"""Source/contract tests only. No provider, device, native executable or network."""
import pathlib
import json
import sys
import tempfile
import unittest

HERE=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
import prepare_csqtt_direct_tun as overlay
import bs_multicall_inputs as gate

FIXTURE=pathlib.Path("D:/MCRF/Builds/Android/bs-rust-vk-backoff-20261102-02/adapters/g253/rust-client")

class DirectTunTests(unittest.TestCase):
    def test_android_test_target_and_linker_flags_are_explicit(self):
        env=overlay.android_link_environment("clang","x86_64-linux-android")
        self.assertEqual(env["CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"],"clang")
        self.assertIn("--target=x86_64-linux-android26",env["RUSTFLAGS"])
        for target in ("x86_64-unknown-linux-gnu","x86_64-pc-windows-gnu","armv7-linux-androideabi",""):
            with self.assertRaises(ValueError):overlay.android_link_environment("clang",target)
    def source(self):
        # Required pinned fixture, never silently skip an unavailable source gate.
        self.assertTrue(FIXTURE.is_dir(),"approved g253 native source fixture required")
        return tuple((FIXTURE/name).read_text(encoding="utf-8") for name in ("main.rs","tun.rs","dispatcher.rs"))

    def test_overlay_preserves_original_io_and_defers_direct_ack(self):
        main,tun,dispatcher=overlay.transform(*self.source())
        self.assertIn("mcrf_direct_tun::configure(&arguments.tun_uds)?;",main)
        self.assertIn("return crate::mcrf_direct_tun::receive(&self.listener, cancel).await",tun)
        self.assertLess(tun.index("return crate::mcrf_direct_tun::receive"),tun.index("pub async fn receive" )+tun[tun.index("pub async fn receive"):].index("message.cmsgs()"))
        self.assertLess(dispatcher.index("let device = match AsyncFd::new(file)"),dispatcher.index("crate::mcrf_direct_tun::adopted()"))
        self.assertLess(dispatcher.index("let mut write_task = tokio::spawn"),dispatcher.index("crate::mcrf_direct_tun::adopted()"))
        self.assertIn(".read_tun(read_device, read_pool, read_stats, read_cancel)",dispatcher)
        self.assertIn(".write_tun(device, write_stats, write_cancel)",dispatcher)
        self.assertIn("replacement = replacements.recv(), if !crate::mcrf_direct_tun::active()",dispatcher)

    def test_single_direct_handoff_parks_and_send_failure_terminates_without_changing_legacy_loop(self):
        _,_,dispatcher=overlay.transform(*self.source())
        call="crate::mcrf_direct_tun::handoff_once(receiver, file, &fd_tx, &receive_cancel)"
        self.assertEqual(dispatcher.count(call),1)
        before,after=dispatcher.split(call)
        self.assertTrue(before.rstrip().endswith("if"))
        strict,legacy=after.split("                        if let Err(error) = fd_tx.send(file).await {",1)
        self.assertIn(".await.is_err() && !receive_cancel.is_cancelled()",strict)
        self.assertIn("receive_dispatcher.terminate(Termination::TunLost)",strict)
        self.assertIn("return;",strict)
        self.assertIn("tokio::time::sleep(Duration::from_millis(100)).await;",legacy)
        native=(HERE/"mcrf_direct_tun.rs").read_text(encoding="utf-8")
        helper=native.split("pub async fn handoff_once",1)[1].split("fn event",1)[0]
        self.assertLess(helper.index("sender.send(file).await"),helper.index("drop(receiver)"))
        self.assertLess(helper.index("drop(receiver)"),helper.index("cancel.cancelled().await"))

    def test_changed_anchor_rejected_before_any_file_is_written(self):
        main,tun,dispatcher=self.source()
        with self.assertRaises(ValueError):overlay.transform(main.replace("mod tun;","mod changed_tun;"),tun,dispatcher)
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            originals=(main,tun,dispatcher.replace("let mut file = initial_file;","changed initial file;"))
            for name,text in zip(("main.rs","tun.rs","dispatcher.rs"),originals):
                (root/name).write_text(text,encoding="utf-8")
            with self.assertRaises(ValueError):overlay.apply(root)
            self.assertEqual(tuple((root/name).read_text(encoding="utf-8") for name in ("main.rs","tun.rs","dispatcher.rs")),originals)
            self.assertFalse((root/"mcrf_direct_tun.rs").exists())

    def test_reapplying_overlay_is_not_silently_accepted(self):
        with self.assertRaises(ValueError):overlay.transform(*overlay.transform(*self.source()))

    def test_capability_is_compiled_not_a_version_label(self):
        self.assertEqual(gate.direct_tun_contract({"versionCode":6,"directTunFdContract":1},
            {"directTunFdContract":1,"directTunGenerations":["2.5.2","2.5.3"],"directTunUnixTests":{"state":"UNIX_DIRECT_TUN_TESTS_PASSED"}}),1)
        with self.assertRaises(ValueError):gate.direct_tun_contract({"versionCode":6,"directTunFdContract":1},
            {"directTunFdContract":1,"directTunGenerations":["2.5.2","2.5.3"]})
        for declared,compiled in ((1,0),(1,True),(True,1),(0,1)):
            with self.assertRaises(ValueError):gate.direct_tun_contract({"versionCode":6,"directTunFdContract":declared},
                {"directTunFdContract":compiled,"directTunGenerations":["2.5.2","2.5.3"]})
        with self.assertRaises(ValueError):gate.direct_tun_contract({"versionCode":5,"directTunFdContract":1},
            {"directTunFdContract":1,"directTunGenerations":["2.5.2","2.5.3"]})

    def test_native_protocol_source_has_no_raw_or_socket_fallback(self):
        source=(HERE/"mcrf_direct_tun.rs").read_text(encoding="utf-8")
        for required in ("SO_PEERCRED","TUNGETIFF","IFF_NO_PI","F_DUPFD_CLOEXEC","MCRF_DIRECT_TUN_IDENTITY", "MCRF_DIRECT_TUN_ADOPTED","MCRF_DIRECT_TUN_LOST"):
            self.assertIn(required,source)
        for forbidden in ("UdpSocket", "netstack", "RelayIP", "TcpStream::connect", "std::process::Command"):
            self.assertNotIn(forbidden,source)

class UnixProofParserTests(unittest.TestCase):
    """Synthetic parser fixtures are NOT evidence of native execution."""
    def setUp(self):
        from bs_engine_inputs import sha
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root=self.root=pathlib.Path(self.temp.name)
        stage=self.stage=root/"stage"
        native=stage/"adapters/g253/rust-client"
        native.mkdir(parents=True)
        harness=stage/"admission-tests"
        harness.mkdir()
        for name in ("mcrf_direct_tun.rs","mcrf_direct_tun_receive.rs"):
            (native/name).write_bytes((HERE/name).read_bytes())
        (harness/"direct_tun_harness.rs").write_bytes((HERE/"direct_tun_harness.rs").read_bytes())
        (harness/"Cargo.toml").write_text('[package]\nname="parser-fixture"\nversion="0.0.0"\n',encoding="utf-8")
        (harness/"lib.rs").write_text("// synthetic parser-only input\n",encoding="utf-8")
        (harness/"Cargo.lock").write_text('version=4\n[[package]]\nname="parser-fixture"\nversion="0.0.0"\n',encoding="utf-8")
        for key in ("g20","g215","g219","g251","g253"):
            directory=stage/"adapters"/key/"rust-client"
            directory.mkdir(parents=True,exist_ok=True)
            (directory/"Cargo.lock").write_bytes((harness/"Cargo.lock").read_bytes())
        prepared={"producerSha256":sha(HERE/"csqtt_multicall_build.py"),"allowedRegistry":[],
                  "directTunOverlays":{name:sha(HERE/name) for name in overlay.DIRECT_OVERLAYS},
                  "generated":[{"path":p.relative_to(stage).as_posix(),"sha256":sha(p)} for p in stage.rglob("*") if p.is_file()]}
        self.write("prepared.json",prepared)
        binary=root/"synthetic-not-executable"
        header=bytearray(64)
        header[:7]=b"\x7fELF\x02\x01\x01"
        header[16:20]=b"\x03\x00\xb7\x00"
        binary.write_bytes(header)
        tool=root/"synthetic-tool"
        tool.write_bytes(b"parser fixture; no compiler ran")
        events=[{"reason":"compiler-artifact","profile":{"test":True},
                 "target":{"name":"mcrf_vk_backoff_admission_tests"},"executable":str(binary)},
                {"reason":"build-finished","success":True}]
        (root/"cargo.jsonl").write_text("\n".join(json.dumps(e) for e in events),encoding="utf-8")
        self.build={"state":"DIRECT_TUN_TESTS_COMPILED_NOT_EXECUTED","platform":"android","target":"aarch64-linux-android",
                    "stage":str(stage),"preparedInputs":overlay.file_row(root/"prepared.json"),
                    "inputs":overlay.compile_inputs(stage,prepared),"sources":overlay.native_sources(),
                    "toolchain":{k:overlay.file_row(tool) for k in ("cargo","rustc","ndkClang")},
                    "linkEnvironment":overlay.android_link_environment(tool),
                    "command":[str(tool),"test","--target","aarch64-linux-android","--manifest-path",
                               "admission-tests/Cargo.toml","--lib","--no-run","--frozen","--message-format=json"],
                    "exitCode":0,"sourceStable":True,"testBinary":overlay.file_row(binary),"cargoLog":overlay.file_row(root/"cargo.jsonl")}
        self.write("compile.json",self.build)
        cases=(*overlay.UNIX_CASES,"direct_tun_harness::direct_tun::tests::token_requires_canonical_positive_signed_63bit")
        (root/"run.log").write_text("\n".join("test "+name+" ... ok" for name in cases)+
                                   f"\ntest result: ok. {len(cases)} passed; 0 failed; 0 ignored; 0 measured; 24 filtered out; finished in 0.03s\n",encoding="utf-8")
        self.proof={"state":"UNIX_DIRECT_TUN_TESTS_PASSED","platform":"android","target":"aarch64-linux-android",
                    "exitCode":0,"providerTraffic":False,"sourceStable":True,"sources":overlay.native_sources(),
                    "compileReceipt":overlay.file_row(root/"compile.json"),"testBinary":self.build["testBinary"],
                    "testLog":overlay.file_row(root/"run.log"),
                    "deviceBinaryBefore":{"sha256":sha(binary),"bytes":64},"deviceBinaryAfter":{"sha256":sha(binary),"bytes":64},
                    "executedBinaryPath":"/data/local/tmp/mcrf-direct-tun-"+sha(binary)[:16],
                    "command":["/data/local/tmp/mcrf-direct-tun-"+sha(binary)[:16],"direct_tun_harness::","--test-threads=1","--nocapture"]}

    def write(self,name,value):
        (self.root/name).write_text(json.dumps(value),encoding="utf-8")

    def verify(self):
        self.write("proof.json",self.proof)
        return overlay.verify_unix_proof(self.root/"proof.json")

    def test_explicit_android_parser_fixture_is_not_relabelled_linux(self):
        result=self.verify()
        self.assertEqual((result["platform"],result["target"]),("android","aarch64-linux-android"))

    def test_old_seven_case_proof_cannot_replace_handoff_lifetime_regressions(self):
        log=self.root/"run.log"
        text=log.read_text(encoding="utf-8")
        for case in overlay.UNIX_CASES[-2:]:text=text.replace("test "+case+" ... ok\n","")
        text=text.replace("9 passed;","7 passed;")
        log.write_text(text,encoding="utf-8")
        self.proof["testLog"]=overlay.file_row(log)
        with self.assertRaises(ValueError):self.verify()

    def test_false_zero_nonzero_exit_and_wrong_platform_are_rejected(self):
        for key,value in (("exitCode",False),("exitCode",1),("platform","linux"),("platform","windows"),
                          ("target","x86_64-unknown-linux-gnu"),("deviceBinaryAfter",{"sha256":"0"*64,"bytes":64}),
                          ("deviceBinaryBefore",{"sha256":self.proof["deviceBinaryBefore"]["sha256"],"bytes":65}),
                          ("executedBinaryPath","/data/local/tmp/different")):
            with self.subTest(key=key,value=value):
                old=self.proof[key]
                self.proof[key]=value
                with self.assertRaises(ValueError):self.verify()
                self.proof[key]=old

    def test_wrong_elf_machine_and_windows_binary_are_rejected(self):
        binary=self.root/"synthetic-not-executable"
        for content in (b"MZ"+bytes(62),b"\x7fELF\x02\x01\x01"+bytes(9)+b"\x03\x00\x3e\x00"+bytes(44)):
            binary.write_bytes(content)
            self.build["testBinary"]=overlay.file_row(binary)
            self.write("compile.json",self.build)
            with self.assertRaises(ValueError):overlay.verify_test_compile(self.root/"compile.json")

    def test_copied_log_altered_leaf_or_missing_build_receipt_rejected(self):
        (self.root/"run.log").write_text("copied passing summary only",encoding="utf-8")
        with self.assertRaises(ValueError):self.verify()
        self.proof["testLog"]=overlay.file_row(self.root/"run.log")
        with self.assertRaises(ValueError):self.verify()
        self.proof.pop("compileReceipt")
        with self.assertRaises((ValueError,KeyError)):self.verify()

    def test_only_exact_ack_adoption_interleaving_is_accepted(self):
        log=self.root/"run.log"
        original=log.read_text(encoding="utf-8")
        ack="test "+overlay.UNIX_CASES[2]+" ... "
        split=ack+"MCRF_DIRECT_TUN_ADOPTED 1\nok"
        def set_log(text):
            log.write_text(text,encoding="utf-8")
            self.proof["testLog"]=overlay.file_row(log)
        set_log(original.replace(ack+"ok",split))
        self.assertEqual(self.verify()["state"],"UNIX_DIRECT_TUN_TESTS_PASSED")
        other="test "+overlay.UNIX_CASES[0]+" ... "
        invalid=(
            split.replace("ADOPTED 1","ADOPTED 2"),
            split.replace("ADOPTED","LOST"),
            split.replace("\nok","\nFAILED"),
            split.replace("\nok","\nignored"),
            split.replace("\nok","\nextra\nok"),
            split+" trailing",
            split+"\n"+split,
        )
        for value in invalid:
            with self.subTest(value=value):
                set_log(original.replace(ack+"ok",value))
                with self.assertRaises(ValueError):self.verify()
        set_log(original.replace(other+"ok",other+"MCRF_DIRECT_TUN_ADOPTED 1\nok"))
        with self.assertRaises(ValueError):self.verify()

    def test_source_or_lock_drift_after_compile_is_rejected(self):
        for name in ("lib.rs","Cargo.lock"):
            path=self.stage/"admission-tests"/name
            original=path.read_bytes()
            path.write_bytes(original+b"\n# changed\n")
            with self.assertRaises(ValueError):self.verify()
            path.write_bytes(original)

    def test_unrelated_elf_or_unsuccessful_cargo_artifact_rejected(self):
        unrelated=self.root/"other-elf"
        unrelated.write_bytes((self.root/"synthetic-not-executable").read_bytes())
        for events in ([{"reason":"build-finished","success":False}],
                       [{"reason":"compiler-artifact","profile":{"test":True},
                         "target":{"name":"mcrf_vk_backoff_admission_tests"},"executable":str(unrelated)},
                        {"reason":"build-finished","success":True}]):
            (self.root/"cargo.jsonl").write_text("\n".join(json.dumps(e) for e in events),encoding="utf-8")
            self.build["cargoLog"]=overlay.file_row(self.root/"cargo.jsonl")
            self.write("compile.json",self.build)
            with self.assertRaises(ValueError):overlay.verify_test_compile(self.root/"compile.json")

    def test_omitted_generated_input_overlay_and_added_source_are_rejected(self):
        from bs_engine_inputs import load
        original=load(self.root/"prepared.json")
        omitted=[row for row in original["generated"] if row["path"]!="admission-tests/lib.rs"]
        self.assertEqual(len(omitted),len(original["generated"])-1)
        for key,value in (("generated",omitted),("directTunOverlays",{})):
            changed={**original,key:value}
            self.write("prepared.json",changed)
            self.build["preparedInputs"]=overlay.file_row(self.root/"prepared.json")
            self.write("compile.json",self.build)
            with self.assertRaises(ValueError):overlay.verify_test_compile(self.root/"compile.json")
        self.write("prepared.json",original)
        self.build["preparedInputs"]=overlay.file_row(self.root/"prepared.json")
        self.write("compile.json",self.build)
        (self.stage/"admission-tests/extra.rs").write_text("// undeclared input",encoding="utf-8")
        with self.assertRaises(ValueError):overlay.verify_test_compile(self.root/"compile.json")


if __name__=="__main__":unittest.main()
