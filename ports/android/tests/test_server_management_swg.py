"""Owned in-memory HTTPS doubles only: no account, remote host or socket is contacted."""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("mcrf_management", Path(__file__).resolve().parents[1] / "app/src/main/assets/server-management-swg.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Socket:
    def getpeercert(self, binary_form=False):
        return b"fixture-leaf"

    def close(self):
        pass


class Context:
    def __init__(self, *_):
        pass

    def wrap_socket(self, tcp, server_hostname):
        return tcp


class Response:
    status = 200

    def __init__(self, value):
        self.value = value

    def read(self, maximum):
        return json.dumps(self.value).encode()[:maximum]

    def getheaders(self):
        return [("Set-Cookie", "session=fixture-session; HttpOnly")]


class ManagementTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.failure = False
        self.summary = {"version": "fixture", "throughput": {"rx_bytes_per_sec": 12, "tx_bytes_per_sec": 34}}
        self.state = {"roster": {"users": {"u_fixture": {"name": "Fixture", "private_key": "must-not-escape", "vk_link": "must-not-escape"}}},
                      "nodes": [{"id": "n_fixture", "name": "Node", "status": "online"}],
                      "describe": {"n_fixture": {"wg0": {"tool": "wg", "system": False, "private_key": "must-not-escape"}}}}
        self.restart_ack = {"node": "n_fixture", "iface": "wg0"}
        self.disable_ack = {"id": "u_fixture", "disabled": True}
        self.failure_path = None
        owner = self

        class Connection:
            def __init__(self, *args, **kwargs):
                pass

            def request(self, method, path, payload, headers):
                self.path = path
                owner.calls.append((method, path, payload, headers))
                if owner.failure and path != "/api/login" or path == owner.failure_path:
                    raise OSError("fixture uncertain result")

            def getresponse(self):
                if self.path == "/api/v1/summary":
                    return Response(owner.summary)
                if self.path == "/api/state":
                    return Response({"ok": True, "data": owner.state})
                if self.path == "/api/iface/restart":
                    return Response({"ok": True, "data": owner.restart_ack})
                if self.path == "/api/user/block":
                    return Response({"ok": True, "data": owner.disable_ack})
                return Response({"ok": True, "data": {"id": "u_new"}})

            def close(self):
                pass

        self.patches = [patch.object(module.ssl, "SSLContext", Context),
                        patch.object(module.socket, "create_connection", return_value=Socket()),
                        patch.object(module.http.client, "HTTPSConnection", Connection)]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def source(self, action="clients", parameters=None):
        return {"request_id": "00000000-0000-0000-0000-000000000001", "action": action,
                "parameters": parameters or {}, "confirmed": True, "panel_url": "https://fixture.invalid:8443/",
                "certificate_sha256": hashlib.sha256(b"fixture-leaf").hexdigest(), "username": "fixture", "password": "fixture-password"}

    def test_roster_and_status_projection_drop_secrets(self):
        result = module.execute(self.source())
        self.assertNotIn("must-not-escape", json.dumps(result))
        self.assertEqual("u_fixture", result["clients"][0]["id"])
        self.assertEqual(12, module.execute(self.source("status"))["rx_bytes_per_sec"])

    def test_changed_certificate_sends_no_credentials(self):
        source = self.source()
        source["certificate_sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            module.execute(source)
        self.assertEqual([], self.calls)

    def test_status_version_cannot_inject_control_characters_into_ui(self):
        for control in ("\n", "\r", "\t", "\x7f", "\x85"):
            self.summary["version"] = "fixture" + control
            with self.subTest(control=repr(control)):
                with self.assertRaisesRegex(ValueError, "STATUS_RESULT"):
                    module.execute(self.source("status"))

    def test_status_counters_are_strict_nonnegative_signed_longs(self):
        for value in (True, -1, 1.5, "12", 2**63):
            self.summary["throughput"]["rx_bytes_per_sec"] = value
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "STATUS_RESULT"):
                    module.execute(self.source("status"))

    def test_status_maximum_long_and_empty_version_preserve_contract(self):
        self.summary["version"] = ""
        self.summary["throughput"]["rx_bytes_per_sec"] = 2**63 - 1
        result = module.execute(self.source("status"))
        self.assertEqual("", result["version"])
        self.assertEqual(2**63 - 1, result["rx_bytes_per_sec"])

    def test_disable_is_exact_and_confirmation_is_required(self):
        source = self.source("disable-client", {"client_id": "u_fixture"})
        source["confirmed"] = False
        with self.assertRaises(ValueError):
            module.execute(source)
        self.assertEqual([], self.calls)
        source["confirmed"] = True
        self.assertEqual({"client_id": "u_fixture"}, module.execute(source))
        self.assertEqual("/api/user/block", self.calls[-1][1])
        self.assertEqual({"user_id": "u_fixture"}, json.loads(self.calls[-1][2]))

    def test_disable_requires_exact_boolean_and_matching_identity_without_retry(self):
        for ack in ({"id": "other", "disabled": True}, {"id": "u_fixture", "disabled": False},
                    {"id": "u_fixture", "disabled": 1}, {"id": "u_fixture", "disabled": "true"},
                    {"id": "u_fixture"}, [], None):
            with self.subTest(ack=ack):
                self.calls.clear()
                self.disable_ack = ack
                with self.assertRaisesRegex(ValueError, "CLIENT_DISABLE_ACK"):
                    module.execute(self.source("disable-client", {"client_id": "u_fixture"}))
                self.assertEqual(["/api/login", "/api/user/block"], [row[1] for row in self.calls])

    def test_unsafe_expiry_is_rejected_before_any_network(self):
        for expiry in (0, 1800000000):
            with self.subTest(expiry=expiry):
                with self.assertRaises(ValueError):
                    module.execute(self.source("client-expiry", {"client_id": "u_fixture", "expires_at": expiry}))
        self.assertEqual([], self.calls)

    def test_uncertain_disable_is_not_repeated(self):
        self.failure_path = "/api/user/block"
        with self.assertRaises(OSError):
            module.execute(self.source("disable-client", {"client_id": "u_fixture"}))
        self.assertEqual(["/api/login", "/api/user/block"], [row[1] for row in self.calls])

    def test_uncertain_creation_is_never_retried(self):
        self.failure = True
        with self.assertRaises(OSError):
            module.execute(self.source("add-client", {"name": "Fixture"}))
        self.assertEqual(1, sum(path == "/api/users/create" for _, path, _, _ in self.calls))

    def test_control_names_are_rejected_before_login(self):
        for code in list(range(32)) + list(range(127, 160)):
            with self.subTest(code=code):
                with self.assertRaises(ValueError):
                    module.execute(self.source("add-client", {"name": "Client" + chr(code)}))
        self.assertEqual([], self.calls)

    def test_control_detection_preserves_regular_international_names(self):
        self.assertFalse(module.has_control("Дом — 工作 телефон"))
        module.execute(self.source("add-client", {"name": "Дом — 工作 телефон"}))
        self.assertEqual(1, sum(path == "/api/users/create" for _, path, _, _ in self.calls))

    def test_raw_url_controls_are_not_normalized_into_an_accepted_address(self):
        for control in ("\n", "\r", "\t", "\x7f", "\x85"):
            source = self.source()
            source["panel_url"] = "https://fixture.invalid:8443/" + control
            with self.subTest(control=repr(control)):
                with self.assertRaises(ValueError):
                    module.execute(source)
        self.assertEqual([], self.calls)

    def test_interface_listing_is_read_only_and_drops_private_metadata(self):
        source = self.source("interfaces")
        source["confirmed"] = False
        result = module.execute(source)
        self.assertEqual([{"node": "n_fixture", "node_name": "Node", "iface": "wg0", "tool": "wg"}], result["interfaces"])
        self.assertNotIn("must-not-escape", json.dumps(result))
        self.assertEqual(["/api/login", "/api/state"], [row[1] for row in self.calls])

    def test_system_unknown_offline_and_busy_interfaces_are_not_offered(self):
        interfaces = self.state["describe"]["n_fixture"]
        interfaces.update({"mesh0": {"tool": "wg", "system": True}, "swg_link": {"tool": "awg"},
                           "unknown0": {}, "wdtt0": {"tool": "wdtt"}})
        self.assertEqual(1, len(module.interface_targets(self.state)))
        for field, value in (("status", "offline"), ("migrating", True), ("removing", True), ("proc_status", "updating")):
            old = dict(self.state["nodes"][0])
            self.state["nodes"][0][field] = value
            self.assertEqual([], module.interface_targets(self.state))
            self.state["nodes"][0] = old

    def test_restart_requires_confirmation_and_strict_target_before_login(self):
        source = self.source("restart", {"node": "n_fixture", "iface": "wg0"})
        source["confirmed"] = False
        with self.assertRaisesRegex(ValueError, "CONFIRMATION_REQUIRED"):
            module.execute(source)
        for field, value in (("node", "../node"), ("iface", "wg0;reboot"), ("iface", "a" * 16), ("node", True)):
            source = self.source("restart", {"node": "n_fixture", "iface": "wg0"})
            source["parameters"][field] = value
            with self.subTest(field=field, value=value):
                with self.assertRaises(ValueError):
                    module.execute(source)
        self.assertEqual([], self.calls)

    def test_restart_checks_fresh_membership_and_sends_exactly_one_target(self):
        result = module.execute(self.source("restart", {"node": "n_fixture", "iface": "wg0"}))
        self.assertEqual({"restart_queued": True, "node": "n_fixture", "iface": "wg0"}, result)
        self.assertNotIn("restarted", result)
        self.assertEqual(["/api/login", "/api/state", "/api/iface/restart"], [row[1] for row in self.calls])
        self.assertEqual({"node": "n_fixture", "iface": "wg0"}, json.loads(self.calls[-1][2]))

    def test_vanished_target_cannot_be_restarted_from_a_stale_picker(self):
        self.state["describe"]["n_fixture"].pop("wg0")
        with self.assertRaisesRegex(ValueError, "RESTART_TARGET"):
            module.execute(self.source("restart", {"node": "n_fixture", "iface": "wg0"}))
        self.assertNotIn("/api/iface/restart", [row[1] for row in self.calls])

    def test_wrong_ack_is_not_queue_confirmation_and_never_retried(self):
        self.restart_ack["iface"] = "wg1"
        with self.assertRaisesRegex(ValueError, "RESTART_ACK"):
            module.execute(self.source("restart", {"node": "n_fixture", "iface": "wg0"}))
        self.assertEqual(1, sum(row[1] == "/api/iface/restart" for row in self.calls))

    def test_uncertain_restart_is_not_repeated(self):
        self.failure_path = "/api/iface/restart"
        with self.assertRaises(OSError):
            module.execute(self.source("restart", {"node": "n_fixture", "iface": "wg0"}))
        self.assertEqual(1, sum(row[1] == "/api/iface/restart" for row in self.calls))

    def test_malformed_interface_identity_and_duplicate_nodes_are_rejected(self):
        self.state["nodes"].append(dict(self.state["nodes"][0]))
        with self.assertRaises(ValueError):
            module.interface_targets(self.state)
        self.state["nodes"].pop()
        self.state["describe"]["n_fixture"]["../bad"] = {"tool": "wg"}
        with self.assertRaises(ValueError):
            module.interface_targets(self.state)


if __name__ == "__main__":
    unittest.main()
