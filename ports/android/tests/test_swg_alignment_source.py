"""Real source-bound inputs and isolated tamper checks; no native execution."""
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
import bs_engine_inputs as bs
from csqtt25 import aligned_inputs as a, aligned_wrapper as w, versioned_inputs as v

NATIVE = pathlib.Path("D:/MCRF/Builds/Android/csqtt252-aligned-armv7-20261101-02")
VERSIONS = pathlib.Path("D:/MCRF/Builds/Android/csqtt-swg-aligned-inputs-20261101-01")
WRAPPER = pathlib.Path("D:/MCRF/Builds/Android/csqtt25-aligned-loader-20261101-01")


def copy_native(destination):
    for name in ("receipt.json", "source-proof.json", "source-manifest.json", "upstream-receipt.json", "release-source.zip"):
        shutil.copyfile(NATIVE / name, destination / name)
    shutil.copytree(NATIVE / "source", destination / "source")
    shutil.copytree(NATIVE / "armeabi-v7a", destination / "armeabi-v7a")


class SwgAlignmentTests(unittest.TestCase):
    def test_real_native_domains_and_three_abi_gate(self):
        proof, receipt = a.verify_native(NATIVE)
        self.assertEqual(proof["domainsGitBlob"], a.DOMAINS_BLOB)
        self.assertEqual([r["abi"] for r in receipt["artifacts"]], ["armeabi-v7a"])
        self.assertEqual(v.verify(VERSIONS, WRAPPER)["sourceBuiltABIs"], ["armeabi-v7a"])

    def test_missing_or_changed_domains_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            copy_native(root)
            domains = root / "source/domains.txt"
            domains.write_bytes(b"invented.example\n")
            with self.assertRaisesRegex(ValueError, "Frozen input changed"):
                a.verify_native(root)
            domains.unlink()
            with self.assertRaisesRegex(ValueError, "inventory"):
                a.verify_native(root)

    def test_relabelled_native_receipt_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            receipt = bs.load(NATIVE / "receipt.json")
            receipt["state"] = "COMPILED_NOT_EXECUTED"
            receipt["artifacts"][0]["sha256"] = "0" * 64
            (root / "receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "receipt changed"):
                a.verify_native(root)

    def test_old_four_kib_cannot_replace_aligned_transport(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp) / "versions"
            shutil.copytree(VERSIONS, root)
            proof = bs.load(VERSIONS / "receipt.json")
            source = pathlib.Path(proof["upstreamStage"]) / "armeabi-v7a" / v.LIBRARY
            shutil.copyfile(source, root / "armeabi-v7a" / v.LIBRARY)
            with self.assertRaisesRegex(ValueError, "Frozen input changed"):
                v.verify(root)

    def test_unrelated_loader_source_change_rejected_even_with_new_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp) / "wrapper"
            shutil.copytree(WRAPPER, root)
            target = root / "module/csqtt_bridge.go"
            target.write_bytes(target.read_bytes() + b"\n// unrelated change\n")
            rows = bs.load(root / "source-manifest.json")
            for row in rows:
                if row["path"] == "csqtt_bridge.go":
                    row["sha256"] = bs.sha(target)
            v.write_json(root / "source-manifest.json", rows)
            proof = bs.load(root / "loader-source-proof.json")
            proof["sourceManifestSha256"] = bs.sha(root / "source-manifest.json")
            v.write_json(root / "loader-source-proof.json", proof)
            with self.assertRaisesRegex(ValueError, "Unrelated loader source changed"):
                w.verify_source(root)

    def test_unchanged_abi_native_replacement_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp) / "wrapper"
            shutil.copytree(WRAPPER, root)
            (root / "x86_64/libcsqtt25.so").write_bytes(b"not the retained artifact")
            with self.assertRaisesRegex(ValueError, "native bytes changed"):
                w.verify(root, VERSIONS)


if __name__ == "__main__":
    unittest.main()
