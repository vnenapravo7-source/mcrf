import unittest
from unittest.mock import patch
import native_routing_cli_evidence as evidence

class PhysicalFixtureTests(unittest.TestCase):
    def test_private_endpoint_does_not_accept_arbitrary_network_destinations(self):
        self.assertTrue(evidence.private_adb_endpoint("192.168.100.234:41437"))
        for value in ("8.8.8.8:5555","127.0.0.1:5555","0.0.0.0:5555","192.168.1.2:65536","-s","192.168.1.2:0"):
            self.assertFalse(evidence.private_adb_endpoint(value))
    def scope(self,overrides=None):
        values={"ro.build.version.sdk":"36","ro.kernel.qemu":"0","ro.product.cpu.abi":"arm64-v8a","ro.serialno":"R5CX73QYN3D"}
        values.update(overrides or {})
        def output(adb,serial,*args):
            if args==("get-state",):return "device"
            if args==("shell","id","-u"):return "2000"
            return values[args[-1]]
        with patch.object(evidence,"output",side_effect=output):
            return evidence.scope("adb","192.168.100.234:41437",physical=True)
    def test_network_fixture_records_real_physical_identity(self):
        self.assertEqual("R5CX73QYN3D",self.scope()["deviceSerial"])
    def test_network_fixture_still_rejects_emulator_or_missing_identity(self):
        for invalid in ({"ro.kernel.qemu":"1"},{"ro.serialno":""},{"ro.serialno":"emulator-5554"}):
            with self.assertRaises(ValueError):self.scope(invalid)

if __name__=="__main__":unittest.main()
