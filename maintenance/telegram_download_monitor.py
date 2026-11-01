#!/usr/bin/env python3
"""Poll GitHub counters; send aggregate Telegram notices and Moscow daily reports.

Only the Python standard library is required. No secrets belong in the state file.
"""

import argparse
import copy
from datetime import date, datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

MSK = timezone(timedelta(hours=3), "MSK")
CATEGORIES = ("windows", "android", "engines")
RETENTION_DAYS = 35
GAP = timedelta(minutes=15)


class MonitorError(Exception):
    """Safe to print: never include response bodies, URLs or credentials."""


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(url, headers, payload=None, service="GitHub"):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(url, headers=headers, data=body)
    try:
        with build_opener(NoRedirects()).open(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as exc:
        raise MonitorError(f"{service}: HTTP {exc.code}; state not advanced") from None
    except (URLError, OSError, ValueError):
        raise MonitorError(f"{service}: request or JSON response failed; state not advanced") from None


def natural(value):
    return type(value) is int and value >= 0


def timestamp(value):
    try:
        result = datetime.fromisoformat(value)
        if result.utcoffset() is None:
            raise ValueError
        return result
    except (TypeError, ValueError):
        raise MonitorError("Invalid state timestamp") from None


def category(tag, name):
    """Match the existing badge definitions, including all engine-catalogue assets."""
    tag, name = tag.lower(), name.lower()
    if tag.startswith("engines-"):
        return "engines"
    if tag.startswith("android-") and name.endswith(".apk"):
        return "android"
    if name == "mcrf.exe":
        return "windows"
    return None


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise MonitorError("Invalid GitHub repository")
        self.base = f"https://api.github.com/repos/{repository}"
        self.headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "MCRF-download-monitor",
        }

    def get(self, path):
        return request_json(self.base + path, self.headers)

    def pages(self, path):
        page = 1
        while True:
            items = self.get(f"{path}?per_page=100&page={page}")
            if not isinstance(items, list):
                raise MonitorError("GitHub: invalid paginated response")
            yield from items
            if len(items) < 100:
                return
            page += 1

    def snapshot(self):
        repo = self.get("")
        if not isinstance(repo, dict) or not natural(repo.get("stargazers_count")):
            raise MonitorError("GitHub: invalid star count")
        assets = {}
        for release in self.pages("/releases"):
            if not isinstance(release, dict) or not natural(release.get("id")) or not isinstance(release.get("tag_name"), str):
                raise MonitorError("GitHub: invalid release")
            if release.get("draft"):
                continue
            # The embedded release.assets array may be truncated. Paginate separately.
            for asset in self.pages(f"/releases/{release['id']}/assets"):
                if not isinstance(asset, dict) or not natural(asset.get("id")) or not natural(asset.get("download_count")) or not isinstance(asset.get("name"), str):
                    raise MonitorError("GitHub: invalid release asset")
                kind = category(release["tag_name"], asset["name"])
                if kind is not None:
                    key = str(asset["id"])
                    if key in assets:
                        raise MonitorError("GitHub: duplicate asset ID; retry a fresh snapshot")
                    assets[key] = {"count": asset["download_count"], "category": kind}
        return {"assets": assets, "stars": repo["stargazers_count"]}


def bucket(partial=False, uncertain=False, stars=None):
    return {**dict.fromkeys(CATEGORIES, 0), "star_delta": 0,
            "stars": stars, "partial": partial, "uncertain": uncertain}


def validate_state(state, repository):
    try:
        if type(state["version"]) is not int or state["version"] != 1 or state["repository"] != repository:
            raise ValueError
        started = timestamp(state["started_at"])
        observed = timestamp(state["observed_at"])
        if started > observed or not natural(state["stars"]):
            raise ValueError
        reported = date.fromisoformat(state["last_daily_date"])
        if reported >= observed.astimezone(MSK).date():
            raise ValueError
        if not isinstance(state["assets"], dict) or not isinstance(state["days"], dict) or len(state["days"]) > RETENTION_DAYS:
            raise ValueError
        for key, asset in state["assets"].items():
            if not key.isdecimal() or not natural(asset["count"]) or asset["category"] not in CATEGORIES:
                raise ValueError
        for key, day in state["days"].items():
            if date.fromisoformat(key) > observed.astimezone(MSK).date():
                raise ValueError
            if any(not natural(day[kind]) for kind in CATEGORIES):
                raise ValueError
            if type(day["star_delta"]) is not int or not (day["stars"] is None or natural(day["stars"])):
                raise ValueError
            if type(day["partial"]) is not bool or type(day["uncertain"]) is not bool:
                raise ValueError
    except (KeyError, TypeError, AttributeError, ValueError):
        raise MonitorError("Invalid monitor state; restore it instead of resetting counters") from None


def rows(counts):
    return f"Windows: +{counts['windows']}\nAndroid: +{counts['android']}\nДвижки: +{counts['engines']}"


def advance(previous, snapshot, now, repository):
    """Pure state transition: caller persists only after all messages succeed."""
    now = now.astimezone(MSK)
    today = now.date()
    today_key = today.isoformat()
    if previous is None:
        state = {"version": 1, "repository": repository, "started_at": now.isoformat(),
                 "observed_at": now.isoformat(), "assets": snapshot["assets"],
                 "stars": snapshot["stars"], "last_daily_date": (today - timedelta(days=1)).isoformat(),
                 "days": {today_key: bucket(partial=True, stars=snapshot["stars"])}}
        totals = dict.fromkeys(CATEGORIES, 0)
        for asset in snapshot["assets"].values():
            totals[asset["category"]] += asset["count"]
        return state, [f"MCRF · мониторинг включён ({now:%d.%m.%Y %H:%M} МСК)\n"
                       "Исходные счётчики GitHub, без уведомлений о старых скачиваниях:\n"
                       f"Windows: {totals['windows']}\nAndroid: {totals['android']}\n"
                       f"Движки: {totals['engines']}\nЗвёзды: {snapshot['stars']}\n"
                       "Источник скачивания: неизвестен. Суточный итог — после 12:00 МСК."]

    validate_state(previous, repository)
    state = copy.deepcopy(previous)
    last = timestamp(state["observed_at"]).astimezone(MSK)
    if now < last:
        raise MonitorError("Clock moved backwards; state not advanced")
    earliest = today - timedelta(days=RETENTION_DAYS - 1)
    cursor = max(last.date(), earliest)
    while cursor <= today:
        state["days"].setdefault(cursor.isoformat(), bucket())
        if now - last > GAP:
            state["days"][cursor.isoformat()]["uncertain"] = True
        cursor += timedelta(days=1)

    changes = dict.fromkeys(CATEGORIES, 0)
    # Keep missing asset IDs as tombstones: deletion must not subtract downloads or
    # cause a later reappearance to be counted a second time. Replacements get new IDs.
    for key, asset in snapshot["assets"].items():
        old = state["assets"].get(key)
        delta = asset["count"] if old is None else max(0, asset["count"] - old["count"])
        changes[asset["category"]] += delta
        state["assets"][key] = asset
    star_delta = snapshot["stars"] - state["stars"]
    day = state["days"][today_key]
    for kind in CATEGORIES:
        day[kind] += changes[kind]
    day["star_delta"] += star_delta
    day["stars"] = snapshot["stars"]
    state["stars"] = snapshot["stars"]
    state["observed_at"] = now.isoformat()

    messages = []
    if any(changes.values()) or star_delta:
        messages.append(f"MCRF · изменения к {now:%d.%m.%Y %H:%M} МСК\n{rows(changes)}\n"
                        f"Звёзды: {snapshot['stars']} ({star_delta:+d})\n"
                        "Источник скачивания: неизвестен (GitHub).")

    # A late run catches up after noon. Before noon, yesterday is not yet due.
    due = today - timedelta(days=1 if now.hour >= 12 else 2)
    next_day = date.fromisoformat(state["last_daily_date"]) + timedelta(days=1)
    if next_day < earliest and next_day <= due:
        end = min(due, earliest - timedelta(days=1))
        messages.append(f"MCRF · {next_day:%d.%m.%Y}–{end:%d.%m.%Y}: "
                        "суточные данные недоступны (перерыв больше срока хранения 35 дней).")
        state["last_daily_date"] = end.isoformat()
        next_day = end + timedelta(days=1)
    while next_day <= due:
        entry = state["days"].get(next_day.isoformat())
        if entry is None:
            raise MonitorError("Missing daily bucket; restore monitor state")
        flags = []
        if entry["partial"]:
            flags.append("неполный первый день после запуска")
        if entry["uncertain"]:
            flags.append("есть перерыв наблюдений; нули не доказывают отсутствие скачиваний")
        star_count = str(entry["stars"]) if entry["stars"] is not None else "нет наблюдений"
        messages.append(f"MCRF · итог за {next_day:%d.%m.%Y} (00:00–24:00 МСК)\n"
                        f"{rows(entry)}\nЗвёзды: {star_count} ({entry['star_delta']:+d})\n"
                        "Источник: неизвестен. По разнице опросов, границы суток приблизительны."
                        + ("\n" + "; ".join(flags) + "." if flags else ""))
        state["last_daily_date"] = next_day.isoformat()
        next_day += timedelta(days=1)
    state["days"] = {key: value for key, value in state["days"].items() if key >= earliest.isoformat()}
    return state, messages


def telegram_send(token, chat_id, message):
    result = request_json(f"https://api.telegram.org/bot{token}/sendMessage",
                          {"Content-Type": "application/json"},
                          {"chat_id": chat_id, "text": message}, "Telegram")
    if not isinstance(result, dict) or result.get("ok") is not True:
        raise MonitorError("Telegram rejected the message; state not advanced")


def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=path.name + ".", delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(state, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def run(repository, path, token, chat_id, github_token, now=None):
    previous = None
    if path.exists():
        try:
            previous = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise MonitorError("Unable to read monitor state; restore it instead of resetting") from None
        validate_state(previous, repository)
    snapshot = GitHub(repository, github_token).snapshot()
    state, messages = advance(previous, snapshot, now or datetime.now(timezone.utc), repository)
    for message in messages:
        telegram_send(token, chat_id, message)
    save_state(path, state)
    print(f"Monitor state saved; messages accepted: {len(messages)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "vnenapravo7-source/mcrf"))
    parser.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    values = [os.environ.get(key, "").strip() for key in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "GITHUB_TOKEN")]
    if not all(values):
        parser.exit(1, "Required environment variables: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, GITHUB_TOKEN\n")
    try:
        run(args.repository, args.state, *values)
    except MonitorError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except Exception:
        # urllib errors can include the full token-bearing URL. Never print a traceback.
        print("Monitor failed unexpectedly; inspect state and configuration (details suppressed).", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
