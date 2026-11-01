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
        self.assertIn("мониторинг включён", messages[0])
        self.assertIn("Windows: 10000", messages[0])
        self.assertIn("Звёзды: 500", messages[0])
        self.assertNotIn("Windows: +", messages[0])
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
        self.assertIn("неизвестен", messages[0])
        self.assertEqual(state["days"]["2026-10-01"]["star_delta"], 2)

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
        self.assertIn("Windows: +0", messages[0])
        self.assertIn("Звёзды: 4 (-1)", messages[0])
        state, messages = monitor.advance(state, snapshot(3, 4), START + timedelta(minutes=10), REPO)
        self.assertIn("Windows: +1", messages[0])

    def test_moscow_noon_reports_previous_calendar_day_once_and_partial_start(self):
        state = self.baseline()
        state, messages = monitor.advance(state, snapshot(12), START.replace(day=2, hour=11, minute=59), REPO)
        self.assertEqual(len(messages), 1)  # Only the current observation change.
        noon_utc = START.replace(day=2, hour=12).astimezone(monitor.timezone.utc)
        state, messages = monitor.advance(state, snapshot(12), noon_utc, REPO)
        self.assertEqual(len(messages), 1)
        self.assertIn("итог за 01.10.2026 (00:00–24:00 МСК)", messages[0])
        self.assertIn("Windows: +0", messages[0])  # Noon changes belong to Oct 2.
        self.assertIn("неполный первый день", messages[0])
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
        self.assertIn("итог за 02.10.2026", messages[0])
        self.assertIn("Windows: +0\nAndroid: +0\nДвижки: +0", messages[0])
        self.assertIn("Звёзды: 5 (+0)", messages[0])
        self.assertNotIn("неполный", messages[0])
        self.assertNotIn("перерыв", messages[0])

    def test_long_gap_is_bounded_and_reports_unavailable_days_honestly(self):
        state, messages = monitor.advance(self.baseline(), snapshot(20), START + timedelta(days=70, hours=3), REPO)
        self.assertEqual(len(state["days"]), monitor.RETENTION_DAYS)
        self.assertIn("недоступны", messages[1])
        self.assertIn("перерыв наблюдений", messages[-1])
        self.assertIn("нет наблюдений", messages[-1])
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


if __name__ == "__main__":
    unittest.main()
