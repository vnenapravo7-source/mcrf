#!/usr/bin/env python3
"""Anonymous download *request* counters. Run behind the supplied TLS proxy.

No asset is fetched by this service. Only administrator-pinned GitHub URLs may
be redirected to or counted. Historical GitHub downloads have unknown source.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import os
from pathlib import Path
import re
import sqlite3
import uuid
from urllib.parse import urlsplit

REPOSITORY = "vnenapravo7-source/mcrf"
MAX_BODY = 2048
MAX_MANIFEST = 2 * 1024 * 1024
MAX_ASSETS = 4096
RETENTION_DAYS = 730
LOCAL_TIMEZONE = timezone(timedelta(hours=3), name="Europe/Moscow")
COMPONENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}\Z")


def valid_asset_url(value: object) -> bool:
    if not isinstance(value, str) or len(value) > 512:
        return False
    try:
        parsed = urlsplit(value)
        prefix = f"https://github.com/{REPOSITORY}/releases/download/"
        if not value.startswith(prefix) or parsed.query or parsed.fragment:
            return False
        parts = value[len(prefix):].split("/")
        return len(parts) == 2 and all(COMPONENT.fullmatch(p) and ".." not in p for p in parts)
    except ValueError:
        return False


def load_assets(path: Path) -> dict[str, dict[str, str]]:
    if path.stat().st_size > MAX_MANIFEST:
        raise ValueError("asset manifest is too large")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or not 1 <= len(raw) <= MAX_ASSETS:
        raise ValueError("manifest must contain 1..4096 fixed asset IDs")
    urls = set()
    for key, asset in raw.items():
        if not COMPONENT.fullmatch(key) or ".." in key or not isinstance(asset, dict):
            raise ValueError("invalid asset ID")
        if set(asset) != {"url", "platform", "product"}:
            raise ValueError("asset fields must be url, platform, product")
        if not valid_asset_url(asset["url"]) or asset["url"] in urls:
            raise ValueError("unsafe or duplicate asset URL")
        if asset["platform"] not in ("windows", "android") or asset["product"] not in ("app", "engine"):
            raise ValueError("unsupported platform/product")
        urls.add(asset["url"])
    return raw


class Counters:
    def __init__(self, path: Path):
        self.path = path
        with self.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS daily (
                day TEXT NOT NULL, source TEXT NOT NULL, platform TEXT NOT NULL,
                product TEXT NOT NULL, count INTEGER NOT NULL CHECK(count > 0),
                PRIMARY KEY(day, source, platform, product))""")
            db.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            for key, value in (("started_at", datetime.now(LOCAL_TIMEZONE).isoformat()), ("counter_epoch", str(uuid.uuid4()))):
                db.execute("INSERT OR IGNORE INTO metadata VALUES (?, ?)", (key, value))

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        try:
            with db:
                yield db
        finally:
            db.close()

    def record(self, source: str, asset: dict[str, str]):
        now = datetime.now(LOCAL_TIMEZONE).date()
        with self.connect() as db:
            db.execute("DELETE FROM daily WHERE day < ?", ((now - timedelta(days=RETENTION_DAYS - 1)).isoformat(),))
            db.execute("""INSERT INTO daily VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(day,source,platform,product) DO UPDATE SET count=count+1""",
                (now.isoformat(), source, asset["platform"], asset["product"]))

    def snapshot(self):
        cutoff = (datetime.now(LOCAL_TIMEZONE).date() - timedelta(days=RETENTION_DAYS - 1)).isoformat()
        with self.connect() as db:
            db.row_factory = sqlite3.Row
            db.execute("BEGIN")  # Keep metadata, days and totals in one read snapshot.
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
            days = [dict(row) for row in db.execute("SELECT * FROM daily WHERE day >= ? ORDER BY day,source,platform,product", (cutoff,))]
            totals = [dict(row) for row in db.execute("""SELECT source,platform,product,SUM(count) AS count
                FROM daily WHERE day >= ? GROUP BY source,platform,product ORDER BY source,platform,product""", (cutoff,))]
        return {"schema": 1, "metric": "download_requests", "completed_downloads": False,
                "coverage": "controlled_links_and_opted_in_clients_only", "retention_days": RETENTION_DAYS,
                "generated_at": datetime.now(LOCAL_TIMEZONE).isoformat(), "timezone": "Europe/Moscow",
                **metadata, "totals": totals, "days": days}


class RequestServer(ThreadingHTTPServer):
    daemon_threads = True
    # The reverse proxy also limits request concurrency and body sizes.
    request_queue_size = 16

    def handle_error(self, request, client_address):
        # socketserver defaults print the client address and exception traceback.
        # Never retain either, including on disconnects and unexpected failures.
        pass

    def __init__(self, address, assets, counters, stats_token):
        if len(stats_token) < 32 or any(c.isspace() for c in stats_token):
            raise ValueError("stats bearer token must contain at least 32 non-whitespace characters")
        self.assets = assets
        self.assets_by_url = {a["url"]: a for a in assets.values()}
        self.counters = counters
        self.stats_token = stats_token
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    server: RequestServer
    server_version = "MCRFRequestCounter/1"

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *_):
        # Never persist client addresses, user agents, tokens or request paths.
        pass

    def reply(self, status, body=None, location=None):
        encoded = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        if body is not None:
            self.send_header("Content-Type", "application/json")
        if location:
            self.send_header("Location", location)
        self.end_headers()
        if encoded:
            self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/healthz":
            return self.reply(200, {"ok": True})
        if self.path == "/v1/stats":
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + self.server.stats_token).encode()):
                return self.reply(401, {"error": "unauthorized"})
            try:
                return self.reply(200, self.server.counters.snapshot())
            except sqlite3.Error:
                return self.reply(503, {"error": "counter_unavailable"})
        if self.path.startswith("/d/"):
            asset = self.server.assets.get(self.path[3:])
            if asset is None:
                return self.reply(404, {"error": "unknown_asset"})
            try:
                self.server.counters.record("manual", asset)
            except sqlite3.Error:
                # A failed counter must never prevent reaching the real asset.
                pass
            return self.reply(302, location=asset["url"])
        self.reply(404, {"error": "not_found"})

    def do_HEAD(self):
        # Link probes do not count as requests to download.
        self.reply(405)

    def do_POST(self):
        if self.path != "/v1/events":
            return self.reply(404, {"error": "not_found"})
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            return self.reply(415, {"error": "json_required"})
        lengths = self.headers.get_all("Content-Length", [])
        try:
            if len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdecimal():
                raise ValueError()
            length = int(lengths[0])
            if not 1 <= length <= MAX_BODY:
                return self.reply(413, {"error": "body_too_large"})
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError()
            event = json.loads(raw)
            if not isinstance(event, dict) or set(event) != {"source", "platform", "asset_url"}:
                raise ValueError()
            # Public update reports are unauthenticated and spoofable; these
            # aggregate request counters never prove installation or completion.
            if event["source"] != "update" or event["platform"] not in ("windows", "android") or not isinstance(event["asset_url"], str):
                raise ValueError()
            asset = self.server.assets_by_url.get(event["asset_url"])
            if asset is None or asset["platform"] != event["platform"]:
                return self.reply(400, {"error": "unknown_asset_or_platform"})
            self.server.counters.record("update", asset)
        except (ValueError, UnicodeError, TypeError):
            return self.reply(400, {"error": "invalid_event"})
        except (sqlite3.Error, OSError):
            return self.reply(503, {"error": "counter_unavailable"})
        self.reply(202, {"accepted": True, "metric": "download_requests"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    token = os.environ.get("MCRF_SOURCE_STATS_TOKEN", "")
    server = RequestServer((args.host, args.port), load_assets(args.assets), Counters(args.database), token)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
