"""Producer boundary checks; no Gradle/APK or provider traffic."""
import pathlib
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
import csqtt_multicall_build as producer
import csqtt_multicall_producer as gate
import prepare_csqtt_direct_tun as direct_tun


class ProducerTests(unittest.TestCase):
    def test_flow_proof_accepts_only_exact_root_or_known_generation_names(self):
        root="\n".join(f"test {name} ... ok" for _ in range(2) for name in producer.FLOW_CASES)
        self.assertTrue(producer.admission_flow_cases_passed(root))
        qualified="\r\n".join(f"test {scope}::{name} ... ok" for scope in ("g251_flow","g253_flow") for name in producer.FLOW_CASES)
        self.assertTrue(producer.admission_flow_cases_passed(qualified))
        self.assertTrue(producer.admission_flow_cases_passed(qualified.replace("_flow::","_flow::tests::")))
        for invalid in (qualified.replace("g253_flow","unrelated"),
                        qualified.replace("g253_flow","g251_flow"),
                        qualified.replace("test ","prefix test "),
                        qualified.replace(" ... ok"," ... ok suffix"),
                        qualified.replace(" ... ok"," ... ignored"),
                        qualified+"\n"+f"test g251_flow::{producer.FLOW_CASES[0]} ... ok",
                        "\n".join(root.splitlines()[:-1])):
            self.assertFalse(producer.admission_flow_cases_passed(invalid))

    def test_adapter_keeps_protocol_files_and_runtime_body(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            crate = root / "rust-client"
            crate.mkdir()
            manifest = '''[package]
name = "csqtt-client"
version = "2.0.0"
edition = "2024"
[[bin]]
name = "client"
path = "main.rs"
[dependencies]
primp = "1.3.1"
[profile.release]
panic = "unwind"
[patch.crates-io]
primp = { path = "vendor/primp" }
'''
            main = '#![allow(dead_code)]\nmod wire;\n#[tokio::main]\nasync fn main() { let _ = std::env::args(); }\n'
            (crate / "Cargo.toml").write_text(manifest)
            (crate / "main.rs").write_text(main)
            (crate / "wire.rs").write_bytes(b"historical-wire-unchanged\r\n")
            producer.adapt_crate(root, "g20")
            self.assertEqual((crate / "wire.rs").read_bytes(), b"historical-wire-unchanged\r\n")
            actual = (crate / "main.rs").read_text()
            self.assertTrue(actual.startswith(main.replace("std::env::args()", "mcrf_arguments()")))
            self.assertIn("pub fn mcrf_run() { main(); }", actual)
            cargo = (crate / "Cargo.toml").read_text()
            self.assertIn('[lib]\npath = "main.rs"', cargo)
            self.assertIn('name = "mcrf-adapter-g20"', cargo)
            self.assertNotIn("[[bin]]", cargo)

    def test_changed_boundary_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / "rust-client").mkdir()
            (root / "rust-client/Cargo.toml").write_text('[package]\nname = "different"')
            with self.assertRaisesRegex(ValueError, "Package anchor"):
                producer.adapt_crate(root, "g20")

    def test_manifest_escape_is_rejected(self):
        for path in ("../Cargo.toml", "C:/private", "/private", "foo\\bar"):
            with self.assertRaises(ValueError):
                producer.checked_file(ROOT, path)

    def test_existing_stage_is_rejected_before_any_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, "Fresh external"):
                producer.prepare(temp)

    def test_baseline_compiled_proof_selects_host_cli_with_android_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / "receipt.json"
            path.write_text(json.dumps({"state": "COMPILED_MULTICALL_PROOF_NOT_PACKAGED",
                "sourceStable": True, "packageReady": False,
                "producerSha256": producer.sha(producer.__file__),
                "inputs": dict.fromkeys(producer.KEYS), "artifacts": []}))
            (path.parent / "Cargo.lock").write_text("version = 4\n")
            content = json.loads(path.read_text())
            content["workspaceLockSha256"] = producer.sha(path.parent / "Cargo.lock")
            from prepare_csqtt_gooby import source_inventory
            content["goobyBuiltinOverlays"] = source_inventory()
            # Satisfy the new source/test prefix so this negative fixture still
            # reaches the intended missing-host/arm64-artifacts rejection.
            content.update(directTunFdContract=1,directTunGenerations=["2.5.2","2.5.3"],
                directTunOverlays={name:producer.sha(producer.HERE/name) for name in direct_tun.DIRECT_OVERLAYS})
            boundary=path.parent/"direct-tun-boundary-tests.log"
            boundary.write_text("synthetic parser fixture; no native execution\n")
            content["directTunBoundaryTests"]={"passed":3,"providerTraffic":False,"logSha256":producer.sha(boundary)}
            names=("prepare_csqtt_vk_backoff.py","mcrf_vk_backoff.rs","prepare_csqtt_flow_harness.py",
                   "vk_backoff_harness/Cargo.toml","vk_backoff_harness/lib.rs","vk_backoff_harness/auth.rs.in",
                   "vk_backoff_harness/worker.rs.in","vk_backoff_harness/bootstrap.rs.in","vk_backoff_harness/flow.rs.in")
            content["vkBackoffOverlays"]={name:producer.sha(producer.HERE/name) for name in names}
            admission=path.parent/"admission-tests"
            admission.mkdir()
            log=path.parent/"admission-tests.log"
            log.write_text("synthetic parser fixture; no native execution\n")
            (admission/"extracted-flow-source.json").write_text("{}\n")
            (admission/"Cargo.lock").write_text("version = 4\n")
            content["vkBackoffAdmission"]={"passed":22,"flowCases":6,"providerTraffic":False,
                "logSha256":producer.sha(log),"extractionSha256":producer.sha(admission/"extracted-flow-source.json"),
                "lockSha256":producer.sha(admission/"Cargo.lock")}
            path.write_text(json.dumps(content))
            with self.assertRaisesRegex(ValueError, "CLI execution and arm64"):
                gate.verify_compiled_proof({"path": str(path), "sha256": producer.sha(path)})
            for name in ("lib.rs", "main.rs", "Cargo.toml"):
                (path.parent / name).write_bytes((gate.SOURCE / name).read_bytes())
            content["inputs"] = {key: {"path": temp, "inventory": [], "overlays": {}}
                                 for key in producer.KEYS}
            content["generated"] = []
            for target, abi, filename in (
                ("host", "host", "mcrf-csqtt-transport.exe"),
                ("arm64", "arm64-v8a", "arm64-v8a/libcsqtt_transport.so"),
                ("x86_64", "x86_64", "x86_64/libcsqtt_transport.so"),
            ):
                binary = path.parent / "artifacts" / filename
                binary.parent.mkdir(parents=True, exist_ok=True)
                binary.write_bytes(target.encode() + b" MCRF_DIRECT_TUN_FD_V1")
                content["artifacts"].append({"target": target, "abi": abi, "path": str(binary),
                                             "sha256": producer.sha(binary), "bytes": binary.stat().st_size})
            cli = path.parent / "host-proof.json"
            cli.write_text(json.dumps({"state": "CLI_RUNTIME_PARALLEL_PASS", "providerTraffic": False,
                                       "binarySha256": content["artifacts"][0]["sha256"], "cases": list(range(27)),
                                       "directTunFdContract": 1, "directTunCapability": {
                                           "marker": "MCRF_DIRECT_TUN_FD_V1", "version": 1,
                                           "generations": ["2.5.2", "2.5.3"], "fdAdoptionRequired": True}}))
            content["hostProofSha256"] = producer.sha(cli)
            path.write_text(json.dumps(content))
            with patch("bs_engine_inputs.elf"):
                checked = gate.verify_compiled_proof({"path": str(path), "sha256": producer.sha(path)})
            self.assertEqual(content, checked)


if __name__ == "__main__":
    unittest.main()
