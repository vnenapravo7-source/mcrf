"""Run: python -m unittest discover -s tests -p test_download_source_service.py"""
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stderr
from datetime import datetime, timedelta
import http.client
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("download_source_service", Path(__file__).resolve().parents[1] / "maintenance" / "download_source_service.py")
service = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(service)
URL = "https://github.com/vnenapravo7-source/mcrf/releases/download/v1.1.2/mcrf-app-update.zip"
ANDROID_URL = "https://github.com/vnenapravo7-source/mcrf/releases/download/android-v0.7/mcrf-android-universal-release.apk"
TOKEN = "test-only-secret-32-characters-long"


class SourceServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.manifest = {"windows-update": {"url": URL, "platform": "windows", "product": "app"},
                         "android-app": {"url": ANDROID_URL, "platform": "android", "product": "app"}}
        self.manifest_file = self.root / "assets.json"
        self.manifest_file.write_text(json.dumps(self.manifest), encoding="utf-8")
        self.counters = service.Counters(self.root / "counts.sqlite3")
        self.server = service.RequestServer(("127.0.0.1", 0), service.load_assets(self.manifest_file), self.counters, TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def event(self, **overrides):
        data = {"source": "update", "platform": "windows", "asset_url": URL}
        data.update(overrides)
        return self.request("POST", "/v1/events", json.dumps(data), {"Content-Type": "application/json"})

    def snapshot(self):
        status, headers, body = self.request("GET", "/v1/stats", headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        return json.loads(body)

    def test_exact_manual_redirect_and_update_are_separate_requests(self):
        status, headers, _ = self.request("GET", "/d/windows-update")
        self.assertEqual(status, 302); self.assertEqual(headers["Location"], URL)
        self.assertEqual(self.event()[0], 202)
        snapshot = self.snapshot()
        self.assertEqual(snapshot["metric"], "download_requests")
        self.assertFalse(snapshot["completed_downloads"])
        self.assertEqual({r["source"]: r["count"] for r in snapshot["totals"]}, {"manual": 1, "update": 1})
        self.assertEqual(snapshot["timezone"], "Europe/Moscow")
        self.assertEqual(snapshot["days"][0]["day"], datetime.now(service.LOCAL_TIMEZONE).date().isoformat())

    def test_stats_require_exact_bearer_secret(self):
        for auth in (None, "Bearer wrong", "Basic " + TOKEN, "Bearer " + TOKEN + "x"):
            self.assertEqual(self.request("GET", "/v1/stats", headers={} if auth is None else {"Authorization": auth})[0], 401)

    def test_event_rejects_forged_source_platform_unknown_url_or_fields(self):
        for fields in ({"source": "manual"}, {"source": "installed"}, {"platform": "android"},
                       {"asset_url": "http://127.0.0.1/private"}, {"asset_url": URL + "?x=1"},
                       {"asset_url": [URL]}, {"extra": "identity"}):
            self.assertEqual(self.event(**fields)[0], 400)
        self.assertEqual(self.snapshot()["totals"], [])

    def test_android_and_engine_dimensions(self):
        self.server.assets_by_url[ANDROID_URL]["product"] = "engine"
        self.assertEqual(self.event(platform="android", asset_url=ANDROID_URL)[0], 202)
        self.assertEqual(self.snapshot()["totals"], [{"source": "update", "platform": "android", "product": "engine", "count": 1}])

    def test_manual_paths_are_not_an_open_redirect(self):
        for path in ("/d/https://evil.example", "/d/windows-update?url=https://evil.example", "/d/../windows-update", "/d/%77indows-update", "/v1/stats?token=" + TOKEN):
            self.assertEqual(self.request("GET", path)[0], 404)
        self.assertEqual(self.snapshot()["totals"], [])

    def test_head_probe_does_not_count(self):
        self.assertEqual(self.request("HEAD", "/d/windows-update")[0], 405)
        self.assertEqual(self.snapshot()["totals"], [])

    def test_malformed_oversize_and_wrong_media_events_rejected(self):
        self.assertEqual(self.request("POST", "/v1/events", "{" , {"Content-Type": "application/json"})[0], 400)
        self.assertEqual(self.request("POST", "/v1/events", "x" * (service.MAX_BODY + 1), {"Content-Type": "application/json"})[0], 413)
        self.assertEqual(self.request("POST", "/v1/events", "{}")[0], 415)
        self.assertEqual(self.request("POST", "/other", "{}")[0], 404)

    def test_only_exact_repository_asset_manifest_urls_allowed(self):
        for bad_url in ("https://evil.example/a", "http://github.com/vnenapravo7-source/mcrf/releases/download/v1/a.zip",
                        URL + "?q=1", URL + "#fragment", URL.replace("github.com", "github.com@evil.example"),
                        URL.replace("mcrf/releases", "other/releases"), URL.replace("v1.1.2", ".."),
                        URL.replace("v1.1.2", "%2e%2e"), URL.replace("github.com", "github.com:443")):
            self.manifest["windows-update"]["url"] = bad_url
            self.manifest_file.write_text(json.dumps(self.manifest))
            with self.assertRaises(ValueError, msg=bad_url): service.load_assets(self.manifest_file)

    def test_duplicate_urls_unknown_dimensions_and_empty_manifest_rejected(self):
        for manifest in ({}, {**self.manifest, "duplicate": self.manifest["windows-update"]},
                         {"x": {"url": URL, "platform": "linux", "product": "app"}},
                         {"x": {"url": URL, "platform": "windows", "product": "other"}}):
            self.manifest_file.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError): service.load_assets(self.manifest_file)

    def test_atomic_concurrent_aggregate_and_no_identity_storage(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(lambda _: self.event()[0], range(24)))
        self.assertEqual(statuses, [202] * 24)
        self.assertEqual(self.snapshot()["totals"][0]["count"], 24)
        with self.counters.connect() as db:
            self.assertEqual([r[1] for r in db.execute("PRAGMA table_info(daily)")], ["day", "source", "platform", "product", "count"])

    def test_counter_epoch_and_start_time_survive_restart(self):
        before = self.snapshot()
        after = service.Counters(self.counters.path).snapshot()
        self.assertEqual(before["counter_epoch"], after["counter_epoch"])
        self.assertEqual(before["started_at"], after["started_at"])

    def test_retention_filters_and_purges_old_aggregates(self):
        old = (datetime.now(service.LOCAL_TIMEZONE).date() - timedelta(days=service.RETENTION_DAYS)).isoformat()
        with self.counters.connect() as db: db.execute("INSERT INTO daily VALUES (?, 'manual', 'windows', 'app', 999)", (old,))
        self.assertEqual(self.snapshot()["totals"], [])
        self.event()
        with self.counters.connect() as db: self.assertEqual(db.execute("SELECT COUNT(*) FROM daily").fetchone()[0], 1)

    def test_storage_failure_keeps_manual_download_and_reports_event_failure(self):
        with patch.object(self.counters, "record", side_effect=sqlite3.OperationalError("test")):
            self.assertEqual(self.request("GET", "/d/windows-update")[0], 302)
            self.assertEqual(self.event()[0], 503)
        with patch.object(self.counters, "snapshot", side_effect=sqlite3.OperationalError("test")):
            self.assertEqual(self.request("GET", "/v1/stats", headers={"Authorization": f"Bearer {TOKEN}"})[0], 503)

    def test_no_request_logging(self):
        output = io.StringIO()
        with redirect_stderr(output): self.request("GET", "/d/windows-update")
        self.assertEqual(output.getvalue(), "")

    def test_handler_failure_never_logs_client_or_exception(self):
        output = io.StringIO()
        with redirect_stderr(output), patch.object(self.counters, "snapshot", side_effect=RuntimeError("sensitive-handler-detail")):
            with self.assertRaises(http.client.RemoteDisconnected):
                self.request("GET", "/v1/stats", headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(output.getvalue(), "")

    def test_database_connection_closes_on_success_and_failure(self):
        for fail in (False, True):
            captured = None
            try:
                with self.counters.connect() as db:
                    captured = db
                    if fail:
                        raise RuntimeError("test")
            except RuntimeError:
                pass
            with self.assertRaises(sqlite3.ProgrammingError):
                captured.execute("SELECT 1")

    def test_secret_required_at_startup(self):
        for bad in ("", "short", "a" * 32 + "\n"):
            with self.assertRaises(ValueError): service.RequestServer(("127.0.0.1", 0), self.manifest, self.counters, bad)


if __name__ == "__main__": unittest.main()
