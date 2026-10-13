import base64
import copy
import hashlib
import importlib.util
import json
import pathlib
import sys
import unittest
import urllib.parse
import zlib
from unittest.mock import patch, MagicMock

sys.dont_write_bytecode = True
source = pathlib.Path(__file__).parent.parent / "Provisioning.py"
spec = importlib.util.spec_from_file_location("mcrf_provision", source)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
CALL = "https://vk.ru/call/join/" + "A" * 43
KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
LINKS = []


def result():
    return {"panel_url": "https://fixture.example:8443/panel/", "username": "fixture",
            "password": "fixture-panel-password", "ready": True, "certificate_sha256": "a" * 64, "connections": []}


def options(**values):
    out = dict(create=True, tcp=True, grpc=True, tcp_port=12325, grpc_port=15931, sni="pinterest.com",
               grpc_service="", transport="direct", direct_port=5443, carrier_url="https://docs.example/test",
               relay_kind="qwdtt", relay_port=56000, wg_port=51820, vk_call=CALL)
    out.update(values)
    return out


class FakePanel:
    def __init__(self, kind):
        self.kind, self.requests, self.rows = kind, [], []
        self.fail_grpc = self.unconfirmed = self.auth_required = self.no_nodes = self.not_running = False
        self.modern_settings = True
        self.iface = ""
        self.password = "FixturePassword234"

    def listener_ready(self, port):
        return not self.not_running

    def request(self, method, path, body=None, **kwargs):
        self.requests.append((method, path, copy.deepcopy(body), kwargs))
        if self.kind == "3xui":
            if path.endswith("getNewX25519Cert"):
                return {"success": True, "obj": {"privateKey": KEY, "publicKey": KEY}}
            if path.endswith("/add"):
                if self.fail_grpc and body["port"] == 15931:
                    raise p.ProvisionError("API панели: HTTP 400. Запрос не повторялся.")
                if not self.unconfirmed:
                    # The real panel is allowed to normalize the caller's tag.
                    row = dict(body, tag="panel-normalized-" + str(body["port"]))
                    self.rows.append(row)
                return {"success": True, "obj": {"id": len(self.rows)}}
            if path.endswith("/list"):
                rows = copy.deepcopy(self.rows)
                if self.modern_settings:
                    for row in rows:
                        for key in ("settings", "streamSettings", "sniffing"):
                            if isinstance(row.get(key), str):
                                row[key] = json.loads(row[key])
                return {"success": True, "obj": rows}
        if self.kind == "openflux":
            if path.endswith("/login"):
                return {"me": {"id": "fixture-user"}}
            if method == "POST" and path == "/api/connections":
                # The pinned panel uses DisallowUnknownFields, including Config's JSON tags.
                allowed = {"name", "owner_id", "id", "enabled", "transport", "url", "mode", "codec", "local_ip",
                           "encryption_key_file", "negotiate", "session_context_url", "transports", "direct_listen",
                           "max_packet_size", "debug", "auto_update"}
                if set(body) - allowed:
                    raise p.ProvisionError("API панели: HTTP 400. Запрос не повторялся.")
                self.rows.append(dict(body, id="fixture-connection"))
                return self.rows[-1]
            if path == "/api/state":
                return {"connections": [dict(self.rows[-1], running=not self.not_running,
                        yandex_auth_required=self.auth_required, client_code="fixture-room-code")]}
            if path.endswith("/share"):
                payload = {"name": "Fixture OpenFlux", "negotiate": True, "secret": "fixture-secret-key-1234567890",
                           "context": "direct", "transports": [{"type": "direct", "dial": "192.0.2.1:5443", "priority": 100}]}
                encoder = zlib.compressobj(wbits=-15)
                packed = encoder.compress(json.dumps(payload).encode()) + encoder.flush()
                return {"link": "openflux://v1/" + base64.urlsafe_b64encode(packed).decode().rstrip("=")}
        if self.kind == "swg":
            if path == "/api/login":
                return {"ok": True, "data": {}}
            if path == "/api/state":
                group = "csqtt" if any("csqtt/set" in q[1] for q in self.requests) else "wdtt"
                return {"ok": True, "data": {"nodes": [] if self.no_nodes else [{"id": "fixture-node"}],
                    "snapshots": {"fixture-node": {group: [{"iface": self.iface,
                    "active": "inactive" if self.not_running else "active", "passwords": {self.password: {}}}]}}}}
            if path.endswith("/set"):
                self.iface = body["iface"]
                return {"ok": True, "data": {}}
            if path == "/api/users/create":
                return {"ok": True, "data": {"id": "fixture-user"}}
            if path == "/api/users/update":
                return {"ok": True, "data": {}}
            if path == "/api/peers/create":
                return {"ok": True, "data": {"wdtt_password": self.password, "csqtt_password": self.password}}
        raise AssertionError((method, path))


class PanelAPITests(unittest.TestCase):
    def setUp(self):
        self.cert = b"fixture certificate"
        self.fingerprint = hashlib.sha256(self.cert).hexdigest()
        self.connection = MagicMock()
        self.response = MagicMock()
        self.response.status = 200
        self.response.read.return_value = b'{"ok":true}'
        self.response.getheaders.return_value = []
        self.connection.getresponse.return_value = self.response
        self.tcp = MagicMock()
        self.wrapped = MagicMock()
        self.wrapped.getpeercert.return_value = self.cert
        self.ctx = MagicMock()
        self.ctx.wrap_socket.return_value = self.wrapped
        self.patchers = [
            patch.object(p.http.client, "HTTPSConnection", return_value=self.connection),
            patch.object(p.socket, "create_connection", return_value=self.tcp),
            patch.object(p.ssl, "SSLContext", return_value=self.ctx),
        ]
        for patcher in self.patchers:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.api = p.PanelAPI("https://fixture.example:8443/panel/", self.fingerprint)

    def test_api_preserves_host_sni_base_path_and_pin(self):
        self.assertEqual({"ok": True}, self.api.request("GET", "/api/state"))
        self.ctx.wrap_socket.assert_called_once_with(self.tcp, server_hostname="fixture.example")
        method, path, payload, headers = self.connection.request.call_args.args
        self.assertEqual(("GET", "/panel/api/state", None), (method, path, payload))
        self.assertEqual("fixture.example:8443", headers["Host"])
        self.connection.close.assert_called_once()

    def test_wrong_certificate_prevents_request(self):
        self.api.fingerprint = "0" * 64
        with self.assertRaises(p.ProvisionError):
            self.api.request("POST", "/api/connections", {"password": "fixture-secret"})
        self.connection.request.assert_not_called()
        self.connection.close.assert_called_once()

    def test_wrap_failure_closes_raw_socket(self):
        self.ctx.wrap_socket.side_effect = OSError("fixture TLS failure")
        with self.assertRaises(p.ProvisionError):
            self.api.request("GET", "/api/state")
        self.tcp.close.assert_called_once()
        self.connection.request.assert_not_called()

    def test_cookies_and_authorization_are_carried_to_next_request(self):
        self.response.getheaders.return_value = [("Set-Cookie", "of_session=fixture-cookie; HttpOnly; Secure")]
        self.api.request("POST", "/api/login", {"username": "fixture", "password": "fixture-password"})
        self.api.bearer = "fixture-api-token"
        self.api.request("POST", "/api/connections", {"transport": "direct"}, action=True)
        headers = self.connection.request.call_args.args[3]
        self.assertEqual("of_session=fixture-cookie", headers["Cookie"])
        self.assertEqual("Bearer fixture-api-token", headers["Authorization"])
        self.assertEqual("1", headers["X-OpenFlux-Action"])

    def test_redirect_error_and_large_reply_are_not_retried_or_disclosed(self):
        for status, raw in [(302, b"fixture-password"), (500, b"fixture-password"), (200, b"x" * (4 * 1024 * 1024 + 1))]:
            with self.subTest(status=status):
                self.connection.reset_mock()
                self.response.status = status
                self.response.read.return_value = raw
                with self.assertRaises(p.ProvisionError) as error:
                    self.api.request("POST", "/api/connections", {"password": "fixture-password"})
                self.assertNotIn("fixture-password", str(error.exception))
                self.assertEqual(1, self.connection.request.call_count)
                self.connection.close.assert_called_once()

    def test_connection_error_and_invalid_json_are_not_retried(self):
        for failure in ("network", "json"):
            with self.subTest(failure=failure):
                self.connection.reset_mock()
                self.connection.request.side_effect = OSError("fixture-password") if failure == "network" else None
                self.response.read.return_value = b"<html>fixture-password</html>"
                with self.assertRaises(p.ProvisionError) as error:
                    self.api.request("POST", "/api/connections", {})
                self.assertNotIn("fixture-password", str(error.exception))
                self.assertEqual(1, self.connection.request.call_count)

    def test_unsafe_api_path_never_connects(self):
        for path in ["https://elsewhere.example", "/../api/state", "api/state"]:
            with self.assertRaises(p.ProvisionError):
                self.api.request("POST", path)
        self.connection.request.assert_not_called()


class ProvisioningTests(unittest.TestCase):
    def configure(self, kind, opts=None, api=None):
        api = api or FakePanel(kind)
        r = p.configure_initial(result(), kind, opts or options(), "192.0.2.1", api, sleep=lambda _: None)
        return r, api

    def test_tcp_grpc_parameters_and_fresh_credentials(self):
        r, api = self.configure("3xui")
        self.assertTrue(r["provision_complete"])
        self.assertEqual(2, len(r["connections"]))
        requests = [q for q in api.requests if q[1].endswith("/add")]
        self.assertEqual(2, len(requests))
        identities = []
        for _, _, body, kw in requests:
            self.assertTrue(kw["form"])
            settings, stream = json.loads(body["settings"]), json.loads(body["streamSettings"])
            identities.append(settings["clients"][0]["id"])
            self.assertEqual("", settings["clients"][0]["flow"])
            self.assertEqual("reality", stream["security"])
            self.assertEqual("pinterest.com:443", stream["realitySettings"]["target"])
        self.assertEqual(2, len(set(identities)))
        tcp, grpc = [urllib.parse.parse_qs(urllib.parse.urlsplit(link).query, keep_blank_values=True) for link in r["connections"]]
        self.assertEqual(["tcp"], tcp["type"])
        self.assertEqual(["chrome"], tcp["fp"])
        self.assertEqual(["grpc"], grpc["type"])
        self.assertEqual(["safari"], grpc["fp"])
        self.assertEqual([""], grpc["serviceName"])
        LINKS.extend(r["connections"])

    def test_saved_inbound_without_listener_not_exported(self):
        api = FakePanel("3xui")
        api.not_running = True
        r, _ = self.configure("3xui", api=api)
        self.assertFalse(r["provision_complete"])
        self.assertEqual([], r["connections"])

    def test_current_and_legacy_settings_response_shapes(self):
        for modern in [True, False]:
            api = FakePanel("3xui")
            api.modern_settings = modern
            r, _ = self.configure("3xui", api=api)
            self.assertTrue(r["provision_complete"])
            self.assertEqual(2, len(r["connections"]))

    def test_select_only_tcp(self):
        r, api = self.configure("3xui", options(grpc=False, grpc_port=None))
        self.assertTrue(r["provision_complete"])
        self.assertEqual(1, len(r["connections"]))

    def test_grpc_service(self):
        r, _ = self.configure("3xui", options(tcp=False, grpc_service="fixture-grpc"))
        self.assertIn("serviceName=fixture-grpc", r["connections"][0])

    def test_no_selected_transport_makes_no_mutation(self):
        r, api = self.configure("3xui", options(tcp=False, grpc=False))
        self.assertFalse(r["provision_complete"])
        self.assertEqual([], api.requests)

    def test_port_collision_preserves_existing_profile(self):
        api = FakePanel("3xui")
        api.rows = [{"port": 12325, "tag": "existing"}]
        r, _ = self.configure("3xui", api=api)
        self.assertFalse(r["provision_complete"])
        self.assertFalse(any(q[0] == "POST" for q in api.requests))
        self.assertEqual("existing", api.rows[0]["tag"])

    def test_partial_success_keeps_confirmed_tcp_without_retry(self):
        api = FakePanel("3xui")
        api.fail_grpc = True
        r, _ = self.configure("3xui", api=api)
        self.assertTrue(r["ready"])
        self.assertFalse(r["provision_complete"])
        self.assertEqual(1, len(r["connections"]))
        self.assertEqual(1, sum(q[1].endswith("/add") and q[2]["port"] == 15931 for q in api.requests))
        self.assertEqual("fixture-panel-password", r["password"])

    def test_unconfirmed_created_profile_is_not_exported(self):
        api = FakePanel("3xui")
        api.unconfirmed = True
        r, _ = self.configure("3xui", api=api)
        self.assertFalse(r["provision_complete"])
        self.assertEqual([], r["connections"])
        self.assertEqual(1, sum(q[1].endswith("/add") for q in api.requests))

    def test_bad_sni_and_ports_make_no_mutation(self):
        for opts in [options(sni="bad;cmd"), options(tcp_port=443), options(grpc_port=12325)]:
            with self.subTest(opts=opts):
                r, api = self.configure("3xui", opts)
                self.assertFalse(r["provision_complete"])
                self.assertEqual([], api.requests)

    def test_openflux_all_carriers_use_protected_session(self):
        for kind in ["direct", "yandex", "vyandex", "boards", "telemost", "mailru", "cupsonline"]:
            with self.subTest(kind=kind):
                r, api = self.configure("openflux", options(transport=kind))
                self.assertTrue(r["provision_complete"], r["provision_error"])
                body = next(q[2] for q in api.requests if q[1] == "/api/connections")
                self.assertEqual(kind, body["transports"][0]["type"])
                self.assertEqual("batched", body["codec"])
                self.assertEqual("l4", body["mode"])
                self.assertIs(False, body["auto_update"])
                self.assertNotIn("autoupdate", body)
                self.assertEqual(1, sum(q[1] == "/api/connections" for q in api.requests))
                self.assertTrue(all(q[3].get("action") for q in api.requests if q[0] == "POST"))
                if kind == "direct":
                    self.assertEqual("0.0.0.0:5443", body["direct_listen"])
                    LINKS.extend(r["connections"])

    def test_yandex_login_requirement_not_reported_ready_connection(self):
        api = FakePanel("openflux")
        api.auth_required = True
        r, _ = self.configure("openflux", options(transport="yandex"), api)
        self.assertTrue(r["ready"])
        self.assertFalse(r["provision_complete"])
        self.assertIn("Yandex", r["provision_error"])
        self.assertEqual([], r["connections"])

    def test_openflux_not_running_not_exported(self):
        api = FakePanel("openflux")
        api.not_running = True
        r, _ = self.configure("openflux", api=api)
        self.assertFalse(r["provision_complete"])
        self.assertEqual([], r["connections"])
        self.assertFalse(any(q[1].endswith("/share") for q in api.requests))

    def test_swg_all_protocols(self):
        for kind in ["qwdtt", "wdtt", "csqtt"]:
            with self.subTest(kind=kind):
                r, api = self.configure("swg", options(relay_kind=kind))
                self.assertTrue(r["provision_complete"], r["provision_error"])
                body = next(q[2] for q in api.requests if q[1].endswith("/set"))
                self.assertEqual("wdttplus" if kind == "wdtt" else kind, body["fork"])
                self.assertFalse(body["raw"])
                self.assertEqual(1, sum(q[1] == "/api/peers/create" for q in api.requests))
                updated = next(q[2] for q in api.requests if q[1] == "/api/users/update")
                self.assertEqual([CALL], updated["vk_links"])
                self.assertTrue(r["connections"][0].startswith(kind + "://"))
                LINKS.extend(r["connections"])

    def test_six_manual_calls_bound_and_exported_for_each_swg_protocol(self):
        calls = ["https://vk.ru/call/join/" + base64.urlsafe_b64encode(bytes([i + 1]) * 32).decode().rstrip("=") for i in range(6)]
        for kind in ["qwdtt", "wdtt", "csqtt"]:
            with self.subTest(kind=kind):
                r, api = self.configure("swg", options(relay_kind=kind, vk_calls=calls))
                self.assertTrue(r["provision_complete"])
                updates = [q[2] for q in api.requests if q[1] == "/api/users/update"]
                self.assertEqual(1, len(updates))
                self.assertEqual(calls, updates[0]["vk_links"])
                exported = urllib.parse.parse_qs(urllib.parse.urlsplit(r["connections"][0]).query)["hashes"][0].split(",")
                self.assertEqual([call.rsplit("/", 1)[1] for call in calls], exported)
                self.assertFalse(any("vk.ru" in q[1] or "calls.start" in q[1] for q in api.requests))

    def test_duplicate_invalid_or_too_many_calls_never_mutate_swg(self):
        for calls in [[CALL, CALL], [CALL, "invalid"], [CALL] * 33]:
            r, api = self.configure("swg", options(vk_calls=calls))
            self.assertFalse(r["provision_complete"])
            self.assertEqual([], api.requests)

    def test_csqtt_does_not_require_hidden_wg_port(self):
        r, _ = self.configure("swg", options(relay_kind="csqtt", wg_port=None))
        self.assertTrue(r["provision_complete"])

    def test_missing_call_and_nodes_have_no_creation(self):
        r, api = self.configure("swg", options(vk_call="invalid"))
        self.assertEqual([], api.requests)
        api = FakePanel("swg")
        api.no_nodes = True
        r, _ = self.configure("swg", api=api)
        self.assertFalse(r["provision_complete"])
        self.assertFalse(any(q[1].endswith("/set") for q in api.requests))

    def test_inactive_node_never_exports_peer(self):
        api = FakePanel("swg")
        api.not_running = True
        r, _ = self.configure("swg", api=api)
        self.assertFalse(r["provision_complete"])
        self.assertEqual([], r["connections"])

    def test_disabled_creation_never_calls_api(self):
        for kind in ["3xui", "openflux", "swg"]:
            r, api = self.configure(kind, options(create=False))
            self.assertFalse(r["provision_requested"])
            self.assertEqual([], api.requests)

    def test_unexpected_failure_does_not_expose_provider_secret(self):
        api = FakePanel("swg")
        api.request = lambda *a, **kw: (_ for _ in ()).throw(ValueError("fixture-provider-secret"))
        r, _ = self.configure("swg", api=api)
        self.assertNotIn("fixture-provider-secret", r["provision_error"])
        self.assertTrue(r["ready"])


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    outcome = unittest.TextTestRunner(verbosity=2).run(suite)
    for link in LINKS:
        print("MCRF_TEST_LINK|" + base64.b64encode(link.encode()).decode())
    sys.exit(0 if outcome.wasSuccessful() else 1)
