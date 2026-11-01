"""Focused offline regression tests; no network or Telegram messages."""

from datetime import datetime, timedelta
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

SPEC = importlib.util.spec_from_file_location("monitor", Path(__file__).with_name("telegram_download_monitor.py"))
monitor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(monitor)
REPO = "example/repo"
START = datetime(2026, 10, 1, 10, 0, tzinfo=monitor.MSK)


def snapshot(count=10, stars=5, assets=None):
    return {"assets": assets if assets is not None else {"1": {"count": count, "category": "windows"}}, "stars": stars}


class StateTests(unittest.TestCase):
    def baseline(self, when=START, data=None):
        return monitor.advance(None, data or snapshot(), when, REPO)[0]

    def test_first_run_confirms_baseline_without_historical_delta_spam(self):
        state, messages = monitor.advance(None, snapshot(10000, 500), START, REPO)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0], "✅ Мониторинг включён")
        self.assertEqual(state["assets"]["1"]["count"], 10000)
        self.assertEqual(state["stars"], 500)
        self.assertEqual(state["days"]["2026-10-01"]["windows"], 0)
        self.assertTrue(state["days"]["2026-10-01"]["partial"])
        monitor.validate_state(state, REPO)

    def test_aggregate_all_three_categories_and_stars_in_one_notice(self):
        state = self.baseline()
        data = snapshot(stars=7, assets={"1": {"count": 12, "category": "windows"},
                                       "2": {"count": 3, "category": "android"},
                                       "3": {"count": 4, "category": "engines"}})
        state, messages = monitor.advance(state, data, START + timedelta(minutes=5), REPO)
        self.assertEqual(len(messages), 1)
        self.assertIn("Windows: +2\nAndroid: +3\nДвижки: +4", messages[0])
        self.assertIn("Звёзды: 7 (+2)", messages[0])
        self.assertEqual(messages[0], "📥 Скачивания\nWindows: +2\nAndroid: +3\nДвижки: +4\n⭐ Звёзды: 7 (+2)")
        self.assertEqual(state["days"]["2026-10-01"]["star_delta"], 2)

    def test_download_notice_omits_zero_categories_and_unchanged_stars(self):
        state, messages = monitor.advance(self.baseline(), snapshot(12), START + timedelta(minutes=5), REPO)
        self.assertEqual(messages, ["📥 Скачивания\nWindows: +2"])
        _, messages = monitor.advance(state, snapshot(12), START + timedelta(minutes=10), REPO)
        self.assertEqual(messages, [])

    def test_asset_deletion_replacement_and_reappearance_never_subtract_or_recount(self):
        state = self.baseline()
        state, messages = monitor.advance(state, snapshot(assets={}), START + timedelta(minutes=5), REPO)
        self.assertEqual(messages, [])
        state, messages = monitor.advance(state, snapshot(assets={"2": {"count": 3, "category": "windows"}}), START + timedelta(minutes=10), REPO)
        self.assertIn("Windows: +3", messages[0])
        state, messages = monitor.advance(state, snapshot(assets={"1": {"count": 11, "category": "windows"}, "2": {"count": 3, "category": "windows"}}), START + timedelta(minutes=15), REPO)
        self.assertIn("Windows: +1", messages[0])
        self.assertEqual(state["days"]["2026-10-01"]["windows"], 4)

    def test_decreasing_asset_counter_rebaselines_without_negative_downloads(self):
        state, messages = monitor.advance(self.baseline(), snapshot(2, 4), START + timedelta(minutes=5), REPO)
        self.assertEqual(messages, ["⭐ Звёзды: 4 (-1)"])
        state, messages = monitor.advance(state, snapshot(3, 4), START + timedelta(minutes=10), REPO)
        self.assertIn("Windows: +1", messages[0])

    def test_moscow_noon_reports_previous_calendar_day_once_and_partial_start(self):
        state = self.baseline()
        state, messages = monitor.advance(state, snapshot(12), START.replace(day=2, hour=11, minute=59), REPO)
        self.assertEqual(len(messages), 1)  # Only the current observation change.
        noon_utc = START.replace(day=2, hour=12).astimezone(monitor.timezone.utc)
        state, messages = monitor.advance(state, snapshot(12), noon_utc, REPO)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0], "📊 За 01.10.2026\nWindows: 0\nAndroid: 0\nДвижки: 0\n⭐ Звёзды: 5 (+0)\nНеполные данные")
        state, messages = monitor.advance(state, snapshot(12), START.replace(day=2, hour=12, minute=5), REPO)
        self.assertEqual(messages, [])

    def test_zero_day_report_and_daily_star_delta(self):
        state = self.baseline()
        # Enough observations to prove no >15-minute gap on the reported zero day.
        now = START
        end = START.replace(day=3, hour=12)
        while now < end:
            now += timedelta(minutes=5)
            state, messages = monitor.advance(state, snapshot(), now, REPO)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0], "📊 За 02.10.2026\nWindows: 0\nAndroid: 0\nДвижки: 0\n⭐ Звёзды: 5 (+0)")

    def test_long_gap_is_bounded_and_reports_unavailable_days_honestly(self):
        state, messages = monitor.advance(self.baseline(), snapshot(20), START + timedelta(days=70, hours=3), REPO)
        self.assertEqual(len(state["days"]), monitor.RETENTION_DAYS)
        self.assertEqual(messages[1], "📊 За 01.10.2026–05.11.2026\nНеполные данные")
        self.assertEqual(messages[-1].count("Неполные данные"), 1)
        self.assertIn("⭐ Звёзды: — (+0)", messages[-1])
        monitor.validate_state(state, REPO)

    def test_corrupt_state_and_backwards_clock_fail_closed(self):
        state = self.baseline()
        with self.assertRaises(monitor.MonitorError):
            monitor.advance(state, snapshot(), START - timedelta(seconds=1), REPO)
        for bad in ({}, {**state, "repository": "wrong/repo"}, {**state, "stars": True}, {**state, "days": {"bad": {}}}):
            with self.assertRaises(monitor.MonitorError):
                monitor.validate_state(bad, REPO)

    def test_telegram_failure_does_not_overwrite_durable_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            monitor.save_state(path, self.baseline())
            original = path.read_bytes()
            with patch.object(monitor.GitHub, "snapshot", return_value=snapshot(12)), patch.object(monitor, "telegram_send", side_effect=monitor.MonitorError("rejected")):
                with self.assertRaises(monitor.MonitorError):
                    monitor.run(REPO, path, "fake", "fake", "fake", START + timedelta(minutes=5))
            self.assertEqual(path.read_bytes(), original)


class ApiTests(unittest.TestCase):
    def test_categories_match_badge_rules(self):
        self.assertEqual(monitor.category("v1.1", "MCRF.exe"), "windows")
        self.assertEqual(monitor.category("android-v0.7", "mcrf.apk"), "android")
        self.assertEqual(monitor.category("engines-2026-10-13-1", "module.zip"), "engines")
        self.assertIsNone(monitor.category("v1", "source.zip"))

    def test_release_and_asset_pagination_ignore_embedded_assets(self):
        api = monitor.GitHub(REPO, "fake")
        calls = []
        def get(path):
            calls.append(path)
            if path == "":
                return {"stargazers_count": 9}
            if path == "/releases?per_page=100&page=1":
                return [{"id": n, "tag_name": "v1", "draft": n != 1, "assets": []} for n in range(1, 101)]
            if path == "/releases?per_page=100&page=2":
                return [{"id": 101, "tag_name": "android-v0.7"}]
            if path == "/releases/1/assets?per_page=100&page=1":
                return [{"id": n, "name": "mcrf.exe", "download_count": 1} for n in range(1, 101)]
            if path == "/releases/1/assets?per_page=100&page=2":
                return [{"id": 101, "name": "MCRF.exe", "download_count": 2}]
            if path == "/releases/101/assets?per_page=100&page=1":
                return [{"id": 102, "name": "mcrf.apk", "download_count": 3}]
            raise AssertionError(path)
        with patch.object(api, "get", side_effect=get):
            result = api.snapshot()
        self.assertEqual(len(result["assets"]), 102)
        self.assertEqual(result["assets"]["102"]["category"], "android")
        self.assertIn("/releases?per_page=100&page=2", calls)
        self.assertIn("/releases/1/assets?per_page=100&page=2", calls)

    def test_malformed_api_data_fails_instead_of_zeroing_counters(self):
        api = monitor.GitHub(REPO, "fake")
        with patch.object(api, "get", return_value={"stargazers_count": "5"}):
            with self.assertRaises(monitor.MonitorError):
                api.snapshot()

    def test_network_errors_never_expose_secret_url(self):
        secret_url = "https://api.telegram.org/botTEST-SECRET/sendMessage"
        for error in (HTTPError(secret_url, 401, "TEST-SECRET", {}, None), URLError(secret_url), ValueError(secret_url)):
            with patch.object(monitor, "build_opener") as opener:
                opener.return_value.open.side_effect = error
                with self.assertRaises(monitor.MonitorError) as caught:
                    monitor.telegram_send("TEST-SECRET", "123", "message")
                self.assertNotIn("TEST-SECRET", str(caught.exception))
                self.assertNotIn("https://", str(caught.exception))

    def test_telegram_requires_ok_true_and_posts_json(self):
        with patch.object(monitor, "request_json", return_value={"ok": False, "description": "sensitive"}) as request:
            with self.assertRaises(monitor.MonitorError) as caught:
                monitor.telegram_send("fake", "123", "hello")
            self.assertNotIn("sensitive", str(caught.exception))
            self.assertEqual(request.call_args.args[2], {"chat_id": "123", "text": "hello"})


def raw_sources(now=START, rows=None, epoch="11111111-1111-4111-8111-111111111111", started=None):
    return {"schema": 1, "metric": "download_requests", "completed_downloads": False,
            "coverage": "controlled_links_and_opted_in_clients_only", "timezone": "Europe/Moscow",
            "retention_days": 730, "counter_epoch": epoch, "started_at": (started or START).isoformat(),
            "generated_at": now.isoformat(), "totals": [], "days": rows or []}


def source_row(day="2026-10-01", count=1, source="manual", platform="windows", product="app"):
    return {"day": day, "count": count, "source": source, "platform": platform, "product": product}


def source_stats(raw, now=START):
    with patch.object(monitor, "request_json", return_value=raw):
        return monitor.read_source_stats(monitor.SOURCE_STATS_URL, "test-only", now)


class SourceStatsTests(unittest.TestCase):
    def test_fixed_https_origin_and_redirects_prevent_credential_leaks(self):
        for url in ("http://vnenapravo7.ru/mcrf-downloads/v1/stats", "https://evil.example/v1/stats",
                    monitor.SOURCE_STATS_URL + "?x=1", "https://vnenapravo7.ru@evil.example/mcrf-downloads/v1/stats"):
            with patch.object(monitor, "request_json") as request:
                with self.assertRaises(monitor.MonitorError):
                    monitor.read_source_stats(url, "secret", START)
                request.assert_not_called()
        self.assertIsNone(monitor.NoRedirects().redirect_request(None, None, 302, "", {}, "https://evil.example"))

    def test_source_schema_requires_request_semantics_freshness_and_unique_rows(self):
        valid = raw_sources(rows=[source_row()])
        for bad in ({**valid, "completed_downloads": True}, {**valid, "timezone": "UTC"},
                    {**valid, "generated_at": (START - timedelta(hours=1)).isoformat()},
                    {**valid, "days": [source_row(), source_row()]},
                    {**valid, "days": [source_row(count=True)]}):
            with self.assertRaises(monitor.MonitorError):
                source_stats(bad)

    def test_initial_request_baseline_never_spams_historical_deltas(self):
        stats = source_stats(raw_sources(rows=[source_row(count=400)]))
        state = {}
        messages = monitor.integrate_source({}, state, [], stats, START)
        self.assertEqual(messages, [])
        self.assertEqual(state["source_stats"], stats)
        self.assertNotIn("test-only", json.dumps(state))

    def test_request_deltas_are_separate_from_github_and_engines_combine_platforms(self):
        old = source_stats(raw_sources(rows=[source_row(count=3)]))
        now = START + timedelta(minutes=5)
        stats = source_stats(raw_sources(now, rows=[source_row(count=5), source_row(source="update", count=4, platform="android"),
                                                  source_row(source="update", count=2, product="engine"),
                                                  source_row(source="update", count=3, platform="android", product="engine")]), now)
        state, core = monitor.advance(StateTests().baseline(), snapshot(19), now, REPO)
        messages = monitor.integrate_source({"source_stats": old, "source_status": "available"}, state, core, stats, now)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0], "📥 Скачивания\nWindows: +9\n\n🔗 Переходы по ссылкам\nWindows: +2\n\n🔄 Запросы обновления\nAndroid: +4\nДвижки: +5")
        self.assertEqual(state["days"]["2026-10-01"]["windows"], 9)
        self.assertEqual(state["days"]["2026-10-01"]["android"], 0)

    def test_daily_source_rows_are_sparse_and_share_one_partial_marker(self):
        now = START + timedelta(days=1)
        stats = source_stats(raw_sources(now, rows=[source_row(count=2)]), now)
        self.assertEqual(monitor.source_day(stats, START.date()), "🔗 Переходы по ссылкам\nWindows: 2\nНеполные данные")
        self.assertEqual(monitor.source_day(stats, START.date() + timedelta(days=1)), "")
        self.assertEqual(monitor.source_day(stats, START.date() - timedelta(days=1)), "")
        daily = "📊 За 01.10.2026\nWindows: 0\nAndroid: 0\nДвижки: 0\n⭐ Звёзды: 5 (+0)\nНеполные данные"
        messages = monitor.integrate_source({}, {}, [daily], stats, now)
        self.assertEqual(messages, [daily.removesuffix("\nНеполные данные") + "\n\n🔗 Переходы по ссылкам\nWindows: 2\nНеполные данные"])
        self.assertEqual(monitor.integrate_source({}, {}, [daily], None, now), [daily])

    def test_epoch_reset_rebaselines_instead_of_counting_entire_new_database(self):
        old = source_stats(raw_sources())
        new = source_stats(raw_sources(rows=[source_row(count=600)], epoch="22222222-2222-4222-8222-222222222222"))
        messages = monitor.integrate_source({"source_stats": old, "source_status": "available"}, {}, [], new, START)
        self.assertEqual(messages, [])

    def test_unavailable_is_silent_and_recovery_uses_prior_baseline(self):
        old = source_stats(raw_sources(rows=[source_row(count=3)]))
        state = {"source_stats": old}
        messages = monitor.integrate_source({"source_stats": old, "source_status": "available"}, state, [], None, START)
        self.assertEqual(messages, [])
        self.assertEqual(state["source_stats"], old)
        self.assertEqual(monitor.integrate_source(state, dict(state), [], None, START), [])
        recovered = source_stats(raw_sources(rows=[source_row(count=5)]))
        messages = monitor.integrate_source(state, {}, [], recovered, START)
        self.assertEqual(messages, ["🔗 Переходы по ссылкам\nWindows: +2"])

    def test_service_failure_and_invalid_optional_state_do_not_block_github(self):
        for old in (None, {"epoch": "broken", "days": "malformed"}):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "state.json"
                state = monitor.advance(None, snapshot(), START, REPO)[0]
                if old:
                    state["source_stats"] = old
                monitor.save_state(path, state)
                with patch.object(monitor.GitHub, "snapshot", return_value=snapshot(12)), patch.object(monitor, "read_source_stats", side_effect=monitor.MonitorError("unavailable")), patch.object(monitor, "telegram_send") as send:
                    monitor.run(REPO, path, "fake", "fake", "fake", START + timedelta(minutes=5), monitor.SOURCE_STATS_URL, "test-only")
                saved = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(saved["assets"]["1"]["count"], 12)
                self.assertEqual(saved["source_status"], "unavailable")
                self.assertEqual(send.call_count, 1)
                self.assertEqual(send.call_args.args[2], "📥 Скачивания\nWindows: +2")

    def test_old_source_days_falling_out_of_retention_never_subtract_new_requests(self):
        old = {"epoch": "same", "days": {"2026-09-01": {"manual": {"windows": 300, "android": 0, "engines": 0}, "update": dict.fromkeys(monitor.CATEGORIES, 0)}}}
        current = source_stats(raw_sources(rows=[source_row(count=2)]))
        current["epoch"] = "same"
        messages = monitor.integrate_source({"source_stats": old, "source_status": "available"}, {}, [], current, START)
        self.assertEqual(messages, ["🔗 Переходы по ссылкам\nWindows: +2"])

    def test_existing_state_persists_source_only_delta_without_recount_on_restart(self):
        old = source_stats(raw_sources(rows=[source_row(count=3)]))
        now = START + timedelta(minutes=5)
        current = source_stats(raw_sources(now, rows=[source_row(count=5)]), now)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            state = StateTests().baseline()
            state.update(source_stats=old, source_status="available")
            monitor.save_state(path, state)
            with patch.object(monitor.GitHub, "snapshot", return_value=snapshot()), patch.object(monitor, "read_source_stats", return_value=current), patch.object(monitor, "telegram_send") as send:
                monitor.run(REPO, path, "fake", "fake", "fake", now, monitor.SOURCE_STATS_URL, "test-only")
                self.assertEqual(send.call_args.args[2], "🔗 Переходы по ссылкам\nWindows: +2")
                send.reset_mock()
                monitor.run(REPO, path, "fake", "fake", "fake", now + timedelta(minutes=5), monitor.SOURCE_STATS_URL, "test-only")
                send.assert_not_called()
            saved = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(saved["started_at"], state["started_at"])
            self.assertEqual(saved["source_stats"], current)
            self.assertEqual(saved["assets"], state["assets"])
            self.assertEqual(saved["days"]["2026-10-01"]["windows"], 0)


if __name__ == "__main__":
    unittest.main()
