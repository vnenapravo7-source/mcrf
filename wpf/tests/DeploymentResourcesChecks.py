"""New batch: repeated named profiles, ports/subnets and real OS-read contract."""
import copy
import json
import unittest
import urllib.parse
from types import SimpleNamespace
from unittest.mock import patch
from ProvisioningChecks20261013 import p, FakePanel, options, result


class ResourceTests(unittest.TestCase):
    def test_os_listeners_addresses_and_routes_are_read_without_shell(self):
        api = p.PanelAPI("https://example.test:28443", "a" * 64)
        outputs = ["udp UNCONN 0 0 0.0.0.0:56000 0.0.0.0:*\ntcp LISTEN 0 128 [::]:28443 [::]:*\n",
                   json.dumps([{"addr_info": [{"family": "inet", "local": "10.88.0.1", "prefixlen": 24}]}]),
                   json.dumps([{"dst": "default"}, {"dst": "10.89.0.0/16"}])]
        with patch.object(p.subprocess, "run", side_effect=[SimpleNamespace(stdout=s) for s in outputs]) as run:
            ports, networks = api.server_resources()
        self.assertEqual({56000, 28443}, ports)
        self.assertEqual(["10.88.0.0/24", "10.89.0.0/16"], networks)
        self.assertTrue(all(isinstance(c.args[0], list) and not c.kwargs.get("shell") for c in run.call_args_list))

    def test_os_resource_failure_is_fail_closed(self):
        api = p.PanelAPI("https://example.test:28443", "a" * 64)
        with patch.object(p.subprocess, "run", side_effect=OSError("fixture")):
            with self.assertRaises(p.ProvisionError):
                api.server_resources()

    def test_live_desired_inactive_and_reserved_ports_are_all_avoided(self):
        api = FakePanel("swg")
        api.occupied_ports = {56000}
        desired = {"csqtt": {"old": {"listen": "0.0.0.0:56001", "stopped": True}},
                   "wdtt": {"pending": {"listen": "0.0.0.0:56002", "wg_port": 56004}}}
        opts = options(relay_port=56000, wg_port=56000)
        original = copy.deepcopy(desired)
        p.assign_ports(api, opts, ["relay_port", "wg_port"], desired, reserved=(56003,))
        self.assertEqual((56005, 56006), (opts["relay_port"], opts["wg_port"]))
        self.assertEqual(original, desired)

    def test_port_range_wrap_is_bounded(self):
        api = FakePanel("swg")
        api.occupied_ports = {65535, 1024}
        opts = options(relay_port=65535)
        p.assign_ports(api, opts, ["relay_port"])
        self.assertEqual(1025, opts["relay_port"])

    def test_networks_avoid_pending_and_live_tunnels(self):
        ports, networks = p.claimed_resources({"csqtt": {"pending": {"tun_addr": "10.88.1.1/24"}},
                                               "wdtt": [{"wg_addr": "10.88.2.1/24"}]})
        self.assertEqual("10.88.3.1/24", p.free_tunnel_network(["10.88.0.0/24"] + networks))
        self.assertEqual("10.89.0.1/24", p.free_tunnel_network(["10.88.0.0/16"]))

    def test_same_protocol_can_be_created_twice_with_names_and_distinct_resources(self):
        for kind in ("csqtt", "wdtt", "qwdtt"):
            api = FakePanel("swg")
            first = p.configure_initial(result(), "swg", options(relay_kind=kind, connection_name="Первый узел"), "192.0.2.1", api, sleep=lambda _: None)
            self.assertTrue(first["provision_complete"], first["provision_error"])
            original = copy.deepcopy(next(c[2] for c in api.requests if c[1].endswith("/set")))
            api.occupied_ports = set(first["ports"].values())
            api.occupied_networks = [first["tunnel_address"]]
            second = p.configure_initial(result(), "swg", options(relay_kind=kind, connection_name="Второй узел"), "192.0.2.1", api, sleep=lambda _: None)
            self.assertTrue(second["provision_complete"], second["provision_error"])
            created = [c[2] for c in api.requests if c[1].endswith("/set")]
            self.assertNotEqual(created[0]["iface"], created[1]["iface"])
            self.assertEqual(original, created[0])
            self.assertEqual(["Первый узел", "Второй узел"], [b["title"] for b in created])
            self.assertTrue(set(first["ports"].values()).isdisjoint(second["ports"].values()))
            self.assertNotEqual(first["tunnel_address"], second["tunnel_address"])
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(second["connections"][0]).query)
            self.assertEqual(["Второй узел"], query["name"])

    def test_csqtt21_repeated_named_deployment_preserves_legacy_format(self):
        api = FakePanel("swg")
        api.csqtt_line, api.csqtt_build = "2.1", "2.1.9-4"
        api.occupied_ports, api.occupied_networks = {56000}, ["10.88.0.1/24"]
        r = p.configure_initial(result(), "swg", options(relay_kind="csqtt", csqtt_line="2.1", connection_name="Ещё CSQTT 2.1"), "192.0.2.1", api, sleep=lambda _: None)
        self.assertTrue(r["provision_complete"], r["provision_error"])
        self.assertEqual("2.1", r["csqtt_line"])
        self.assertTrue(r["connections"][0].startswith("csqtt://connect?"))
        self.assertEqual({"relay_port": 56001}, r["ports"])

    def test_openflux_direct_avoids_occupied_port_and_keeps_name(self):
        api = FakePanel("openflux")
        api.occupied_ports = {5443}
        r = p.configure_initial(result(), "openflux", options(transport="direct", connection_name="Мой Flux"), "192.0.2.1", api, sleep=lambda _: None)
        self.assertTrue(r["provision_complete"], r["provision_error"])
        self.assertEqual("0.0.0.0:5444", api.rows[0]["direct_listen"])
        self.assertEqual("Мой Flux", api.rows[0]["name"])

    def test_vless_named_tcp_grpc_reserve_ports_as_one_batch(self):
        api = FakePanel("3xui")
        api.occupied_ports = {12325, 15931}
        r = p.configure_initial(result(), "3xui", options(connection_name="Офис"), "192.0.2.1", api, sleep=lambda _: None)
        self.assertTrue(r["provision_complete"], r["provision_error"])
        self.assertEqual([12326, 15932], [row["port"] for row in api.rows])
        self.assertEqual(["Офис · TCP", "Офис · GRPC"], [row["remark"] for row in api.rows])

    def test_invalid_names_never_mutate(self):
        for name in ("x" * 81, "bad\nname"):
            api = FakePanel("swg")
            r = p.configure_initial(result(), "swg", options(connection_name=name), "192.0.2.1", api, sleep=lambda _: None)
            self.assertFalse(r["provision_complete"])
            self.assertEqual([], api.requests)

    def test_resource_probe_failure_does_not_create_a_profile(self):
        api = FakePanel("swg")
        def fail():
            raise p.ProvisionError("resource check unavailable")
        api.server_resources = fail
        r = p.configure_initial(result(), "swg", options(), "192.0.2.1", api, sleep=lambda _: None)
        self.assertFalse(r["provision_complete"])
        self.assertFalse(any(c[1].endswith("/set") or c[1].endswith("/create") for c in api.requests))


if __name__ == "__main__":
    unittest.main(verbosity=2)
