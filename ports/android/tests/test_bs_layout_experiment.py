"""Layout and experiment rejection paths, without Gradle/APK or native builds."""
import argparse
import copy
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
sys.path.insert(0, str(ROOT / "maintenance"))
import bs_engine_inputs as bs
import csqtt_multicall_producer as multicall
import bs_rust_size_experiment as experiment
import freeze_bs_inputs as freezer
import evaluate_bs_rust_experiment as evaluator
import zlib


class LayoutTests(unittest.TestCase):
    def test_evaluator_rejects_invented_sizes_even_with_pinned_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            receipt, metrics = self.fixture()
            for name in ("baseline", "size"):
                binary = root / (name + ".bin")
                binary.write_bytes((name * 100).encode())
                row = receipt["profiles"][name]
                row.update(artifact=str(binary), artifactSha256=bs.sha(binary),
                           bytes=binary.stat().st_size,
                           compressedBytes=len(zlib.compress(binary.read_bytes(), 9)))
                metrics[name]["artifactSha256"] = row["artifactSha256"]
            receipt["profiles"]["size"]["compressedBytes"] = 1
            receipt_path, metrics_path = root / "receipt.json", root / "metrics.json"
            receipt_path.write_text(json.dumps(receipt))
            metrics_path.write_text(json.dumps(metrics))
            with self.assertRaisesRegex(ValueError, "native size mismatch"):
                evaluator.verify(receipt_path, bs.sha(receipt_path), metrics_path, bs.sha(metrics_path))

    def test_wdtt_cannot_be_in_new_layout_or_migration(self):
        self.assertNotIn("wdtt", bs.KINDS)
        with self.assertRaisesRegex(ValueError, "standalone"):
            bs.catalog({"arm64-v8a": {"libwdtt.so": None}})
        with self.assertRaisesRegex(ValueError, "standalone"):
            freezer.freeze({"wdtt": "unused"}, "unused", 2)
        manifest = (ROOT / "engines/EngineUpdateBsManifest.xml").read_text()
        self.assertNotIn(",wdtt", manifest)

    def test_native_candidate_does_not_promote_performance_acceptance(self):
        bs.require_layout("rust-multicall-v1")
        with tempfile.TemporaryDirectory() as temp:
            output = pathlib.Path(temp) / "port-plan"
            receipt = multicall.prepare(output)
            self.assertFalse(receipt["packageReady"])
            self.assertEqual([], receipt["artifacts"])
            self.assertFalse((output / "jni").exists())
            # An invented READY receipt cannot bypass the producer implementation.
            receipt["state"] = "READY"
            with self.assertRaisesRegex(ValueError, "ACCEPTANCE_REQUIRED"):
                multicall.require_ported()

    def fixture(self):
        receipt = {"state": "COMPILED_NOT_BENCHMARKED", "sourceStable": True, "abi": "arm64-v8a", "generation": "2.0",
                   "sourceManifestSha256": "c" * 64, "profiles": {
                       "baseline": {"artifactSha256": "a" * 64, "compressedBytes": 1000},
                       "size": {"artifactSha256": "b" * 64, "compressedBytes": 900}}}
        metrics = {k:receipt[k] for k in ("abi", "generation", "sourceManifestSha256")}
        for name in ("baseline", "size"):
            metrics[name] = {"artifactSha256": receipt["profiles"][name]["artifactSha256"],
                             "latencyMs": [100.] * 20, "coldStartMs": [100.] * 20,
                             "throughputMbps": [100.] * 3, "panicCount": 0,
                             "protocolCases": dict.fromkeys(experiment.CASES, True)}
        return receipt, metrics

    def test_thresholds_accept_boundaries_and_reject_each_regression(self):
        receipt, metrics = self.fixture()
        metrics["size"].update(throughputMbps=[97.] * 3, latencyMs=[105.] * 20, coldStartMs=[110.] * 20)
        self.assertEqual("ACCEPTED_EXPERIMENT", experiment.evaluate(receipt, metrics)["state"])
        for field, value in (("throughputMbps", [96.9] * 3), ("latencyMs", [105.1] * 20), ("coldStartMs", [110.1] * 20)):
            changed = copy.deepcopy(metrics)
            changed["size"][field] = value
            self.assertEqual("REJECTED_EXPERIMENT", experiment.evaluate(receipt, changed)["state"])
        receipt["profiles"]["size"]["compressedBytes"] = 901
        self.assertEqual("REJECTED_EXPERIMENT", experiment.evaluate(receipt, metrics)["state"])

    def test_measurements_reject_missing_tampered_nonfinite_protocol_panic(self):
        receipt, metrics = self.fixture()
        for key, value in (("artifactSha256", "0" * 64), ("panicCount", 1), ("protocolCases", {}),
                           ("latencyMs", [float("nan")] * 20), ("coldStartMs", [1]), ("throughputMbps", [0] * 3)):
            changed = copy.deepcopy(metrics)
            changed["size"][key] = value
            with self.assertRaises(ValueError):
                experiment.evaluate(receipt, changed)
        metrics["abi"] = "x86_64"
        with self.assertRaises(ValueError): experiment.evaluate(receipt, metrics)
        receipt["state"] = "PREPARED_NOT_COMPILED"
        with self.assertRaises(ValueError): experiment.evaluate(receipt, metrics)

    def test_experiment_copies_pinned_sources_never_changes_frozen(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "frozen"
            crate = source / "rust-client"
            crate.mkdir(parents=True)
            (crate / "Cargo.toml").write_text('[package]\nname="fixture"\nversion="0.0.0"\n[profile.release]\nopt-level=3\nlto=true\ncodegen-units=1\npanic="unwind"\n')
            (crate / "Cargo.lock").write_text("# pinned lock\n")
            rows = experiment.source_inventory(source)
            manifest = root / "source-manifest.json"
            manifest.write_text(json.dumps(rows))
            args = argparse.Namespace(source=str(source), manifest=str(manifest), manifest_sha256=bs.sha(manifest),
                crate="rust-client", output=str(root / "experiment"), generation="2.0", abi="arm64-v8a", execute=False)
            receipt = experiment.run(args)
            self.assertEqual("PREPARED_NOT_COMPILED", receipt["state"])
            self.assertTrue(receipt["sourceStable"])
            self.assertEqual(rows, experiment.source_inventory(source))
            for name in ("baseline", "size"):
                self.assertEqual(rows, experiment.source_inventory(root / "experiment" / name / "source"))
            args.output = str(root / "frozen" / "nested")
            with self.assertRaisesRegex(ValueError, "outside frozen"):
                experiment.run(args)
            (crate / "Cargo.lock").write_text("changed")
            with self.assertRaisesRegex(ValueError, "Frozen input changed"):
                experiment.snapshot(source, manifest, args.manifest_sha256)


if __name__ == "__main__": unittest.main()
