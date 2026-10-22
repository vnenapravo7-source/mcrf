"""Run the existing read-only MCRF panel fixtures against the Android-embedded server payload.
No server, account, phone, Gradle, or real network is used.
"""
import pathlib
import re
import sys
import types
import unittest
import urllib.parse
import base64
import json

sys.dont_write_bytecode = True
only_new = sys.argv[1:] == ["--new-deploy-call-checks"]
if sys.argv[1:] and not only_new:
    raise SystemExit("Unknown verification scope")
root = pathlib.Path(__file__).resolve().parents[5]
source = root / "ports/android/app/src/main/kotlin/org/mcrf/client/AndroidDeployScripts.kt"
literal = re.search(r'internal val provisioning = """\n(.*?)\n"""\.trimIndent\(\)', source.read_text(encoding="utf-8-sig"), re.S).group(1)
literal = literal.replace("${'$'}", "$")
embedded = types.ModuleType("mcrf_provision")
exec(compile(literal, "AndroidDeployScripts.provisioning", "exec"), embedded.__dict__)
fixture = (root / "wpf/tests/ProvisioningChecks20261013.py").read_text(encoding="utf-8-sig")
fixture = re.sub(r'source = pathlib\.Path.*?spec\.loader\.exec_module\(p\)', 'p = embedded', fixture, count=1, flags=re.S)
# Windows fixtures use the older common comma serializer. Preserve their API checks,
# but decode Android exports using each original protocol's separator rules.
old_decode = 'urllib.parse.parse_qs(urllib.parse.urlsplit(r["connections"][0]).query)["hashes"][0].split(",")'
fixture = fixture.replace(old_decode, 'android_hashes(r["connections"][0], kind)')
def android_hashes(link, kind):
    query = urllib.parse.urlsplit(link).query
    raw = next(part.split("=", 1)[1] for part in query.split("&") if part.startswith("hashes="))
    if kind == "csqtt":
        return [urllib.parse.unquote(value) for value in raw.split("+")]
    decoded = urllib.parse.unquote_plus(raw)
    return decoded.splitlines() if kind == "qwdtt" else decoded.split(",")
suite_module = types.ModuleType("AndroidDeployProvisioningFixtures")
suite_module.__dict__.update(embedded=embedded, android_hashes=android_hashes, __file__=str(root / "wpf/tests/ProvisioningChecks20261013.py"))
exec(compile(fixture, "AndroidDeployProvisioningFixtures", "exec"), suite_module.__dict__)
qr_exports = []
class AndroidOriginalCodecTests(unittest.TestCase):
    def test_expanded_call_payload_keeps_size_and_duplicate_guards(self):
        valid = ["https://vk.ru/call/join/" + base64.urlsafe_b64encode(bytes([i])*32).decode() for i in range(7)]
        self.assertTrue(embedded.valid_swg_calls(valid))
        self.assertFalse(embedded.valid_swg_calls(valid + valid[:1]))
        self.assertFalse(embedded.valid_swg_calls([None]))
        oversized = ["https://vk.ru/call/join/" + ("A" * 500) + str(i) for i in range(200)]
        self.assertFalse(embedded.valid_swg_calls(oversized))
    def test_expanded_calls_preserved_by_original_separator_order(self):
        for count in (7, 33, 64):
            hashes = [base64.urlsafe_b64encode(bytes([i+1])*32).decode() for i in range(count)]
            calls = ["https://vk.ru/call/join/"+value for value in hashes]
            for kind in ("qwdtt", "wdtt", "csqtt"):
                with self.subTest(count=count, kind=kind):
                    api = suite_module.FakePanel("swg")
                    result = embedded.configure_initial(suite_module.result(), "swg", suite_module.options(relay_kind=kind, vk_calls=calls), "fixture.example", api, lambda _: None)
                    self.assertTrue(result["provision_complete"])
                    link = result["connections"][0]
                    self.assertEqual(hashes, android_hashes(link, kind))
                    qr_exports.append({"kind": kind, "count": count, "hashes": hashes, "link": link, "password": api.password})
                    update = next(row[2] for row in api.requests if row[1] == "/api/users/update")
                    self.assertEqual(calls, update["vk_links"])
                    raw = urllib.parse.urlsplit(link).query
                    if kind == "qwdtt":
                        self.assertEqual(count-1, raw.count("%0A"))
                    elif kind == "csqtt":
                        self.assertEqual(count-1, raw.count("+"))
                        self.assertNotIn("%2B", raw)
class AndroidSwgMultiTests(unittest.TestCase):
    def profiles(self):
        return [dict(kind=kind, enabled=True, relay_port=56000+i, wg_port=51820+i,
                     call_count=1, vk_calls=["https://vk.ru/call/join/"+base64.urlsafe_b64encode(bytes([40+i])*32).decode()])
                for i, kind in enumerate(("qwdtt", "wdtt", "csqtt"))]

    def configure(self, profiles, api=None):
        api = api or suite_module.FakePanel("swg")
        return embedded.configure_initial(suite_module.result(), "swg", suite_module.options(swg_profiles=profiles), "fixture.example", api, lambda _: None), api

    def test_three_protocols_export_three_links_independent_ports_subnets_calls(self):
        profiles = self.profiles()
        result, api = self.configure(profiles)
        self.assertTrue(result["provision_complete"], result["provision_error"])
        self.assertEqual(["qwdtt", "wdtt", "csqtt"], [link.split(":", 1)[0] for link in result["connections"]])
        rows = [row[2] for row in api.requests if row[1].endswith("/set")]
        self.assertEqual(3, len(rows))
        self.assertEqual(3, len({body["listen"] for body in rows}))
        self.assertEqual(3, len({body.get("wg_addr", body.get("tun_addr")) for body in rows}))
        updates = [row[2]["vk_links"] for row in api.requests if row[1] == "/api/users/update"]
        self.assertEqual([p["vk_calls"] for p in profiles], updates)

    def test_duplicate_ports_rejected_before_any_panel_request(self):
        profiles = self.profiles()
        profiles[2]["relay_port"] = profiles[0]["wg_port"]
        result, api = self.configure(profiles)
        self.assertFalse(result["provision_complete"])
        self.assertEqual([], api.requests)

    def test_shared_calls_rejected_before_any_panel_request(self):
        profiles = self.profiles()
        profiles[2]["vk_calls"] = profiles[0]["vk_calls"]
        result, api = self.configure(profiles)
        self.assertFalse(result["provision_complete"])
        self.assertEqual([], api.requests)

    def test_invalid_last_selection_never_creates_first_protocol(self):
        profiles = self.profiles()
        profiles[2]["vk_calls"] = ["invalid"]
        result, api = self.configure(profiles)
        self.assertFalse(result["provision_complete"])
        self.assertEqual([], api.requests)

    def test_partial_failure_preserves_completed_link_without_retry(self):
        api = suite_module.FakePanel("swg")
        original = api.request
        attempts = []
        def request(method, path, body=None, **kwargs):
            if path.endswith("/set"):
                attempts.append(path)
                if len(attempts) == 2:
                    raise embedded.ProvisionError("fixture second protocol failure")
            return original(method, path, body, **kwargs)
        api.request = request
        result, _ = self.configure(self.profiles(), api)
        self.assertFalse(result["provision_complete"])
        self.assertEqual(1, len(result["connections"]))
        self.assertEqual(2, len(attempts))

if only_new:
    suite = unittest.TestSuite(AndroidOriginalCodecTests(name) for name in (
        "test_expanded_call_payload_keeps_size_and_duplicate_guards",
        "test_expanded_calls_preserved_by_original_separator_order"))
else:
    suite = unittest.defaultTestLoader.loadTestsFromModule(suite_module)
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(AndroidSwgMultiTests))
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(AndroidOriginalCodecTests))
outcome = unittest.TextTestRunner(verbosity=1).run(suite)
if outcome.wasSuccessful() and not only_new:
    fixture_path = root / "ports/android/app/src/test/resources/AndroidDeploy-export-links.json"
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_path.write_text(json.dumps({"fixture_only": True, "source": "AndroidDeployScripts.provisioning with FakePanel; no account/server", "exports": qr_exports}, ensure_ascii=True, indent=2)+"\n", encoding="utf-8")
sys.exit(0 if outcome.wasSuccessful() else 1)
