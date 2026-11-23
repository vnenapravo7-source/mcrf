"""Synthetic/source contracts; these tests never build native code or provide Unix acceptance."""
import pathlib
import sys
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
import bs_engine_inputs as bs
import bs_multicall_inputs as multi
import bs_direct_tun_pair as pair


def rust(digit):
    return {"artifacts": [{"target": abi, "abi": abi, "sha256": digit * 64, "bytes": 123}
                          for abi in bs.ABIS], "directTunFdContract": 1}


class PinOnlyPairTests(unittest.TestCase):
    def files(self):
        old, new = rust("a"), rust("b")
        previous = {pair.PIN_FILE: pair.pin_source(old), "profile.go": b"retained SRTP5 defaults",
                    "profile_test.go": b"retained SRTP tests", "go.mod": b"same dependencies"}
        replacement = dict(previous, **{pair.PIN_FILE: pair.pin_source(new)})
        return previous, replacement, old, new

    def test_only_generated_new_elf_pins_are_admitted(self):
        pair.only_pin_delta(*self.files())

    def test_profile_defaults_tests_and_dependencies_cannot_change(self):
        for name in ("profile.go", "profile_test.go", "go.mod"):
            with self.subTest(name=name):
                previous, replacement, old, new = self.files()
                replacement[name] += b"changed"
                with self.assertRaisesRegex(ValueError, "Only generated"):
                    pair.only_pin_delta(previous, replacement, old, new)

    def test_source_addition_or_removal_cannot_hide_in_a_new_receipt(self):
        for remove in (False, True):
            previous, replacement, old, new = self.files()
            if remove: del replacement["profile_test.go"]
            else: replacement["other.go"] = b"unexpected"
            with self.assertRaisesRegex(ValueError, "inventory"):
                pair.only_pin_delta(previous, replacement, old, new)

    def test_pin_file_may_not_contain_any_functional_delta(self):
        previous, replacement, old, new = self.files()
        replacement[pair.PIN_FILE] += b"\nfunc bypassIdentity() {}\n"
        with self.assertRaisesRegex(ValueError, "functional"):
            pair.only_pin_delta(previous, replacement, old, new)

    def test_reusing_old_go_pins_is_not_a_rebuild(self):
        previous, replacement, old, new = self.files()
        replacement[pair.PIN_FILE] = previous[pair.PIN_FILE]
        with self.assertRaisesRegex(ValueError, "Only generated"):
            pair.only_pin_delta(previous, replacement, old, new)

    def test_old_or_new_pins_must_match_their_actual_rust_evidence(self):
        for wrong_old in (False, True):
            previous, replacement, old, new = self.files()
            if wrong_old: old = rust("c")
            else: new = rust("c")
            with self.assertRaisesRegex(ValueError, "functional"):
                pair.only_pin_delta(previous, replacement, old, new)

    def test_missing_pairing_cannot_admit_the_prepared_go_source(self):
        with self.assertRaisesRegex(ValueError, "pairing receipt"):
            pair.verify_pairing({}, {})

    def test_pins_require_exact_all_three_abis(self):
        for rows in (rust("a")["artifacts"][:1], rust("a")["artifacts"] + rust("a")["artifacts"][:1]):
            with self.assertRaisesRegex(ValueError, "three-ABI"):
                pair.pin_source({"artifacts": rows})

    def test_new_rust_always_reenters_strict_all_abi_unix_verifier(self):
        previous, replacement, old, new = self.files()
        old_proof = {"baselineSource": "unused", "baselineManifest": "unused", "baselineManifestSha256": "unused",
                     "superset": {}, "duplicate": {}}
        row = {"path": "new receipt", "sha256": "pinned by coordinator"}
        with mock.patch.object(pair, "compiled_baseline", return_value=({}, old_proof, previous, old)), \
             mock.patch.object(pair, "verify_compiled_proof", return_value=new) as verify, \
             mock.patch.object(pair.adapter, "selected_sources", return_value=(None, [])), \
             mock.patch.object(pair.adapter, "adapted_sources", return_value=replacement):
            pair.expected_pair({}, row)
            verify.assert_called_once_with(row, all_abis=True)

    def test_manifest_gate_and_build_checks_are_wired_not_cli_capability(self):
        manifest = (ROOT / "engines/EngineUpdateBsManifest.xml").read_text()
        gradle = (ROOT / "engines/build.gradle.kts").read_text()
        verifier = (ROOT / "transports/swg/bs_engine_inputs.py").read_text()
        gate = (ROOT / "transports/swg/bs_multicall_inputs.py").read_text()
        packager = (ROOT / "maintenance/Package-BsEngine.ps1").read_text()
        self.assertIn('android:name="org.mcrf.engines.csqtt_direct_tun_fd" android:value="${csqttDirectTunFdContract}"', manifest)
        self.assertIn('manifestPlaceholders["csqttDirectTunFdContract"]=0', gradle)
        self.assertIn('manifestPlaceholders["csqttDirectTunFdContract"]=bsDescriptor?.get("directTunFdContract")', gradle)
        self.assertIn('verify_pairing(prepared, prepared.get("rust"))', verifier)
        self.assertIn('bs.require(prepared.get("pinOnlyRebuild") is not None', gate)
        self.assertIn('verify_compiled_proof(rust_row,all_abis=True)', gate)
        self.assertIn('--manifest-xmltree-stdin', packager)

    def test_direct_capability_requires_new_version_and_real_unix_evidence(self):
        proof = {"directTunFdContract": 1, "directTunGenerations": ["2.5.2", "2.5.3"],
                 "directTunUnixTests": {"state": "UNIX_DIRECT_TUN_TESTS_PASSED"}}
        self.assertEqual(1, multi.direct_tun_contract({"directTunFdContract": 1, "versionCode": 6}, proof))
        for gate, replacement in [
            ({"directTunFdContract": 1, "versionCode": 5}, proof),
            ({"directTunFdContract": True, "versionCode": 6}, proof),
            ({"directTunFdContract": 1, "versionCode": 6}, dict(proof, directTunUnixTests={})),
            ({"directTunFdContract": 1, "versionCode": 6}, dict(proof, directTunFdContract=0)),
        ]:
            with self.assertRaises(ValueError): multi.direct_tun_contract(gate, replacement)


class ManifestCapabilityTests(unittest.TestCase):
    def test_native251_capability_requires_version2_and_native_acceptance(self):
        proof={"directTunFdContract":2,"directTunGenerations":["2.5.1","2.5.2","2.5.3"],"nativeRoutingContract":1,"directTunUnixTests":{"state":"UNIX_DIRECT_TUN_TESTS_PASSED"}}
        gate={"directTunFdContract":2,"nativeRoutingContract":1,"versionCode":11}
        self.assertEqual(2,multi.direct_tun_contract(gate,proof))
        for bad in [dict(gate,versionCode=10),dict(gate,nativeRoutingContract=0),dict(gate,nativeRoutingContract=True)]:
            with self.assertRaises(ValueError):multi.direct_tun_contract(bad,proof)
        for bad in [dict(proof,nativeRoutingContract=0),dict(proof,directTunGenerations=["2.5.2","2.5.3"]),dict(proof,directTunUnixTests={})]:
            with self.assertRaises(ValueError):multi.direct_tun_contract(gate,bad)

    def xml(self, value="(type 0x10)0x1", name="org.mcrf.engines.csqtt_direct_tun_fd"):
        return ('    E: meta-data (line=4)\n'
                '      A: android:name(0x01010003)="'+name+'" (Raw: "'+name+'")\n'
                '      A: android:value(0x01010024)='+value+'\n')

    def test_exact_integer_zero_or_one_matches_verified_gate(self):
        bs.verify_direct_tun_metadata(self.xml(), 1)
        bs.verify_direct_tun_metadata(self.xml("(type 0x11)0x0"), 0)

    def test_socks_capability_requires_each_exact_role_and_integer(self):
        names = [f"org.mcrf.engines.socks_udp_source.{kind}" for kind in bs.KINDS]
        manifest = "".join(self.xml(name=name) for name in names)
        for kind, name in zip(bs.KINDS, names):
            key = f"socks_udp_source.{kind}"
            bs.verify_direct_tun_metadata(manifest, 1, key)
            for altered in (manifest.replace(self.xml(name=name), ""),
                            manifest + self.xml(name=name),
                            manifest.replace(self.xml(name=name), self.xml('"1"', name))):
                with self.assertRaises(ValueError):
                    bs.verify_direct_tun_metadata(altered, 1, key)

    def test_native_routing_metadata_requires_exact_generation_list(self):
        session=self.xml(name="org.mcrf.engines.bs_routing_session")
        modes=self.xml('"2.5.1,2.5.2,2.5.3" (Raw: "2.5.1,2.5.2,2.5.3")',name="org.mcrf.engines.bs_routing_generations")
        bs.verify_direct_tun_metadata(self.xml("(type 0x10)0x2"),2)
        bs.verify_native_routing_metadata(session+modes,1)
        for text in [session,session+modes+modes,(session+modes).replace("2.5.1,2.5.2,2.5.3","2.5.3"),session+self.xml("(type 0x10)0x1",name="org.mcrf.engines.bs_routing_generations")]:
            with self.assertRaises(ValueError):bs.verify_native_routing_metadata(text,1)

    def test_missing_duplicate_or_unrelated_metadata_is_rejected(self):
        for text in ("", self.xml()+self.xml(), self.xml(name="other")):
            with self.assertRaises(ValueError): bs.verify_direct_tun_metadata(text, 1)

    def test_string_boolean_or_wrong_integer_cannot_grant_capability(self):
        for value in ('"1" (Raw: "1")', "(type 0x12)0xffffffff", "(type 0x10)0x0", "(type 0x10)0x2"):
            with self.assertRaises(ValueError): bs.verify_direct_tun_metadata(self.xml(value), 1)

    def test_neighboring_element_cannot_supply_the_capability_value(self):
        text = self.xml().split('      A: android:value')[0] + self.xml(name="other")
        with self.assertRaises(ValueError): bs.verify_direct_tun_metadata(text, 1)

    def test_gate_boolean_is_not_an_integer_capability(self):
        with self.assertRaises(ValueError): bs.verify_direct_tun_metadata(self.xml(), True)


if __name__ == "__main__": unittest.main()
