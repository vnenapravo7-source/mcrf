"""Negative scope and tamper regressions; these are not runtime acceptance."""
import json,pathlib,tempfile,unittest
from unittest.mock import patch
import native_routing_evidence as evidence
import native_routing_cli_evidence as cli

class EvidenceTests(unittest.TestCase):
 def test_fixture_newlines_do_not_allow_other_source_or_asset_changes(self):
  self.assertTrue(evidence.source_equal('main.rs',b'fn main(){}\r\n',b'fn main(){}\n'))
  self.assertFalse(evidence.source_equal('main.rs',b'fn main(){x()}\r\n',b'fn main(){}\n'))
  self.assertFalse(evidence.source_equal('routes.srs',b'x\r\n',b'x\n'))
 def test_physical_devices_are_rejected_before_adb(self):
  with patch.object(cli.subprocess,'check_output') as called:
   for serial in ['192.168.7.217:5555','R5CX73QYN3D','emulator-5556;echo x','']:
    with self.assertRaises(ValueError):cli.scope('unused',serial)
   called.assert_not_called()
 def test_emulator_metadata_must_confirm_qemu_and_abi(self):
  for values in [[b'0',b'x86_64'],[b'1',b'arm64-v8a']]:
   with patch.object(cli.subprocess,'check_output',side_effect=values):
    with self.assertRaises(ValueError):cli.scope('unused','emulator-5556')
 def test_explicit_physical_fixture_checks_uid_sdk_and_abi(self):
  good=[b'device',b'2000',b'29',b'',b'arm64-v8a']
  with patch.object(cli.subprocess,'check_output',side_effect=good):
   self.assertEqual(cli.scope('unused','R5CX73QYN3D',physical=True),
                    {'kind':'physical','abi':'arm64-v8a','sdk':29,'shellUid':2000})
  for index,value in [(0,b'unauthorized'),(1,b'0'),(2,b'28'),(3,b'1'),(4,b'x86_64')]:
   bad=good[:];bad[index]=value
   with patch.object(cli.subprocess,'check_output',side_effect=bad):
    with self.assertRaises(ValueError):cli.scope('unused','R5CX73QYN3D',physical=True)
 def test_typed_physical_proof_rejects_cross_abi_or_cleanup_pending(self):
  row=dict(fixture={'kind':'physical','abi':'arm64-v8a','sdk':29,'shellUid':2000},
           serial='R5CX73QYN3D',cleanupComplete=True,providerTraffic=False,
           executedBinaryPath='/data/local/tmp/mcrf-native-cli-'+'a'*32,
           producerSha256=cli.sha(cli.__file__),adbSha256='b'*64)
  self.assertEqual(cli.verify_fixture(row),'arm64')
  for key,value in [('cleanupComplete',False),('serial','emulator-5556'),
                    ('fixture',{'kind':'physical','abi':'x86_64','sdk':29,'shellUid':2000})]:
   with self.assertRaises(ValueError):cli.verify_fixture(dict(row,**{key:value}))
 def test_unknown_or_incomplete_envelope_cannot_admit(self):
  with tempfile.TemporaryDirectory(prefix='mcrf-routing-negative-') as directory:
   root=pathlib.Path(directory);self.assertEqual(root.resolve().parent,pathlib.Path(tempfile.gettempdir()).resolve());path=root/'proof.json'
   for value in [{},{'schema':1,'kernels':{},'services':{},'unitTests':{}},{'schema':True,'kernels':{},'services':{},'unitTests':{}},{'schema':1,'kernels':{},'services':{},'unitTests':{},'skip':True}]:
    path.write_text(json.dumps(value),encoding='utf-8')
    with self.assertRaises(ValueError):evidence.verify(path,root)
 def test_external_file_even_with_correct_digest_is_not_evidence(self):
  with tempfile.TemporaryDirectory(prefix='mcrf-routing-negative-') as directory:
   root=pathlib.Path(directory);self.assertEqual(root.resolve().parent,pathlib.Path(tempfile.gettempdir()).resolve());path=root/'proof.json';path.write_text('{}')
   with self.assertRaises(ValueError):evidence.frozen({'path':str(path),'sha256':evidence.sha(path)})
 def test_tampered_digest_is_rejected(self):
  with tempfile.TemporaryDirectory(prefix='mcrf-routing-negative-') as directory:
   root=pathlib.Path(directory);self.assertEqual(root.resolve().parent,pathlib.Path(tempfile.gettempdir()).resolve());path=root/'proof.json';path.write_text('{}');row={'path':str(path),'sha256':evidence.sha(path)};path.write_text('{"changed":true}')
   with self.assertRaises(ValueError):evidence.frozen(row)

if __name__=='__main__':unittest.main()
