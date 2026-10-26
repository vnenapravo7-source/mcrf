"""New RAW deployment cases only; no old suite, network, account or VPS writes."""
import json
import pathlib
import re
import types
import unittest
import urllib.parse

root = pathlib.Path(__file__).resolve().parents[5]
source = (root / "ports/android/app/src/main/kotlin/org/mcrf/client/AndroidDeployScripts.kt").read_text(encoding="utf-8-sig")
literal = re.search(r'internal val provisioning = """\n(.*?)\n"""\.trimIndent\(\)', source, re.S).group(1).replace("${'$'}", "$")
p = types.ModuleType("owned_raw_provision")
exec(compile(literal, "AndroidDeployScripts.provisioning", "exec"), p.__dict__)

class FakeRawPanel:
    def __init__(self, occupied=False):
        self.occupied = occupied
        self.iface = ""
        self.writes = []

    def request(self, method, path, body=None):
        if method == "GET" and path == "/api/state":
            records = [{"iface": self.iface, "active": "active", "passwords": {"owned-fixture-password": {}}, "raw_port": 56003}] if self.iface else []
            node = {"id": "owned-node", "wdtt_cfg": {"existing": {"raw_port": 56003}} if self.occupied else {}}
            return {"ok": True, "data": {"nodes": [node], "snapshots": {"owned-node": {"wdtt": records}}}}
        if path == "/api/wdtt/set":
            self.iface = body["iface"]
            self.writes.append(body)
        if path == "/api/users/create":
            return {"ok": True, "data": {"id": "owned-user"}}
        if path == "/api/peers/create":
            return {"ok": True, "data": {"wdtt_password": "owned-fixture-password"}}
        return {"ok": True, "data": {}}

class RawDeployTests(unittest.TestCase):
    def options(self):
        return {"relay_kind": "qwdtt", "relay_port": 56000, "wg_port": 51820,
                "vk_calls": ["https://vk.ru/call/join/" + "A" * 32]}

    def test_raw_listener_and_export_agree(self):
        api = FakeRawPanel()
        links = []
        p.provision_swg_single(api, self.options(), "192.0.2.1", links, "owned", "owned", sleep=lambda _: None)
        self.assertTrue(api.writes[0]["raw"])
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(links[0]).query)
        self.assertEqual(["raw"], query["mode"])
        self.assertEqual(["56003"], query["raw_port"])

    def test_existing_raw_owner_is_never_disabled(self):
        api = FakeRawPanel(occupied=True)
        with self.assertRaises(p.ProvisionError):
            p.provision_swg_single(api, self.options(), "192.0.2.1", [], "owned", "owned", sleep=lambda _: None)
        self.assertEqual([], api.writes)

    def test_reserved_raw_port_cannot_be_used_as_turn_port(self):
        api = FakeRawPanel()
        options = self.options()
        options["relay_port"] = 56003
        with self.assertRaises(p.ProvisionError):
            p.provision_swg_single(api, options, "192.0.2.1", [], "owned", "owned", sleep=lambda _: None)
        self.assertEqual([], api.writes)

if __name__ == "__main__":
    unittest.main()
