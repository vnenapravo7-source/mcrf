import importlib.util
import pathlib
import unittest
import json
import re
import shutil
import uuid
import subprocess
from unittest import mock

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("negotiation", HERE / "prepare-negotiated-workers.py")
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)
PINNED = pathlib.Path("D:/MCRF/Projects/MCRF/work/android-upstream/csqtt-current-d02da9b/rust-client")


def server_blob():
    # Hash policy is over git's LF blob, never the autocrlf working copy.
    return subprocess.run(["git", "-C", str(PINNED.parent), "show",
                           "d02da9bd9d8926969b766e197c434d830a83dbd8:rust-server/tokio_io.rs"],
                          check=True, capture_output=True).stdout


def dispatcher_blob():
    base = subprocess.run(["git", "-C", str(PINNED.parent), "show",
                           "d02da9bd9d8926969b766e197c434d830a83dbd8:rust-client/dispatcher.rs"],
                          check=True, capture_output=True).stdout.decode().splitlines(True)
    patch = (PINNED.parent.parent / "csqtt-swg-2.5.1.patch").read_text().splitlines(True)
    out, pos, active = [], 0, False
    for line in patch:
        if line.startswith("diff --git"):
            active = "a/rust-client/dispatcher.rs " in line
        if not active:
            continue
        if line.startswith("@@"):
            start = int(re.match(r"@@ -(\d+)", line)[1]) - 1
            out.extend(base[pos:start])
            pos = start
        elif line.startswith(("---", "+++", "diff ", "index ")):
            continue
        elif line.startswith(" "):
            assert base[pos] == line[1:]
            out.append(base[pos])
            pos += 1
        elif line.startswith("-"):
            assert base[pos] == line[1:]
            pos += 1
        elif line.startswith("+"):
            out.append(line[1:])
    return "".join(out + base[pos:]).encode()


class WorkerPreparationTests(unittest.TestCase):
    def setUp(self):
        self.main = (PINNED / "main.rs").read_text(encoding="utf-8")
        self.worker = (PINNED / "worker.rs").read_text(encoding="utf-8")
        self.session = (PINNED / "session.rs").read_text(encoding="utf-8")

    def test_legacy_fallback_and_cap_updates_share_target_in_all_control_paths(self):
        main, worker = adapter.transform(self.main, self.worker)
        session = adapter.transform_session(self.session)
        self.assertIn("Arc::new(crate::session::McrfWorkerTarget::new(initial_workers))", main)
        self.assertIn("pub workers: Arc<crate::session::McrfWorkerTarget>,", worker)
        self.assertEqual(session.count("Arc<McrfWorkerTarget>"), 5)
        self.assertNotIn("Arc<AtomicUsize>", session)
        self.assertIn("apply_tunconf_workers(&value, scale.configured, scale.target, scale.notify);", session)
        self.assertIn("apply_tunconf_workers(&config, configured_workers, target_workers.as_ref(), scale_notify.as_ref());", session)
        # Both standalone TUNWORKERS paths continue through the same cap setter.
        self.assertEqual(session.count("apply_workers_limit(agreed,"), 2)
        self.assertIn("let prev = target.apply(Some(effective), effective);", session)
        helper = adapter.WORKER_TARGET
        self.assertLess(helper.index("self.server_limit.lock()"), helper.index("self.current.swap("))
        self.assertIn("if let Some(value) = agreed { *limit = Some(value.max(1)); }", helper)
        self.assertIn("let effective = (*limit).unwrap_or(configured.max(1));", helper)
        self.assertIn('part.starts_with("workers=")', helper)
        # Native regression cases are emitted, not executed by this Python suite.
        self.assertIn("for cap in [1, 20]", session)
        self.assertIn("legacy_config_scales_and_explicit_cap_survives_repeated_config", session)
        with self.assertRaises(ValueError):
            adapter.transform_session(session)
        with self.assertRaises(ValueError):
            adapter.transform_session(self.session.replace("target.swap(effective, Ordering::AcqRel)", "target.swap(50, Ordering::AcqRel)"))

    def test_pinned_source_and_requested_registration_preserved(self):
        main, worker = adapter.transform(self.main, self.worker)
        self.assertIn("let initial_workers = 1;", main)
        self.assertIn("configured_workers: workers,", main)
        self.assertIn("configured_workers: context.params.configured_workers,", worker)
        self.assertNotIn("&& id > context.params.configured_workers", worker)
        self.assertEqual(worker.count("tokio::time::sleep(Duration::from_millis(250))"), 3)
        self.assertIn("fn bootstrap_and_fifty_requested_twenty_agreed()", worker)
        self.assertIn("group_creds.release_allocation(&credential_lease).await;\n            return;\n        }\n        let config_guard", worker)

    def test_drift_and_double_application_fail_before_writes(self):
        with self.assertRaises(ValueError):
            adapter.transform(self.main.replace("let initial_workers = workers;", "let initial_workers = 50;"), self.worker)
        main, worker = adapter.transform(self.main, self.worker)
        with self.assertRaises(ValueError):
            adapter.transform(main, worker)

    def test_refuses_retained_or_upstream_input(self):
        with self.assertRaises(ValueError):
            adapter.prepare(PINNED.parent)

    def test_bootstrap_one_and_fifty_requested_twenty_agreed_static(self):
        main, worker = adapter.transform(self.main, self.worker)
        bootstrap = int(re.search(r"let initial_workers = (\d+);", main)[1])
        self.assertEqual(bootstrap, 1)
        # Evaluate the exact simple Rust retirement predicate after validating
        # every former configured-worker exemption was replaced, not a Rust run.
        guard = "(id > context.params.workers.load(Ordering::Acquire))"
        self.assertEqual(worker.count(guard), 5)
        self.assertNotIn("&& id > context.params.configured_workers", worker)
        self.assertEqual(re.search(r"pub const WORKERS_PER_GROUP: usize = (\d+);", worker)[1], "10")
        group_body = worker[worker.index("fn group_worker_ids("):worker.index("fn credential_stream_id(")]
        self.assertIn("let first = (group_id - 1) * WORKERS_PER_GROUP + 1;", group_body)
        self.assertIn("let last = (first + WORKERS_PER_GROUP).min(total_workers + 1);", group_body)
        slots = lambda n: [i for g in range(1, 6) for i in range((g-1)*10+1, min(g*10+1, n+1))]
        self.assertEqual(slots(bootstrap), [1])
        self.assertEqual(slots(20), list(range(1, 21)))
        self.assertEqual([i for i in range(1, 51) if not i > 20], slots(20))
        self.assertIn("configured_workers: workers,", main)  # GETCONF still requests 50.


class FrozenSourceTests(unittest.TestCase):
    def setUp(self):
        # Disposable source fixtures only: no toolchain, native or APK execution.
        self.stage = pathlib.Path("D:/MCRF/Builds/Android") / ("csqtt25-negotiated-" + uuid.uuid4().hex)
        self.root = self.stage / "source"
        (self.root / "rust-client").mkdir(parents=True)
        for name in ("main.rs", "worker.rs", "session.rs"):
            blob = subprocess.run(["git", "-C", str(PINNED.parent), "show",
                                   "d02da9bd9d8926969b766e197c434d830a83dbd8:rust-client/" + name],
                                  check=True, capture_output=True).stdout
            (self.root / "rust-client" / name).write_bytes(blob)
        (self.root / "rust-client/dispatcher.rs").write_bytes(dispatcher_blob())
        (self.root / "rust-server").mkdir()
        (self.root / adapter.SERVER_TUN_PATH).write_bytes(server_blob())
        self.addCleanup(shutil.rmtree, self.stage)

    def test_freeze_binds_both_changed_files_and_single_application(self):
        proof = adapter.prepare(self.root)
        self.assertEqual(proof["applications"], 1)
        self.assertEqual(proof["bootstrapWorkers"], 1)
        self.assertEqual(adapter.verify(self.root), proof)
        manifest = json.loads((self.stage / "source-manifest.json").read_text())
        self.assertEqual([r["path"] for r in manifest], sorted(["rust-client/dispatcher.rs"] + adapter.CHANGED_PATHS))
        self.assertEqual(proof["serverTunTxQueue"], adapter.server_tun_policy())
        for changed in proof["changedInputs"]:
            row = next(r for r in manifest if r["path"] == changed["path"])
            self.assertNotEqual(changed["beforeSha256"], changed["sha256"])
            self.assertEqual(changed["sha256"], row["sha256"])
        before = {p: p.read_bytes() for p in self.stage.rglob("*") if p.is_file()}
        with self.assertRaisesRegex(ValueError, "already frozen"):
            adapter.prepare(self.root)
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_anchor_drift_does_not_write_either_file_or_freeze(self):
        path = self.root / "rust-client/worker.rs"
        path.write_text("unknown source", encoding="utf-8")
        before = [(p, p.read_bytes()) for p in self.root.rglob("*.rs")]
        with self.assertRaises(ValueError):
            adapter.prepare(self.root)
        self.assertTrue(all(p.read_bytes() == data for p, data in before))
        self.assertFalse((self.stage / "source-manifest.json").exists())

    def test_session_drift_and_old_fallback_contract_refused(self):
        session = self.root / "rust-client/session.rs"
        original = session.read_bytes()
        session.write_bytes(original + b"\n// drift")
        before = {p: p.read_bytes() for p in self.root.rglob("*.rs")}
        with self.assertRaisesRegex(ValueError, "client input source drift"):
            adapter.prepare(self.root)
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        session.write_bytes(original)
        proof = adapter.prepare(self.root)
        proof.pop("legacyTunconfFallback")
        adapter.save_json(self.stage / "negotiation.json", proof)
        with self.assertRaisesRegex(ValueError, "fallback contract"):
            adapter.verify(self.root)

    def test_output_pin_failure_precedes_every_source_write(self):
        before = {p: p.read_bytes() for p in self.root.rglob("*.rs")}
        with mock.patch.object(adapter, "SESSION_INPUT_PIN", (adapter.SESSION_INPUT_PIN[0], "0" * 64)):
            with self.assertRaisesRegex(ValueError, "client output source drift"):
                adapter.prepare(self.root)
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        self.assertFalse((self.stage / "source-manifest.json").exists())

    def test_frozen_content_set_and_manifest_drift_refused(self):
        adapter.prepare(self.root)
        extra = self.root / "extra.rs"
        extra.write_text("untracked input", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source set"):
            adapter.verify(self.root)
        extra.unlink()
        path = self.root / "rust-client/main.rs"
        path.write_text(path.read_text(encoding="utf-8") + "\n// changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source set"):
            adapter.verify(self.root)
        (self.stage / "source-manifest.json").write_text("[]", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "manifest changed"):
            adapter.verify(self.root)

    def built_fixture(self):
        proof = adapter.prepare(self.root)
        artifacts = []
        for abi, elf_class, machine in (("arm64-v8a", 2, 183), ("armeabi-v7a", 1, 40), ("x86_64", 2, 62)):
            folder = self.stage / abi
            folder.mkdir()
            path = folder / "libcsqtt25_transport.so"
            header = bytearray(20)
            header[:6] = bytes((127, 69, 76, 70, elf_class, 1))
            header[16:18] = (3).to_bytes(2, "little")
            header[18:20] = machine.to_bytes(2, "little")
            path.write_bytes(header)  # inert ELF header fixture, never executed.
            artifacts.append({"abi": abi, "file": str(path), "bytes": 20, "sha256": adapter.digest(path)})
        receipt = {"state": "COMPILED_NOT_EXECUTED", "sourceStable": True, "source": str(self.root),
                   "revision": "d02da9bd9d8926969b766e197c434d830a83dbd8",
                   "swgPatchSha256": "b61ad9dd4610b3ed200e9f2fb69567492987a4fb2d89c70255f0770148b2ff77",
                   "workerNegotiationContract": 1, "serverTunTxQueueContract": 1,
                   "nativeExecuted": False, "sourceManifestSha256": proof["sourceManifestSha256"],
                   "negotiationSha256": adapter.digest(self.stage / "negotiation.json"),
                   "transformSha256": proof["transformSha256"], "changedInputs": proof["changedInputs"],
                   "preparerSha256": adapter.digest(HERE / "Prepare-NegotiatedSource.ps1"),
                   "producerSha256": adapter.digest(HERE / "Build-NegotiatedNative.ps1"), "artifacts": artifacts}
        adapter.save_json(self.stage / "receipt.json", receipt)
        return receipt

    def test_built_receipt_and_artifact_tamper_fail_closed(self):
        receipt = self.built_fixture()
        adapter.verify(self.root, built=True)
        receipt["changedInputs"] = []
        adapter.save_json(self.stage / "receipt.json", receipt)
        with self.assertRaisesRegex(ValueError, "receipt missing"):
            adapter.verify(self.root, built=True)

    def test_wrong_abi_refused_even_with_updated_digest(self):
        receipt = self.built_fixture()
        path = pathlib.Path(receipt["artifacts"][0]["file"])
        path.write_bytes(path.read_bytes()[:18] + (62).to_bytes(2, "little"))
        receipt["artifacts"][0]["sha256"] = adapter.digest(path)
        adapter.save_json(self.stage / "receipt.json", receipt)
        with self.assertRaisesRegex(ValueError, "ELF ABI mismatch"):
            adapter.verify(self.root, built=True)

    def test_artifact_content_missing_abi_and_executed_receipts_refused(self):
        receipt = self.built_fixture()
        path = pathlib.Path(receipt["artifacts"][0]["file"])
        before = path.read_bytes()
        path.write_bytes(before + b"tamper")
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            adapter.verify(self.root, built=True)
        path.write_bytes(before)
        all_artifacts = receipt["artifacts"]
        receipt["artifacts"] = all_artifacts[:2]
        adapter.save_json(self.stage / "receipt.json", receipt)
        with self.assertRaisesRegex(ValueError, "All negotiated"):
            adapter.verify(self.root, built=True)
        receipt["artifacts"] = all_artifacts
        receipt["nativeExecuted"] = True
        adapter.save_json(self.stage / "receipt.json", receipt)
        with self.assertRaisesRegex(ValueError, "receipt missing"):
            adapter.verify(self.root, built=True)

    def test_wrapper_pins_new_abis_and_preserves_process_command(self):
        receipt = self.built_fixture()
        wrapper_stage = self.stage.parent / ("p3-csqtt25-source-" + uuid.uuid4().hex)
        module = wrapper_stage / "module"
        target = module / "internal/csqtt25"
        target.mkdir(parents=True)
        self.addCleanup(shutil.rmtree, wrapper_stage)
        for name in ("runtime.go", "native_abi.go"):
            shutil.copyfile(HERE / name, target / name)
        before = (target / "runtime.go").read_text().split("// Construction only:")[1]
        adapter.pin_wrapper(module, self.root)
        after = (target / "runtime.go").read_text()
        self.assertEqual(before, after.split("// Construction only:")[1])
        self.assertIn(receipt["artifacts"][0]["sha256"], after)
        for row in receipt["artifacts"][1:]:
            self.assertIn(row["sha256"], (target / "native_abi.go").read_text())
        with self.assertRaises(ValueError):
            adapter.pin_wrapper(module, self.root)


class ProducerWiringTests(unittest.TestCase):
    def test_copy_transform_freeze_and_build_order(self):
        prepare = (HERE / "Prepare-NegotiatedSource.ps1").read_text()
        self.assertLess(prepare.index("git clone --no-hardlinks"), prepare.index('& python "$stage/prepare-negotiated-workers.py" $source'))
        self.assertEqual(prepare.count('& python "$stage/prepare-negotiated-workers.py" $source'), 1)
        self.assertLess(prepare.index('& python "$stage/prepare-negotiated-workers.py" $source'), prepare.index("$receipt.sourceManifestSha256="))
        build = (HERE / "Build-NegotiatedNative.ps1").read_text()
        self.assertLess(build.index("Assert-Source\n$rust="), build.index('cargo.exe" ndk'))
        self.assertIn('Push-Location "$source/rust-client"', build)
        self.assertIn("build --release --frozen", build)
        self.assertNotIn("prepare(self", build)
        self.assertIn("--verify-built $source", build)

    def test_no_prebuilt_transport_fallback_in_common_producer(self):
        build = (HERE / "Build-P3AbiNative.ps1").read_text()
        self.assertNotIn('"$AcceptedStage/$abi/libcsqtt25_transport.so"', build)
        self.assertIn('$transport="$AdditionalInputs/$abi/libcsqtt25_transport.so"', build)
        self.assertIn("$receipt.negotiatedSourceManifestSha256=$source.negotiatedSourceManifestSha256", build)
        prepare = (HERE / "Prepare-P3AbiSource.ps1").read_text()
        self.assertLess(prepare.index("--pin-wrapper"), prepare.index('$inputs=@('))
        graph = (HERE.parents[2] / "maintenance/New-P3P4CommonDefinition.ps1").read_text()
        self.assertIn("NegotiatedTransportStage=(Stage 'csqtt25-rust-native')", graph)
        self.assertIn("@{Stage=(Stage 'csqtt25-rust-source')}", graph)

    def test_server_queue_source_is_prepared_but_native_build_remains_client_only(self):
        prepare = (HERE / "Prepare-NegotiatedSource.ps1").read_text()
        build = (HERE / "Build-NegotiatedNative.ps1").read_text()
        self.assertIn("sparse-checkout set rust-client rust-server shared", prepare)
        self.assertIn("serverTunTxQueueContract=1", prepare)
        self.assertIn("$receipt.serverTunTxQueueContract -ne 1", build)
        self.assertIn('Push-Location "$source/rust-client"', build)
        self.assertNotIn('Push-Location "$source/rust-server"', build)


class ServerTunQueueTests(unittest.TestCase):
    def test_git_blob_exact_policy_and_double_application(self):
        before = server_blob()
        self.assertNotIn(b"\r\n", before)
        after = adapter.transform_server_tun(before)
        self.assertEqual(after, before.replace(adapter.SERVER_TUN_OLD, adapter.SERVER_TUN_NEW))
        self.assertEqual(adapter.hashlib.sha256(after).hexdigest(), adapter.SERVER_TUN_AFTER_SHA256)
        for drift in (after, before.replace(b"\n", b"\r\n"), before + b"\n// drift"):
            with self.assertRaises(ValueError):
                adapter.transform_server_tun(drift)

    def test_three_file_preparation_validates_server_before_any_write(self):
        fixture = FrozenSourceTests()
        fixture.setUp()
        try:
            server = fixture.root / adapter.SERVER_TUN_PATH
            server.write_bytes(server.read_bytes().replace(b"\n", b"\r\n"))
            before = {p: p.read_bytes() for p in fixture.root.rglob("*.rs")}
            with self.assertRaisesRegex(ValueError, "server TUN source drift"):
                adapter.prepare(fixture.root)
            self.assertEqual(before, {p: p.read_bytes() for p in before})
            self.assertFalse((fixture.stage / "source-manifest.json").exists())
        finally:
            fixture.doCleanups()


if __name__ == "__main__":
    unittest.main()
