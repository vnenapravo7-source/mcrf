import copy
import hashlib
import pathlib
import struct
import tempfile
import unittest
from unittest import mock

import versioned_inputs as v


class VersionedInputsTests(unittest.TestCase):
    def test_exact_releases_have_three_abis_and_differ_from_251(self):
        lock = v.metadata()
        self.assertEqual(set(lock["versions"]), {"2.5.2", "2.5.3"})
        self.assertEqual(lock["versions"]["2.5.2"]["native"], lock["versions"]["2.5.3"]["native"])
        for pin in lock["versions"].values():
            self.assertEqual(set(pin["native"]), set(v.ABIS))
            self.assertEqual(len(pin["revision"]), 40)
            self.assertEqual(pin["serverTagRevision"], "9c4a59d523ae4ec42fb8205198678c8a7ffc6263")
            self.assertNotEqual(pin["native"]["arm64-v8a"]["sha256"], "9dad51554bafb2e6cf386a48cc278fb08c434a78ece40baff00fb51f203b237c")

    def test_elf_header_checks_machine_class_and_pie(self):
        for abi, (cls, machine) in v.ABIS.items():
            data = bytearray(64); data[:6] = b"\x7fELF"+bytes([cls,1]); struct.pack_into("<HH",data,16,3,machine)
            v.inspect_elf(data,abi)
            for other in set(v.ABIS)-{abi}:
                with self.assertRaises(ValueError): v.inspect_elf(data,other)
            struct.pack_into("<H",data,16,2)
            with self.assertRaises(ValueError): v.inspect_elf(data,abi)

    def test_bad_apk_rejected_before_signer_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            apk=pathlib.Path(temp)/"wrong.apk"; apk.write_bytes(b"2.5.1 bytes cannot masquerade as 2.5.3")
            with mock.patch.object(v.subprocess,"run") as run:
                with self.assertRaisesRegex(ValueError,"digest"): v.inspect_apk(apk,"2.5.3","unused")
                run.assert_not_called()

    def test_signer_is_independent_of_matching_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            apk=pathlib.Path(temp)/"fixture.apk"; apk.write_bytes(b"owned fixture")
            lock=copy.deepcopy(v.metadata())
            asset=next(a for a in lock["versions"]["2.5.2"]["assets"] if a["name"]=="CSQTT-2.5.2-universal.apk")
            asset.update(size=13,digest="sha256:"+hashlib.sha256(b"owned fixture").hexdigest())
            with mock.patch.object(v,"metadata",return_value=lock), mock.patch.object(v.subprocess,"run",return_value=mock.Mock(stdout="Signer #1 certificate SHA-256 digest: unknown\n")):
                with self.assertRaisesRegex(ValueError,"signer"): v.inspect_apk(apk,"2.5.2","unused")

    def test_missing_or_forged_receipt_never_admits_old_wrapper(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            with self.assertRaises(ValueError): v.verify(root)
            v.write_json(root/"receipt.json",dict(schema=1,state="COMPILED_NOT_EXECUTED"))
            with self.assertRaises((ValueError,KeyError)): v.verify(root)

    def test_source_pins_reject_dirty_checkout_before_output(self):
        with mock.patch.object(v.subprocess,"check_output",return_value=b" M rust-client/main.rs\n"):
            with self.assertRaisesRegex(ValueError,"Pristine"): v.verify_source(pathlib.Path("unused"))


if __name__ == "__main__": unittest.main()
