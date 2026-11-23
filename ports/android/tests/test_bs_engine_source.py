"""Source/packaging unit checks only. No Gradle, compiler, APK install or native execution."""
import importlib.util
import json
import pathlib
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ANDROID = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ANDROID / "transports/swg"))
import bs_engine_inputs as bs
import prepare_bs_wrapper as wrapper


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) else data.encode())


QWDTT_DISPATCHER = b"""type Dispatcher struct {
	tunReadCount uint64
	tunSentCount uint64
	tunDroppedCount uint64
}
func count(d *Dispatcher) {
	atomic.AddUint64(&d.tunReadCount, 1)
	atomic.AddUint64(&d.tunSentCount, 1)
	atomic.AddUint64(&d.tunSentCount, 1)
	atomic.AddUint64(&d.tunDroppedCount, 1)
	atomic.LoadUint64(&d.tunSentCount)
	atomic.LoadUint64(&d.tunDroppedCount)
}
"""

QWDTT_UNDERLAY_SOURCES = {
    'session.go': b'conn, err := (&net.Dialer{}).DialContext(ctx, "udp", turnAddr)\nd := net.Dialer{Timeout: 10 * time.Second}',
    'creds.go': b'tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),\ndialer := &net.Dialer{\n\t\tTimeout:   3 * time.Second,\n\t\tKeepAlive: 30 * time.Second,\n\t}',
    'creds_vkcalls.go': b'tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),',
    'doh.go': b'func newDoHBootstrapDialer() *net.Dialer {\n\treturn &net.Dialer{\n\t}\n}\n\nfunc newDoHHTTPClient()',
    'captcha_proxy.go': b'return startCaptchaProxyWithDial(parent, (&net.Dialer{Timeout: 15 * time.Second}).DialContext)',
}


class BsSourceTests(unittest.TestCase):
    def notice_fixture(self, root):
        roots = {kind: root / kind for kind in (*bs.KINDS, "wrapper", "versions")}
        for kind, directory in roots.items():
            directory.mkdir()
            if kind in bs.KINDS:
                # Derived stages have no licenses directory. Their original legal
                # files are already included in the receipt-bound source manifest.
                source = directory / "module"
                write(source / "LICENSE", "shared legal notice")
                rows = [{"path": "LICENSE", "sha256": bs.sha(source / "LICENSE")}]
                write(directory / "source-manifest.json", json.dumps(rows))
                write(directory / "receipt.json", json.dumps({"source": str(source),
                      "sourceManifestSha256": bs.sha(directory / "source-manifest.json")}))
            else:
                write(directory / "licenses/LICENSE", "shared legal notice")
        return roots

    def test_derived_roots_stage_pinned_notices_and_deduplicate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            roots = self.notice_fixture(root)
            gate = {"notices": bs.notice_inputs(roots)}
            out = root / "staged"
            with patch.object(bs, "inspect", return_value=(gate, roots, {})):
                bs.stage("unused", "unused", out)
            index = bs.load(out / "assets/notices-index.json")
            self.assertEqual(set(roots), {row["engine"] for row in index})
            self.assertEqual(1, len(list((out / "assets/licenses").iterdir())))
            self.assertEqual("shared legal notice", next((out / "assets/licenses").iterdir()).read_text(encoding="utf-8"))

    def test_notices_reject_tamper_added_files_and_missing_gate_inventory(self):
        with tempfile.TemporaryDirectory() as tmp:
            roots = self.notice_fixture(pathlib.Path(tmp))
            gate = {"notices": bs.notice_inputs(roots)}
            with self.assertRaisesRegex(ValueError, "refreeze"):
                bs.verified_notices({}, roots)
            write(roots["wrapper"] / "licenses/unpinned.txt", "arbitrary")
            with self.assertRaisesRegex(ValueError, "refreeze"):
                bs.verified_notices(gate, roots)
            write(roots["csqtt20"] / "module/LICENSE", "tampered")
            with self.assertRaisesRegex(ValueError, "Frozen input changed"):
                bs.notice_inputs(roots)

    def test_notices_never_fall_back_to_loose_or_receipt_stage_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            roots = self.notice_fixture(root)
            manifest = roots["qwdtt"] / "source-manifest.json"
            write(manifest, "[]")
            receipt = bs.load(roots["qwdtt"] / "receipt.json")
            receipt.update(sourceManifestSha256=bs.sha(manifest), stage=str(roots["wrapper"]))
            write(roots["qwdtt"] / "receipt.json", json.dumps(receipt))
            write(roots["qwdtt"] / "LICENSE", "loose file must not authorize notices")
            with self.assertRaisesRegex(ValueError, "Missing source-bound native notices: qwdtt"):
                bs.notice_inputs(roots)

    def test_wdtt_rebuild_host_evidence_is_required_and_fail_closed(self):
        cases = ["TestMcrfRawMTUCeilingPreservesSmallerServerValue", "TestMcrfIPRelayBuffersOnOwnedSocket",
                 "TestMcrfSOCKSAdmissionQueuesAndResumes", "TestMcrfWdtt512ClientsAndOverflowBackpressure"]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            log = root / "host-tests.jsonl"
            good = [{"Action": "pass", "Test": name} for name in cases] + [{"Action": "pass"}]
            receipt = {"sourceRecovery": {"rebuilt": True, "canonicalRevision": "f706ccd373685bbde20cae33b2b7f400e8335ecc"}}
            write(log, "\n".join(map(json.dumps, good)))
            receipt["hostEvidenceSha256"] = bs.sha(log)
            bs.verify_wdtt_rebuild(root, receipt)
            write(log, "tampered")
            with self.assertRaisesRegex(ValueError, "host log changed"):
                bs.verify_wdtt_rebuild(root, receipt)
            for bad in (good[1:], good[:-1], good + [{"Action": "fail"}], good + [{"Action": "skip"}]):
                write(log, "\n".join(map(json.dumps, bad)))
                receipt["hostEvidenceSha256"] = bs.sha(log)
                with self.assertRaisesRegex(ValueError, "host cases"):
                    bs.verify_wdtt_rebuild(root, receipt)

    def test_native_receipt_requires_exact_abi_inventory(self):
        rows = [{"abi": abi, "file": f"{abi}/libqwdtt.so"} for abi in bs.ABIS]
        bs.native_inventory({"artifacts": rows}, "wrapper")
        for extra in (rows[0], {"abi": "x86", "file": "x86/libqwdtt.so"},
                      {"abi": "arm64-v8a", "file": "arm64-v8a/extra.so"}):
            with self.assertRaisesRegex(ValueError, "ABI inventory"):
                bs.native_inventory({"artifacts": rows + [extra]}, "wrapper")
        with self.assertRaisesRegex(ValueError, "ABI inventory"):
            bs.native_inventory({"artifacts": rows[:-1]}, "wrapper")

    def test_rejects_unpinned_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "gate.json"
            write(path, '{}')
            with self.assertRaisesRegex(ValueError, "gate SHA"):
                bs.inspect(path, "0" * 64)

    def test_json_cannot_override_security_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "gate.json"
            write(path, '{"schema":1,"schema":2}')
            with self.assertRaisesRegex(ValueError, "Duplicate JSON"):
                bs.load(path)

    def test_no_parent_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                bs.bound({"path": "../outside", "sha256": "0" * 64}, pathlib.Path(tmp))

    def test_wrong_native_abi_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "lib.so"
            data = bytearray(120)
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<HH", data, 16, 3, 62)
            struct.pack_into("<Q", data, 32, 64)
            struct.pack_into("<HH", data, 54, 56, 1)
            struct.pack_into("<I", data, 64, 1)
            struct.pack_into("<Q", data, 112, 16384)
            write(path, bytes(data))
            bs.elf(path, "x86_64")
            with self.assertRaisesRegex(ValueError, "ELF/ABI"):
                bs.elf(path, "arm64-v8a")

    def baseline(self, root):
        src = root / "module"
        write(src / "csqtt_bridge.go", 'package main\nfunc f() error {\n name := ""\n if runtime.GOOS == "android" { name = "libcsqtt_transport.so" }\n\tcommand := exec.Command(filepath.Join(filepath.Dir(exe), name))\n return nil\n}\n')
        write(src / "go.mod", "module fixture\n")
        manifest = root / "source-manifest.json"
        write(manifest, json.dumps([{"path": p.name, "sha256": bs.sha(p)} for p in sorted(src.iterdir())]))
        return src, manifest

    def test_wrapper_preparer_changes_only_selector_and_adds_helper(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            src, manifest = self.baseline(root)
            before = (src / "csqtt_bridge.go").read_bytes()
            out = root / "prepared"
            wrapper.prepare(src, manifest, bs.sha(manifest), out)
            self.assertEqual(before, (src / "csqtt_bridge.go").read_bytes())
            self.assertEqual(1, (out / "csqtt_bridge.go").read_text(encoding="utf-8").count("mcrfBSTransport(name)"))
            self.assertEqual(bs.sha(out / "mcrf_bs_transport.go"), bs.sha(ANDROID / "transports/swg/mcrf_bs_transport.go"))
            proof = bs.load(out / "bs-wrapper-source.json")
            self.assertEqual("BS_WRAPPER_PREPARED_NOT_COMPILED", proof["state"])
            self.assertEqual({"csqtt_bridge.go","go.mod","mcrf_bs_transport.go","mcrf_bs_transport_test.go","mcrf_child_failure.go","mcrf_child_failure_test.go"},{row["path"] for row in proof["sources"]})

    def test_wrapper_refuses_stale_source_and_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            src, manifest = self.baseline(root)
            out = root / "prepared"
            wrapper.prepare(src, manifest, bs.sha(manifest), out)
            with self.assertRaisesRegex(ValueError, "Fresh"):
                wrapper.prepare(src, manifest, bs.sha(manifest), out)
            write(src / "extra.go", "package main")
            with self.assertRaisesRegex(ValueError, "source set"):
                wrapper.prepare(src, manifest, bs.sha(manifest), root / "other")

    def test_qwdtt_dispatcher_uses_intrinsically_aligned_atomic_counters(self):
        output = wrapper.align_qwdtt_dispatcher_counters(QWDTT_DISPATCHER).decode()
        for counter in ("tunReadCount", "tunSentCount", "tunDroppedCount"):
            self.assertIn(counter + " atomic.Uint64", output)
            self.assertNotIn("&d." + counter, output)
        self.assertEqual(2, output.count("d.tunSentCount.Add(1)"))
        with self.assertRaisesRegex(ValueError, "counter sites changed"):
            wrapper.align_qwdtt_dispatcher_counters(
                QWDTT_DISPATCHER.replace(b"atomic.AddUint64(&d.tunSentCount, 1)", b"", 1))

    def multimode_fixture(self, root):
        src, manifest = self.baseline(root)
        write(src / "main.go", 'package main\nfunc main() {\n}\n')
        def freeze(source, path):
            write(path, json.dumps([{"path": p.relative_to(source).as_posix(), "sha256": bs.sha(p)}
                                   for p in sorted(source.rglob("*")) if p.is_file()]))
        write(src / "dispatcher.go", QWDTT_DISPATCHER)
        for name, content in QWDTT_UNDERLAY_SOURCES.items():
            write(src / name, content)
        freeze(src, manifest)
        pins = []
        for kind in ("csqtt25", "qwdtt"):
            stage=root/kind; dest=stage/"module"
            for p in src.iterdir():
                write(dest/p.name,p.read_bytes())
            if kind=="csqtt25":
                write(dest/"main.go", 'package main\nfunc main() {\n if os.Args[1] == "-csqtt25-config" {}\n if os.Args[1] == "-csqtt-config" {}\n}\n')
                bridge=(dest/"csqtt_bridge.go").read_text(encoding="utf-8")
                write(dest/"csqtt_bridge.go", bridge+'\nfunc runCSQTTBridgePrepared() {\n\tmcrfNativeStreams.useChild()\n\t// MTU is fixed at 1300 in the original Android VPN service.\n}\n')
                write(dest/"mcrf_csqtt25.go", "package main\n")
                write(dest/"internal/csqtt25/profile.go", "package csqtt25\n")
            freeze(dest,stage/"source-manifest.json")
            receipt=stage/"receipt.json"
            write(receipt,json.dumps({"kind":kind,"source":str(dest),"sourceStable":True,"state":"COMPILED_NOT_EXECUTED",
                                      "sourceManifestSha256":bs.sha(stage/"source-manifest.json")}))
            pins.append({"path":str(receipt),"sha256":bs.sha(receipt)})
        return src,manifest,*pins

    def test_multimode_replays_exact_adaptation_and_rejects_tamper(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp);src,manifest,modern,duplicate=self.multimode_fixture(root)
            out=root/"prepared"
            wrapper.prepare(src,manifest,bs.sha(manifest),out,modern,duplicate)
            proof=bs.load(out/"bs-wrapper-source.json")
            bs.verify_multimode_preparation(proof,modern,duplicate)
            self.assertIn("mcrfBSValidateLaunch(os.Args)",(out/"main.go").read_text(encoding="utf-8"))
            self.assertIn("if launch == nil",(out/"csqtt_bridge.go").read_text(encoding="utf-8"))
            write(out/"main.go",(out/"main.go").read_bytes()+b"// unreviewed\n")
            for row in proof["sources"]:
                if row["path"]=="main.go":row["sha256"]=bs.sha(out/"main.go")
            with self.assertRaisesRegex(ValueError,"Adapted source bytes"):
                bs.verify_multimode_preparation(proof,modern,duplicate)

    def test_multimode_refuses_nonidentical_qwdtt_and_unreviewed_modern_delta(self):
        for target in ("qwdtt", "csqtt25"):
            with tempfile.TemporaryDirectory() as tmp:
                root=pathlib.Path(tmp);src,manifest,modern,duplicate=self.multimode_fixture(root)
                stage=root/target
                write(stage/"module/go.mod","module unreviewed\n")
                rows=bs.load(stage/"source-manifest.json")
                for row in rows:
                    if row["path"]=="go.mod":row["sha256"]=bs.sha(stage/"module/go.mod")
                write(stage/"source-manifest.json",json.dumps(rows))
                receipt=bs.load(stage/"receipt.json");receipt["sourceManifestSha256"]=bs.sha(stage/"source-manifest.json")
                write(stage/"receipt.json",json.dumps(receipt))
                (duplicate if target=="qwdtt" else modern)["sha256"]=bs.sha(stage/"receipt.json")
                with self.assertRaisesRegex(ValueError,"duplicate|Unreviewed"):
                    wrapper.prepare(src,manifest,bs.sha(manifest),root/"out",modern,duplicate)

    def test_multimode_catalog_keeps_all_generations_and_standalone_wdtt(self):
        catalog=bs.catalog({})
        self.assertEqual(4,catalog["schema"])
        self.assertNotIn("wdtt",catalog["engines"])
        self.assertEqual(set(bs.KINDS),set(catalog["engines"]))
        for kind,node in catalog["engines"].items():
            self.assertEqual("libbs.so",node["executable"])
        with self.assertRaisesRegex(ValueError,"Multi-mode source proof"):
            bs.verify_multimode_preparation({"modeContract":1})

    def test_process_host_mode_comes_from_logical_id_only(self):
        source=(ANDROID/"app/src/main/kotlin/org/mcrf/client/NativeCarrier.kt").read_text(encoding="utf-8")
        self.assertIn("configureHostMode(builder.environment(),binary.name,moduleKind,",source)
        self.assertIn("BsEnginePackage.usesMulticall(context)",source)
        self.assertIn("CsqttSwgVersions.needsNewRuntime(profile)&&!multicall",source)
        self.assertNotIn('profile.optString("MCRF_BS_MODE")',source)
        host=(ANDROID/"app/src/main/kotlin/org/mcrf/client/BsEngineContract.kt").read_text(encoding="utf-8")
        self.assertIn('env.remove("MCRF_BS_MODE");env.remove("MCRF_BS_TRANSPORT")',host)
        self.assertIn('env["MCRF_BS_MODE"]=logical',host)

    def test_inventory_does_not_call_builds(self):
        source = (ANDROID / "maintenance/bs_size_inventory.py").read_text(encoding="utf-8")
        self.assertNotIn("subprocess", source)
        self.assertNotIn("write_bytes", source)

    def test_package_refuses_shared_debug_fixture_and_other_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            native = root / "native.so"
            write(native, b"source-bound-native")
            outputs = {abi: {"libbs.so": native} for abi in bs.ABIS}
            apk = root / "fixture.apk"
            for extra in (None, "lib/arm64-v8a/libopenflux_testserver.so", "lib/x86_64/libbs_csqtt.so"):
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("lib/arm64-v8a/libbs.so", native.read_bytes())
                    dex=bytearray(128);dex[:4]=b"dex\n";struct.pack_into("<I",dex,96,1)
                    descriptor=b"Lorg/mcrf/engines/R;";dex[104:104+len(descriptor)]=descriptor
                    struct.pack_into("<I",dex,32,len(dex))
                    archive.writestr("classes.dex",dex)
                    archive.writestr("assets/mcrf-bs.json", json.dumps(bs.catalog(outputs)))
                    if extra:
                        archive.writestr(extra, b"unrelated")
                if extra:
                    with self.assertRaisesRegex(ValueError, "Unexpected native payload/ABI"):
                        bs.verify_apk(apk, outputs, "arm64-v8a")
                else:
                    bs.verify_apk(apk, outputs, "arm64-v8a")

    def test_abi_outputs_and_source_gate_are_wired(self):
        source = (ANDROID / "engines/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('bsEngineInputsSha256', source)
        self.assertIn('if(kind=="bs")', source)
        self.assertIn('StageBsEngine::jniDirectory', source)
        self.assertIn('StageBsEngine::assetsDirectory', source)
        self.assertIn('output.filters.any{it.filterType==com.android.build.api.variant.FilterConfiguration.FilterType.ABI}', source)

    def test_uninstall_never_silent_or_changes_profiles(self):
        source = (ANDROID / "app/src/main/kotlin/org/mcrf/client/BsEngineMigration.kt").read_text(encoding="utf-8")
        self.assertIn('Intent.ACTION_UNINSTALL_PACKAGE', source)
        self.assertIn('.setPositiveButton("Удалить старые")', source)
        self.assertIn('advance(result==Activity.RESULT_OK,absent,verified)', source)
        for forbidden in ("DELETE_ALL_USERS", "DELETE_PACKAGES", "ProfileStore(", "replaceAll(", "Runtime.getRuntime", "pm uninstall"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
