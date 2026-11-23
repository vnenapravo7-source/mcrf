"""Static provenance/producer tests; fake ELF headers are never executed."""
import pathlib
import base64
import json
import shutil
import subprocess
import tempfile
import unittest
import uuid
from unittest import mock

import p3_abi_provenance as provenance
import test_prepare_negotiated_workers as fixtures

HERE = fixtures.HERE
adapter = fixtures.adapter


class BaselineTests(unittest.TestCase):
    def test_producer_stats_transform_matches_verified_bytes(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            self.skipTest("PowerShell unavailable for producer source transform")
        producer = (HERE / "Prepare-P3AbiSource.ps1").read_text(encoding="utf-8")
        fragment = producer.split("# Replacing the adapter", 1)[1].split('[IO.File]::WriteAllText', 1)[0]
        fragment = "# Replacing the adapter" + fragment
        command = "$taskCS25Root='" + str(HERE).replace("'", "''") + "'\n"
        command += fragment.replace("$PSScriptRoot", "$taskCS25Root")
        command += "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($bridge))"
        result = subprocess.run([shell, "-NoProfile", "-NonInteractive", "-EncodedCommand",
                                 base64.b64encode(command.encode("utf-16le")).decode("ascii")],
                                capture_output=True, text=True, check=True, timeout=30)
        self.assertEqual(base64.b64decode(result.stdout.strip()), provenance.sampled_bridge())

    def test_retained_streams_remain_bound_to_historical_bytes(self):
        rows = provenance.inventory(provenance.ACCEPTED_SOURCE / "module",
                                    provenance.ACCEPTED_SOURCE / "SOURCE_MANIFEST.json")
        for name, expected in provenance.RETAINED_STREAMS.items():
            self.assertEqual(rows[name], expected)
        with mock.patch.dict(provenance.RETAINED_STREAMS, {"mcrf_streams.go": "0" * 64}):
            with self.assertRaisesRegex(ValueError, "performance overlay"):
                provenance.baseline()

    def test_surviving_baseline_closes_without_historical_snapshots(self):
        base = provenance.baseline()
        self.assertEqual(pathlib.Path(base["module"]), provenance.ACCEPTED_SOURCE / "module")
        self.assertTrue(base["performanceAlreadyApplied"])
        # Every opened path must exist now. Historical baselineReceipt values are
        # deliberately inert and cannot be a hidden dependency of validation.
        original = provenance.regular
        opened = []
        def record(path):
            opened.append(str(path))
            return original(path)
        with mock.patch.object(provenance, "regular", side_effect=record):
            provenance.baseline()
        self.assertFalse(any("next-independent-" in p or "p2-release06-" in p for p in opened))

    def test_unapproved_baseline_and_receipt_hash_refused(self):
        with self.assertRaisesRegex(ValueError, "Exact surviving"):
            provenance.baseline(HERE)
        with mock.patch.object(provenance, "ACCEPTED_RECEIPT_SHA256", "0" * 64):
            with self.assertRaisesRegex(ValueError, "receipt changed"):
                provenance.baseline()

    def test_manifest_rejects_extra_missing_duplicate_traversal_and_changed_files(self):
        with tempfile.TemporaryDirectory(dir="D:/MCRF/Builds/Android", prefix="p3-static-") as folder:
            stage = pathlib.Path(folder)
            module = stage / "module"
            module.mkdir()
            file = module / "main.go"
            file.write_bytes(b"package main\n")
            row = {"path": "main.go", "sha256": provenance.digest(file)}
            manifest = stage / "manifest.json"
            adapter.save_json(manifest, [row])
            provenance.inventory(module, manifest)
            for rows in ([row, row], [{**row, "path": "../main.go"}], [{**row, "path": "C:main.go"}], []):
                adapter.save_json(manifest, rows)
                with self.assertRaises(ValueError):
                    provenance.inventory(module, manifest)
            adapter.save_json(manifest, [row])
            file.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "hash changed"):
                provenance.inventory(module, manifest)


class PreparedTests(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.FrozenSourceTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.transport = fixture.stage
        self.built = fixture.built_fixture()
        self.stage = provenance.ACCEPTED_STAGE.parent / ("p3-csqtt25-source-" + uuid.uuid4().hex)
        self.module = self.stage / "module"
        shutil.copytree(provenance.ACCEPTED_SOURCE / "module", self.module)
        self.addCleanup(shutil.rmtree, self.stage)
        for name in provenance.OVERLAYS:
            shutil.copyfile(HERE / name, self.module / "internal/csqtt25" / name)
        (self.module / "mcrf_csqtt25.go").write_bytes(provenance.sampled_bridge())
        adapter.pin_wrapper(self.module, fixture.root)
        self.proof = {
            "state": "P3_ABI_SOURCE_PREPARED_NOT_COMPILED", "sourceStable": True,
            "compiled": False, "executed": False,
            "module": str(self.module), "ownerCommandUnchanged": True,
            "baselineReceipt": str(provenance.ACCEPTED_STAGE / "receipt.json"),
            "baselineReceiptSha256": provenance.ACCEPTED_RECEIPT_SHA256,
            "baselinePreparedReceipt": str(provenance.ACCEPTED_SOURCE / "receipt.json"),
            "baselinePreparedSha256": provenance.ACCEPTED_SOURCE_RECEIPT_SHA256,
            "baselineSourceManifestSha256": provenance.digest(provenance.ACCEPTED_SOURCE / "SOURCE_MANIFEST.json"),
            "performanceAlreadyApplied": True,
            "performanceSourceSha256": provenance.digest(HERE.parent / "performance/Apply-PerformanceSource.ps1"),
            "provenanceVerifierSha256": provenance.digest(HERE / "p3_abi_provenance.py"),
            "overlays": [{"name": n, "sha256": provenance.digest(HERE / n)} for n in provenance.OVERLAYS],
            "workerNegotiationContract": 1, "serverTunTxQueueContract": 1,
            "negotiatedTransportStage": str(self.transport),
            "negotiatedTransportReceiptSha256": provenance.digest(self.transport / "receipt.json"),
            "negotiatedSourceManifestSha256": provenance.digest(self.transport / "source-manifest.json"),
        }
        self.freeze()

    def freeze(self):
        rows = [{"path": p.relative_to(self.module).as_posix(), "sha256": provenance.digest(p)}
                for p in sorted(self.module.rglob("*")) if p.is_file()]
        adapter.save_json(self.stage / "SOURCE_MANIFEST.json", rows)
        self.proof["manifestSha256"] = provenance.digest(self.stage / "SOURCE_MANIFEST.json")
        adapter.save_json(self.stage / "receipt.json", self.proof)

    def test_full_rebase_closure_preserves_performance_and_pins_all_abis(self):
        provenance.prepared(self.stage)
        adapter.verify_wrapper(self.module, self.transport / "source")
        self.assertEqual(provenance.digest(self.module / "csqtt_bridge.go"),
                         provenance.digest(provenance.ACCEPTED_SOURCE / "module/csqtt_bridge.go"))

    def test_bridge_replacement_cannot_drop_sampled_stats_even_after_refreeze(self):
        bridge = self.module / "mcrf_csqtt25.go"
        self.assertEqual(bridge.read_bytes().count(b"McrfSampleStreamStats: true,"), 1)
        shutil.copyfile(HERE.parent / "mcrf_csqtt25.go", bridge)
        self.freeze()
        with self.assertRaisesRegex(ValueError, "sampled STATS ingress"):
            provenance.prepared(self.stage)

    def test_owner_and_performance_tamper_refused_even_after_refreeze(self):
        for name in ("mcrf_process_group.go", "csqtt_bridge.go", "mcrf_streams.go"):
            path = self.module / name
            before = path.read_bytes()
            path.write_bytes(before + b"\n// drift")
            self.freeze()
            with self.assertRaisesRegex(ValueError, "owner/hash-pool/performance"):
                provenance.prepared(self.stage)
            path.write_bytes(before)

    def test_loader_and_pin_tamper_refused_even_after_refreeze(self):
        path = self.module / "internal/csqtt25/native_abi.go"
        path.write_text(path.read_text().replace(self.built["artifacts"][1]["sha256"], "0" * 64), encoding="utf-8")
        self.freeze()
        with self.assertRaisesRegex(ValueError, "exact negotiated"):
            provenance.prepared(self.stage)

    def test_baseline_substitution_and_repinning_a_freeze_refused(self):
        self.proof["baselineReceiptSha256"] = "0" * 64
        self.freeze()
        with self.assertRaisesRegex(ValueError, "provenance changed"):
            provenance.prepared(self.stage)
        with self.assertRaisesRegex(ValueError, "already frozen"):
            adapter.pin_wrapper(self.module, self.transport / "source")


class NativeTests(PreparedTests):
    """Inert artifacts and saved synthetic evidence only; no producer execution."""
    def setUp(self):
        super().setUp()
        self.native_stage = provenance.ACCEPTED_STAGE.parent / ("p3-csqtt25-native-" + uuid.uuid4().hex)
        self.native_stage.mkdir()
        self.addCleanup(shutil.rmtree, self.native_stage)
        baseline = provenance.load(provenance.ACCEPTED_STAGE / "receipt.json")
        self.native_proof = {
            "kind": "csqtt25", "state": "COMPILED_NOT_EXECUTED", "sourceStable": True,
            "nativeExecuted": False, "apkBuilt": False, "ownerCommandUnchanged": True,
            "workerNegotiationContract": 1, "serverTunTxQueueContract": 1,
            "processGroupContract": 1, "turnHashPoolContract": 1,
            "p3AbiAuthority": "human-20261023-finish-current-fixes-then-p3-p4",
            "preparedSource": str(self.stage), "preparedReceiptSha256": provenance.digest(self.stage / "receipt.json"),
            "source": str(self.module), "producerSha256": provenance.digest(HERE / "Build-P3AbiNative.ps1"),
            "sourceManifestSha256": self.proof["manifestSha256"], "performanceAlreadyApplied": True,
            "packageVersionCode": baseline["packageVersionCode"] + 1,
            "packageVersionName": f'2.5.1-swgpanel-abi.{baseline["packageVersionCode"] + 1}',
            "retainedOwnerReceiptSha256": provenance.ACCEPTED_RECEIPT_SHA256,
            **{k: self.proof[k] for k in ("negotiatedTransportStage", "negotiatedTransportReceiptSha256",
               "negotiatedSourceManifestSha256", "baselineSourceManifestSha256", "provenanceVerifierSha256")}}
        shutil.copyfile(self.stage / "SOURCE_MANIFEST.json", self.native_stage / "source-manifest.json")
        for key, folder in (("processGroupInputs", "process-group-inputs"), ("turnFeatureInputs", "turn-feature-inputs")):
            self.native_proof[key] = [dict(row) for row in baseline[key]]
            for row in self.native_proof[key]:
                if key == "turnFeatureInputs" and row["file"] == "internal/csqtt25/profile.go":
                    row["sha256"] = provenance.digest(HERE / "profile.go")
                path = self.native_stage / folder / row["file"]
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(self.module / row["file"], path)
        origin = {"authority": self.native_proof["p3AbiAuthority"], "newOwnerExecution": False, "newAbiRuntimeAccepted": False,
                  "baselineReceipt": self.proof["baselineReceipt"],
                  "baselineReceiptSha256": provenance.ACCEPTED_RECEIPT_SHA256,
                  "sourceManifestSha256": self.proof["manifestSha256"], "performanceAlreadyApplied": True}
        for name, key, path in (
                ("retained-native-runtime.go", "oldRuntimeSha256", provenance.ACCEPTED_SOURCE / "module/internal/csqtt25/runtime.go"),
                ("abi-runtime-input.go", "newRuntimeSha256", self.module / "internal/csqtt25/runtime.go"),
                ("retained-performance-bridge.go", "oldPerformanceBridgeSha256", self.module / "csqtt_bridge.go"),
                ("performance-bridge-input.go", "newPerformanceBridgeSha256", self.module / "csqtt_bridge.go"),
                ("retained-p3-owner-evidence.json", "retainedEvidenceSha256", provenance.ACCEPTED_STAGE / "retained-p3-owner-evidence.json")):
            shutil.copyfile(path, self.native_stage / name)
            origin[key] = provenance.digest(path)
        cases = ["Test25AdditionalAbi" + n for n in ("DescriptorsAreExactAndDistinct",
            "AndroidMustMatchWrapperArchitecture", "HeaderInspectionRejectsMalformedOrOtherGenerations",
            "RejectsCorrectHeaderWithWrongContent", "ActualArtifacts/arm64-v8a", "ActualArtifacts/armeabi-v7a",
            "ActualArtifacts/x86_64")]
        cases += ["TestMcrf" + n for n in ("RawMTUCeilingPreservesSmallerServerValue", "IPRelayBuffersOnOwnedSocket",
                                         "SOCKSAdmissionQueuesAndResumes", "RawMTUOnActualBulkUpload")]
        (self.native_stage / "abi-host-tests.jsonl").write_text("".join(
            json.dumps({"Action": "pass", "Test": name}) + "\n" for name in cases))
        shutil.copyfile(self.transport / "receipt.json", self.native_stage / "abi-input-receipt.json")
        self.host = {"state": "PASS_P3_CS25_ABI_HOST", "failures": 0, "skipped": 0, "cases": cases,
                     "sourceManifestSha256": self.proof["manifestSha256"],
                     "inputReceiptSha256": provenance.digest(self.transport / "receipt.json"),
                     "logSha256": provenance.digest(self.native_stage / "abi-host-tests.jsonl")}
        adapter.save_json(self.native_stage / "new-abi-host-evidence.json", self.host)
        origin["hostEvidenceSha256"] = self.native_proof["hostEvidenceSha256"] = provenance.digest(self.native_stage / "new-abi-host-evidence.json")
        adapter.save_json(self.native_stage / "p3-abi-origin.json", origin)
        self.native_proof["p3AbiOriginSha256"] = provenance.digest(self.native_stage / "p3-abi-origin.json")
        self.native_proof["artifacts"] = []
        for row in self.built["artifacts"]:
            folder = self.native_stage / row["abi"]
            folder.mkdir()
            for name in ("libcsqtt25.so", "libcsqtt25_transport.so"):
                path = folder / name
                shutil.copyfile(row["file"], path)
                self.native_proof["artifacts"].append({"abi": row["abi"], "file": str(path),
                    "sha256": provenance.digest(path), "bytes": path.stat().st_size, "executed": False})
        self.save_native()

    def save_native(self):
        adapter.save_json(self.native_stage / "receipt.json", self.native_proof)

    def test_native_receipt_binds_all_three_new_pins(self):
        self.assertEqual(provenance.native(self.native_stage)["transportPins"],
                         {r["abi"]: r["sha256"] for r in self.built["artifacts"]})

    def test_missing_contracts_or_source_binding_refused(self):
        for key in ("workerNegotiationContract", "serverTunTxQueueContract", "preparedReceiptSha256", "producerSha256"):
            old = self.native_proof[key]
            self.native_proof[key] = 0
            self.save_native()
            with self.assertRaises(ValueError):
                provenance.native(self.native_stage)
            self.native_proof[key] = old

    def test_rehashed_old_generation_transport_and_incomplete_set_refused(self):
        row = self.native_proof["artifacts"][1]
        path = pathlib.Path(row["file"])
        path.write_bytes(path.read_bytes() + b"historical transport")
        row.update(sha256=provenance.digest(path), bytes=path.stat().st_size)
        self.save_native()
        with self.assertRaisesRegex(ValueError, "generation changed"):
            provenance.native(self.native_stage)
        self.native_proof["artifacts"] = []
        self.save_native()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            provenance.native(self.native_stage)

    def test_host_reuse_and_artifact_redirect_refused(self):
        self.host["sourceManifestSha256"] = "0" * 64
        adapter.save_json(self.native_stage / "new-abi-host-evidence.json", self.host)
        with self.assertRaisesRegex(ValueError, "host/input evidence"):
            provenance.native(self.native_stage)
        self.native_proof["artifacts"][0]["file"] = self.built["artifacts"][0]["file"]
        self.save_native()
        # Restore host before exercising the independent path gate.
        self.host["sourceManifestSha256"] = self.proof["manifestSha256"]
        adapter.save_json(self.native_stage / "new-abi-host-evidence.json", self.host)
        with self.assertRaisesRegex(ValueError, "set/path"):
            provenance.native(self.native_stage)

    def test_rehashed_host_log_with_missing_pass_or_failure_refused(self):
        log = self.native_stage / "abi-host-tests.jsonl"
        for content in ("", json.dumps({"Action": "fail", "Test": self.host["cases"][0]})):
            log.write_text(content)
            self.host["logSha256"] = provenance.digest(log)
            adapter.save_json(self.native_stage / "new-abi-host-evidence.json", self.host)
            self.native_proof["hostEvidenceSha256"] = provenance.digest(self.native_stage / "new-abi-host-evidence.json")
            origin = provenance.load(self.native_stage / "p3-abi-origin.json")
            origin["hostEvidenceSha256"] = self.native_proof["hostEvidenceSha256"]
            adapter.save_json(self.native_stage / "p3-abi-origin.json", origin)
            self.native_proof["p3AbiOriginSha256"] = provenance.digest(self.native_stage / "p3-abi-origin.json")
            self.save_native()
            with self.assertRaisesRegex(ValueError, "log does not prove"):
                provenance.native(self.native_stage)


class ProducerStaticTests(unittest.TestCase):
    def test_defaults_and_verifier_wiring(self):
        for name in ("Prepare-P3AbiSource.ps1", "Build-P3AbiNative.ps1", "Invoke-P3HostChecks.ps1"):
            text = (HERE / name).read_text(encoding="utf-8")
            self.assertIn(provenance.ACCEPTED_STAGE.as_posix(), text)
            self.assertIn("p3_abi_provenance.py", text)
            self.assertNotIn("next-independent-", text)
            self.assertNotIn("/qwdtt/qwdtt", text)
            self.assertNotIn("prepared-expansion-source.json", text)
        text = (HERE / "Prepare-P3AbiSource.ps1").read_text()
        self.assertLess(text.index("--verify-built"), text.index("New-Item -ItemType Directory -Path $target"))
        self.assertNotIn('& "$PSScriptRoot/../performance/Apply-PerformanceSource.ps1"', text)
        self.assertIn(".Replace([char]92,[char]47)", text)
        build = (HERE / "Build-P3AbiNative.ps1").read_text()
        self.assertIn('"$AcceptedStage/retained-p3-owner-evidence.json"', build)
        self.assertIn("$origin.performanceAlreadyApplied=$true", build)

    def test_gradle_release_and_contract_propagation(self):
        for name in ("Prepare-P3AbiSource.ps1", "Build-P3AbiNative.ps1"):
            self.assertIn("$receipt.serverTunTxQueueContract=1", (HERE / name).read_text())
        for name in ("Build-P3AbiNative.ps1", "Invoke-P3HostChecks.ps1"):
            self.assertIn(".serverTunTxQueueContract -ne 1", (HERE / name).read_text())
        android = HERE.parents[2]
        release = (android / "maintenance/Build-Release08.ps1").read_text()
        self.assertIn('[Parameter(Mandatory)][string]$Csqtt25Stage', release)
        self.assertLess(release.index('--native $nativeStages.csqtt25'), release.index('$stage="D:/MCRF/Builds/Android/release08-'))
        gradle = (android / "engines/build.gradle.kts").read_text()
        self.assertIn('"--native",root.absolutePath', gradle)
        self.assertIn('require(csqtt25Gate)', gradle)
        self.assertIn('it.keys==csqtt25LegacyNativePins.keys', gradle)
        self.assertIn('if(descriptor["performanceAlreadyApplied"]==true)', gradle)
        self.assertIn('origin["performanceAlreadyApplied"]==true&&oldBridge.readText()==newBridge.readText()', gradle)

    def test_installer_same_version_guards_and_identical_bytes_branch(self):
        text = (HERE.parents[2] / "maintenance/Install-Release08.ps1").read_text()
        self.assertIn('$old.versionCode -gt $asset.versionCode', text)
        self.assertNotIn('$old.versionCode -ge $asset.versionCode', text)
        self.assertIn("$Matches[1] -cne $asset.package", text)
        self.assertIn("$certs[0].Groups[1].Value -cne $build.signer", text)
        self.assertIn("$current.firstInstallTime -cne $asset.firstInstallTimeBefore", text)
        self.assertIn("-cne $asset.sha256Before", text)
        self.assertIn("if((Hash08 $oldApk) -ceq $asset.sha256){$asset['alreadyInstalled']=$true}", text)
        self.assertLess(text.index("if($asset.ContainsKey('alreadyInstalled')"), text.index("$out=Adb08 @('install'"))
        self.assertIn("$now.firstInstallTime -cne $beforeTime", text)
        self.assertIn("$result.protectedHashes=Guard08", text)
        self.assertLess(text.index("$asset.Remove($key)"), text.index('Apk08 "$root/assets/'))
        self.assertNotIn("'uninstall'", text)
        self.assertNotIn("'clear'", text)

    def test_release_source_zip_preserves_paths_with_duplicate_basenames(self):
        # Execute only the extracted archive block on disposable text fixtures.
        text = (HERE.parents[2] / "maintenance/Build-Release08.ps1").read_text()
        block = text[text.index('$archive="$stage/mcrf-android-2.0-source.zip"'):text.index('$receipt=[ordered]@{state=\'BUILT_NOT_INSTALLED_OR_PUBLISHED\'')]
        with tempfile.TemporaryDirectory(dir="D:/MCRF/Builds/Android", prefix="p3-zip-static-") as folder:
            stage = pathlib.Path(folder)
            root = stage / "source"
            for name in ("README.md", "ports/android/app/build.gradle.kts", "ports/android/engines/build.gradle.kts"):
                path = root / name
                path.parent.mkdir(parents=True)
                path.write_text(name)
            command = ("$ErrorActionPreference='Stop'; $project='" + root.as_posix() + "'; $root=$project; $stage='" + stage.as_posix()
                       + "'; $sourceFiles=Get-ChildItem -LiteralPath $root -Recurse -File; " + block
                       + "; $zip=[IO.Compression.ZipFile]::OpenRead($archive); try { $names=@($zip.Entries.FullName|Sort-Object); "
                       + "if(($names -join ',') -cne 'ports/android/app/build.gradle.kts,ports/android/engines/build.gradle.kts,README.md'){throw 'Source archive flattened paths'} }finally{$zip.Dispose()}")
            pwsh = shutil.which("pwsh")
            result = subprocess.run([pwsh, "-NoProfile", "-NonInteractive", "-Command", command],
                                    capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_all_producer_powershell_parses_without_execution(self):
        pwsh = shutil.which("pwsh") or str(pathlib.Path("C:/Users/dieonfire/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe"))
        paths = list(HERE.glob("*.ps1")) + [HERE.parents[2] / "maintenance" / name for name in ("Build-Release08.ps1", "Install-Release08.ps1")]
        command = "$ErrorActionPreference='Stop'; @(" + ",".join("'" + p.as_posix() + "'" for p in paths) + ") | ForEach-Object { $tokens=$null; $errors=$null; $null=[System.Management.Automation.Language.Parser]::ParseFile($_,[ref]$tokens,[ref]$errors); if($errors.Count){throw ($errors.Message -join '; ')} }"
        result = subprocess.run([pwsh, "-NoProfile", "-NonInteractive", "-Command", command],
                                capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
