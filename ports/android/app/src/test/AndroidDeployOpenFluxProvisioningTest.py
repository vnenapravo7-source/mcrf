"""Deploy-only regressions. Not executed as part of the source-only package."""
import base64
import copy
import hashlib
import io
import json
import pathlib
import re
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/main/kotlin/org/mcrf/client"
literal = re.search(r'internal val provisioning = """\n(.*?)\n"""\.trimIndent\(\)', (SOURCE / "AndroidDeployScripts.kt").read_text(encoding="utf-8-sig"), re.S).group(1)
p = types.ModuleType("openflux_deploy_fixture")
exec(compile(literal.replace("${'$'}", "$"), "AndroidDeployScripts.provisioning", "exec"), p.__dict__)


class Panel:
    def __init__(self, capability=1, codes=None, error=""):
        self.capability, self.codes, self.error = capability, list(codes or ["room-code"]), error
        self.requests, self.connection = [], None

    def request(self, method, path, body=None, **kwargs):
        self.requests.append((method, path, copy.deepcopy(body)))
        if path == "/api/login":
            return {}
        if method == "POST" and path == "/api/connections":
            self.connection = dict(copy.deepcopy(body), id="fixture-connection")
            return self.connection
        if path == "/api/state":
            rows = []
            if self.connection:
                code = self.codes.pop(0) if len(self.codes) > 1 else self.codes[0]
                rows = [dict(self.connection, running=True, client_code=code, last_error=self.error)]
            return {"mcrf_script_deploy": self.capability, "connections": rows}
        if path.endswith("/share"):
            return {"link": "openflux://v1/" + "A" * 20}
        raise AssertionError(path)

    def listener_ready(self, port):
        return True


class OpenFluxProvisioningTests(unittest.TestCase):
    def test_server_adapter_refuses_unpinned_sources_without_mutating_them(self):
        patch_source = re.search(r'val panelPatch="""\n(.*?)\n"""\.trimIndent\(\)', (SOURCE / "AndroidDeployOpenFlux.kt").read_text(encoding="utf-8-sig"), re.S).group(1)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            (path / "manager.go").write_bytes(b"fixture-unpinned-manager")
            (path / "share.go").write_bytes(b"fixture-unpinned-share")
            with patch("sys.argv", ["panel-adapter", directory]), self.assertRaises(RuntimeError):
                exec(compile(patch_source, "panel-adapter", "exec"), {})
            self.assertEqual(b"fixture-unpinned-manager", (path / "manager.go").read_bytes())
            self.assertEqual(b"fixture-unpinned-share", (path / "share.go").read_bytes())
            self.assertFalse((path / "mcrf_script_deploy.go").exists())

    def provision(self, kind, value="", panel=None):
        panel, links = panel or Panel(), []
        p.provision_openflux(panel, {"transport": kind, "carrier_url": value}, "fixture.invalid", links, "fixture", "fixture-password", lambda _: None)
        return panel, links

    def test_avito_waits_for_generated_room_code_and_exports_once(self):
        panel, links = self.provision("avito", panel=Panel(codes=["", "fixture-code"]))
        self.assertEqual(1, len(links))
        self.assertEqual(2, sum(path == "/api/state" for _, path, _ in panel.requests))
        self.assertEqual("avito", panel.connection["transports"][0]["type"])
        self.assertEqual("", panel.connection["url"])

    def test_existing_room_code_is_preserved_for_both_room_transports(self):
        for kind in ("avito", "cupsonline"):
            with self.subTest(kind=kind):
                code = base64.b64encode(b'["fixture-room"]').decode()
                panel, _ = self.provision(kind, code)
                self.assertEqual(code, panel.connection["url"])
                self.assertEqual(code, panel.connection["transports"][0]["url"])

    def test_script_params_reach_panel_unchanged_without_server_paths_in_payload(self):
        settings = {"token": "fixture;#\nsecret", "value": "значение"}
        value = json.dumps({"name": "mtslink", "url": "https://fixture.invalid/room", "settings": settings})
        with patch.object(p, "prepare_openflux_script") as prepare:
            panel, links = self.provision("script", value)
        prepare.assert_called_once_with("mtslink")
        self.assertEqual({"name": "mtslink", "settings": settings}, panel.connection["transports"][0]["params"])
        self.assertTrue(panel.connection["negotiate"])
        self.assertEqual(1, len(links))
        self.assertEqual(1, sum(path == "/api/connections" for _, path, _ in panel.requests))

    def test_old_panel_refuses_script_before_download_or_connection_creation(self):
        panel = Panel(capability=0)
        with patch.object(p, "prepare_openflux_script") as prepare:
            with self.assertRaises(p.ProvisionError):
                self.provision("script", json.dumps({"name": "mtslink"}), panel)
        prepare.assert_not_called()
        self.assertFalse(any(path == "/api/connections" for _, path, _ in panel.requests))

    def test_package_failure_cannot_create_connection(self):
        panel = Panel()
        with patch.object(p, "prepare_openflux_script", side_effect=p.ProvisionError("fixture refusal")):
            with self.assertRaises(p.ProvisionError):
                self.provision("script", json.dumps({"name": "mtslink"}), panel)
        self.assertIsNone(panel.connection)

    def test_unpublished_and_malformed_script_options_refuse_before_requests(self):
        for value in ({"name": "mtslink-webrtc"}, {"name": "vkteams"}, {"name": "../evil"}, {"name": []},
                      {"name": "mtslink", "path": "/tmp/evil"}, {"name": "mtslink", "settings": {"token": 4}},
                      {"name": "mtslink", "url": "room\nPath=/tmp/evil"}):
            with self.subTest(value=value):
                panel = Panel()
                with self.assertRaises(p.ProvisionError):
                    self.provision("script", json.dumps(value), panel)
                self.assertEqual([], panel.requests)

    def test_engine_error_never_exports_or_retries_create(self):
        panel = Panel(error="fixture failure")
        with self.assertRaises(p.ProvisionError):
            self.provision("avito", panel=panel)
        self.assertEqual(1, sum(path == "/api/connections" for _, path, _ in panel.requests))
        self.assertFalse(any(path.endswith("/share") for _, path, _ in panel.requests))

    def test_package_digest_and_signature_are_both_required_before_publish(self):
        payload = b"fixture-only signed package bytes"
        digest = hashlib.sha256(payload).hexdigest()
        for expected, signature in (("0" * 64, "valid"), (digest, "invalid"), (digest, "valid")):
            with self.subTest(expected=expected, signature=signature), tempfile.TemporaryDirectory() as directory:
                target = pathlib.Path(directory)
                report = types.SimpleNamespace(returncode=0, stdout=json.dumps({"ok": True, "signature": signature, "official": True}).encode())
                with patch.dict(p.OPENFLUX_SCRIPTS, {"mtslink": ("1.0.0", expected)}), patch("pathlib.Path", return_value=target), \
                     patch("urllib.request.urlopen", return_value=io.BytesIO(payload)), patch("subprocess.run", return_value=report) as inspect:
                    if expected == digest and signature == "valid":
                        p.prepare_openflux_script("mtslink")
                        self.assertEqual(payload, (target / "mtslink.flux").read_bytes())
                        self.assertIn("--pubkey=" + p.OPENFLUX_SCRIPT_KEY, inspect.call_args.args[0])
                    else:
                        with self.assertRaises(p.ProvisionError):
                            p.prepare_openflux_script("mtslink")
                        self.assertFalse((target / "mtslink.flux").exists())


if __name__ == "__main__":
    unittest.main()
