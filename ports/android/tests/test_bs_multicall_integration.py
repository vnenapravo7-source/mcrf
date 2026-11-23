import pathlib
import sys
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'transports/swg'))
import bs_engine_inputs as bs
import bs_multicall_inputs as multi
import prepare_bs_wrapper as wrapper
from test_bs_engine_source import QWDTT_DISPATCHER, QWDTT_UNDERLAY_SOURCES


class IntegrationTests(unittest.TestCase):
    def test_gooby_requires_exact_native_wrapper_and_new_package(self):
        key='csqttGoobyBuiltinContract'
        self.assertEqual(0,multi.gooby_contract({'versionCode':9},{},{}))
        self.assertEqual(1,multi.gooby_contract({'versionCode':10,key:1},{key:1},{key:1}))
        for values in ((1,0,1),(1,1,0),(0,1,1),(True,1,1),(1,True,1),(1,1,True)):
            with self.assertRaises(ValueError):
                multi.gooby_contract({'versionCode':10,key:values[0]},{key:values[1]},{key:values[2]})
        with self.assertRaises(ValueError):multi.gooby_contract({'versionCode':9,key:1},{key:1},{key:1})
    def test_srtp_capability_requires_matching_fresh_wrapper_not_version_or_rust_alone(self):
        self.assertEqual(0,multi.srtp_mode_contract({'versionCode':4},{}))
        self.assertEqual(0,multi.srtp_mode_contract({'versionCode':500},{}))
        self.assertEqual(1,multi.srtp_mode_contract({'versionCode':5,'csqtt25SrtpModeContract':1},
                                                  {'csqtt25SrtpModeContract':1}))
        for gate,receipt in [
            ({'versionCode':5,'csqtt25SrtpModeContract':1},{}),
            ({'versionCode':5},{'csqtt25SrtpModeContract':1}),
            ({'versionCode':4,'csqtt25SrtpModeContract':1},{'csqtt25SrtpModeContract':1}),
            ({'versionCode':5,'csqtt25SrtpModeContract':True},{'csqtt25SrtpModeContract':1}),
            ({'versionCode':5,'csqtt25SrtpModeContract':1},{'csqtt25SrtpModeContract':True}),
        ]:
            with self.assertRaises(ValueError):multi.srtp_mode_contract(gate,receipt)

    def test_srtp_metadata_defaults_zero_and_is_enabled_only_from_verified_bs_descriptor(self):
        manifest=(ROOT/'engines/src/main/AndroidManifest.xml').read_text(encoding="utf-8")
        gradle=(ROOT/'engines/build.gradle.kts').read_text(encoding="utf-8")
        self.assertIn('android:name="org.mcrf.engines.csqtt25_srtp_mode" android:value="${csqtt25SrtpModeContract}"',manifest)
        self.assertIn('manifestPlaceholders["csqtt25SrtpModeContract"]=0',gradle)
        self.assertIn('if(bsDescriptor?.get("csqtt25SrtpModeContract")==1)1 else 0',gradle)
        native=(ROOT/'app/src/main/kotlin/org/mcrf/client/NativeCarrier.kt').read_text(encoding="utf-8")
        self.assertIn('CarrierOptionContract.missing(profile){info.metaData?.getInt(it)?:0}',native)
        producer=(ROOT/'transports/swg/Build-BsWrapper.ps1').read_text(encoding="utf-8")
        for case in multi.SRTP_TESTS:self.assertIn(case,producer)

    def test_fresh_wrapper_uses_current_modern_profile_and_tests_not_frozen_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            frozen={
                'csqtt_bridge.go':b'func runCSQTTBridgePrepared(\n\tmcrfNativeStreams.useChild()\nname = "libcsqtt_transport.so"\n\tcommand := exec.Command(filepath.Join(filepath.Dir(exe), name))\n\t// MTU is fixed at 1300 in the original Android VPN service.',
                'dispatcher.go':QWDTT_DISPATCHER,
                **QWDTT_UNDERLAY_SOURCES,
                'main.go':b'"-csqtt25-config" "-csqtt-config"\nfunc main() {\n}',
                'internal/csqtt25/profile.go':b'old-frozen-profile',
                'internal/csqtt25/profile_test.go':b'old-frozen-test',
            }
            for name,content in frozen.items():
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(content)
            files=wrapper.adapted_sources(root,[{'path':name} for name in frozen],True)
            rows=wrapper.modern_profile_overlays()
            self.assertEqual({'internal/csqtt25/profile.go','internal/csqtt25/profile_test.go','internal/csqtt25/profile_gooby_test.go'},
                             {row['destination'] for row in rows})
            for row in rows:
                current=ROOT/row['canonical']
                self.assertEqual(current.read_bytes(),files[row['destination']])
                self.assertEqual(bs.sha(current),row['sha256'])

    def test_unmeasured_profile_cannot_claim_release_acceptance(self):
        gate={'schema':3,'state':'BS_SOURCE_BOUND_INPUTS_READY','layout':bs.LAYOUT,
              'package':'org.mcrf.engines.bs','logicalEngines':list(bs.KINDS),'versionCode':1,
              'performanceStatus':'CANDIDATE_NOT_MEASURED','releaseAccepted':True}
        with self.assertRaisesRegex(ValueError,'candidate'):
            multi.inspect(gate)

    def test_original_packet_requires_new_version_matching_wrapper_and_android_evidence(self):
        self.assertEqual(0,multi.original_packet_contract({'versionCode':8},{},pathlib.Path('.')))
        for gate,receipt in [({'versionCode':8,'originalPacketContract':1},{}),
                             ({'versionCode':8},{'originalPacketContract':1}),
                             ({'versionCode':7,'originalPacketContract':1},{'originalPacketContract':1}),
                             ({'versionCode':8,'originalPacketContract':True},{'originalPacketContract':1})]:
            with self.assertRaises(ValueError):multi.original_packet_contract(gate,receipt,pathlib.Path('.'))
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                multi.original_packet_contract({'versionCode':8,'originalPacketContract':1},
                                               {'originalPacketContract':1},pathlib.Path(temp))

    def test_new_layout_does_not_admit_standalone_wdtt(self):
        with self.assertRaisesRegex(ValueError,'WDTT is standalone'):
            multi.freeze({'wrapper':'unused','rust':'unused','wdtt':'unused'},'unused',1)

    def test_catalog_has_exactly_two_native_files_and_all_logical_ids(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            wrapper,transport=root/'libbs.so',root/'libcsqtt_transport.so'
            wrapper.write_bytes(b'wrapper-fixture');transport.write_bytes(b'transport-fixture')
            outputs={abi:{'libbs.so':wrapper,'libcsqtt_transport.so':transport} for abi in bs.ABIS}
            catalog=bs.catalog(outputs)
            self.assertEqual((4,'rust-multicall-v1'),(catalog['schema'],catalog['layout']))
            self.assertEqual(set(bs.KINDS),set(catalog['engines']))
            self.assertNotIn('wdtt',catalog['engines'])
            for abi in bs.ABIS:
                self.assertEqual({'libbs.so','libcsqtt_transport.so'},set(catalog['native'][abi]))

    def test_unknown_layout_rejected(self):
        with self.assertRaisesRegex(ValueError,'Unknown BS'):
            bs.require_layout('from-import')


if __name__=='__main__':unittest.main()
