"""Source-only strict g253 FD overlay; never edits pinned originals or runs code."""
import pathlib
import shutil

HERE=pathlib.Path(__file__).resolve().parent
FILES=("prepare_csqtt_direct_tun.py","mcrf_direct_tun.rs","mcrf_direct_tun_receive.rs")
VERSION=1
GENERATIONS=("2.5.2","2.5.3")
DIRECT_OVERLAYS=("prepare_csqtt_direct_tun.py","mcrf_direct_tun.rs","mcrf_direct_tun_receive.rs",
                 "direct_tun_harness.rs","test_prepare_csqtt_direct_tun.py")
UNIX_CASES=(
    "direct_tun_harness::direct_tun::tests::ordinary_file_is_not_a_tun_descriptor",
    "direct_tun_harness::direct_tun::tests::unix_peer_same_uid_is_observed_not_assumed",
    "direct_tun_harness::direct_tun::tests::acknowledgement_requires_received_fd_and_only_adopts_once",
    "direct_tun_harness::direct_tun::receiver::tests::two_descriptors_are_rejected_without_fd_leak",
    "direct_tun_harness::direct_tun::receiver::tests::truncated_rights_are_owned_and_closed_without_fd_leak",
    "direct_tun_harness::direct_tun::receiver::tests::initial_accept_deadline_and_cancellation_are_bounded",
    "direct_tun_harness::direct_tun::tests::one_fd_handoff_outlives_startup_deadline_and_cancels_without_leak",
    "direct_tun_harness::direct_tun::tests::failed_fd_handoff_errors_without_parking_or_leaking",
)

def verify_unix_proof(path):
    """Consume real Linux/Android evidence, bound to an actual Cargo artifact."""
    import re
    from bs_engine_inputs import load,sha,require,bound
    path=pathlib.Path(path).absolute()
    proof=load(path)
    require(proof.get("state")=="UNIX_DIRECT_TUN_TESTS_PASSED" and proof.get("platform") in ("linux","android") and
            type(proof.get("exitCode")) is int and proof["exitCode"]==0 and
            proof.get("providerTraffic") is False and proof.get("sourceStable") is True,
            "Actual Linux or Android direct TUN test execution required")
    require(proof.get("sources")==native_sources(),
            "Unix direct TUN tests use another source")
    compiled=verify_test_compile(checked_row(proof["compileReceipt"]))
    require(all(proof.get(k)==compiled.get(k) for k in ("platform","target","sources","testBinary")),
            "Execution does not match the compiled test artifact")
    binary=checked_row(proof["testBinary"])
    identity={"sha256":sha(binary),"bytes":binary.stat().st_size}
    require(proof.get("deviceBinaryBefore")==identity and proof.get("deviceBinaryAfter")==identity,
            "Pre/post executed-device binary identity missing or changed")
    command=proof.get("command")
    require(isinstance(command,list) and len(command)==4 and isinstance(command[0],str) and command[0] and
            command[1:]==["direct_tun_harness::","--test-threads=1","--nocapture"],
            "Only the bounded direct TUN harness may qualify")
    require(command[0]==proof.get("executedBinaryPath") and
            (proof["platform"]!="android" or command[0]=="/data/local/tmp/mcrf-direct-tun-"+sha(binary)[:16]),
            "Execution command/device binary path mismatch")
    log=checked_row(proof["testLog"]).read_text(encoding="utf-8").replace("\r\n","\n")
    # --nocapture can interleave this exact test's adoption event with libtest.
    # Normalize only its known two-line success; retain all other strict checks.
    ack="test "+UNIX_CASES[2]+" ... "
    log=re.sub(r"^"+re.escape(ack)+r"MCRF_DIRECT_TUN_ADOPTED 1\nok$",
               lambda _:ack+"ok",log,flags=re.M)
    require(all(len(re.findall(r"^test "+re.escape(name)+r" \.\.\. ok$",log,re.M))==1 for name in UNIX_CASES) and
            "test result: ok." in log and not re.search(r"test result: FAILED|\.\.\. FAILED|\.\.\. ignored",log),
            "Required Unix FD/count/truncation/leak/deadline/ACK tests absent or failed")
    expected=len(UNIX_CASES)+1 # Required Unix cases plus the canonical token test.
    require(len(re.findall(r"^test .* \.\.\. ok$",log,re.M))==expected and
            re.search(r"^test result: ok\. "+str(expected)+r" passed; 0 failed; 0 ignored;",log,re.M),
            "Expected exactly the selected native tests, no ignored/failing cases")
    return {"state":"UNIX_DIRECT_TUN_TESTS_PASSED","platform":proof["platform"],"target":proof["target"],
            "path":str(path),"sha256":sha(path),"cases":list(UNIX_CASES)}


def native_sources():
    from bs_engine_inputs import sha
    return {name:sha(HERE/name) for name in ("mcrf_direct_tun.rs","mcrf_direct_tun_receive.rs","direct_tun_harness.rs")}


def file_row(path):
    from bs_engine_inputs import sha
    path=pathlib.Path(path).absolute()
    return {"path":str(path),"sha256":sha(path),"bytes":path.stat().st_size}


def checked_row(row):
    from bs_engine_inputs import bound,require
    path=bound(row)
    require(type(row.get("bytes")) is int and row["bytes"]==path.stat().st_size,"Frozen input size changed")
    return path


def check_test_elf(path,platform,target):
    from bs_engine_inputs import require
    machines={"aarch64-linux-android":("android",183),"x86_64-linux-android":("android",62),"x86_64-unknown-linux-gnu":("linux",62),
              "aarch64-unknown-linux-gnu":("linux",183)}
    require(target in machines and machines[target][0]==platform,"Explicit supported Unix target required")
    with pathlib.Path(path).open("rb") as stream:header=stream.read(64)
    require(len(header)==64 and header[:7]==b"\x7fELF\x02\x01\x01" and
            int.from_bytes(header[16:18],"little") in (2,3) and
            int.from_bytes(header[18:20],"little")==machines[target][1],"Unix test ELF class/machine mismatch")


def compile_inputs(stage,prepared):
    """Reuse the producer's generated inventory and pinned registry union."""
    import tomllib
    from bs_engine_inputs import sha,require,bound
    require(prepared.get("producerSha256")==sha(HERE/"csqtt_multicall_build.py"),"Test producer changed")
    require(prepared.get("directTunOverlays")=={name:sha(HERE/name) for name in DIRECT_OVERLAYS},
            "Test overlay changed")
    from csqtt_multicall_build import inventory,KEYS
    locks=("Cargo.lock","admission-tests/Cargo.lock")
    declared={row["path"]:row["sha256"] for row in prepared["generated"] if row["path"] not in locks}
    actual={row["path"]:row["sha256"] for row in inventory(stage) if row["path"] not in (*locks,"receipt.json") and
            row["path"].split("/")[0] not in ("android-unix-proof","host-tools")}
    require(len({row["path"] for row in prepared["generated"]})==len(prepared["generated"]) and declared==actual,
            "Complete generated source inventory changed or omitted")
    for row in prepared["generated"]:
        if row["path"] not in ("Cargo.lock","admission-tests/Cargo.lock"):bound(row,stage)
    require(sha(stage/"admission-tests/direct_tun_harness.rs")==sha(HERE/"direct_tun_harness.rs") and
            all(sha(stage/"adapters/g253/rust-client"/name)==digest for name,digest in native_sources().items()
                if name!="direct_tun_harness.rs"),"Generated native harness changed")
    lock=stage/"admission-tests/Cargo.lock"
    packages=tomllib.loads(lock.read_text(encoding="utf-8"))["package"]
    allowed={(p["name"],p["version"],p.get("source"),p.get("checksum")) for p in prepared["allowedRegistry"]}
    pinned=set()
    for key in KEYS:
        for package in tomllib.loads((stage/"adapters"/key/"rust-client/Cargo.lock").read_text(encoding="utf-8"))["package"]:
            if "source" in package:pinned.add((package["name"],package["version"],package["source"],package.get("checksum")))
    if prepared.get("goobyBuiltinOverlays"):
        from prepare_csqtt_gooby import source_inventory
        require(prepared["goobyBuiltinOverlays"]==source_inventory(),"Gooby test dependency input changed")
        for package in tomllib.loads((stage/"gooby_builtin/Cargo.lock").read_text(encoding="utf-8"))["package"]:
            if "source" in package:pinned.add((package["name"],package["version"],package["source"],package.get("checksum")))
    require(allowed==pinned,"Admitted registry union differs from generated adapter locks")
    require(all((p["name"],p["version"],p.get("source"),p.get("checksum")) in allowed
                for p in packages if "source" in p),"Test dependency outside pinned producer union")
    return {"sources":native_sources(),"manifest":file_row(stage/"admission-tests/Cargo.toml"),"lock":file_row(lock)}


def verify_test_compile(path):
    import json
    from bs_engine_inputs import load,require,sha
    build=load(path)
    require(build.get("state")=="DIRECT_TUN_TESTS_COMPILED_NOT_EXECUTED" and
            type(build.get("exitCode")) is int and build["exitCode"]==0 and build.get("sourceStable") is True,
            "Successful source-bound native test compilation required")
    prepared=load(checked_row(build["preparedInputs"]))
    require(build["inputs"]==compile_inputs(pathlib.Path(build["stage"]),prepared) and
            build["sources"]==native_sources(),"Compile-to-execution source drift")
    require(set(build["toolchain"])==({"cargo","rustc","ndkClang"} if build["platform"]=="android"
                                      else {"cargo","rustc"}),"Exact compile toolchain identities required")
    for row in build["toolchain"].values():checked_row(row)
    binary=checked_row(build["testBinary"])
    check_test_elf(binary,build["platform"],build["target"])
    command=build["command"]
    if build["platform"]=="android":
        require(command[1:]==["test","--target",build["target"],"--manifest-path",
                "admission-tests/Cargo.toml","--lib","--no-run","--frozen","--message-format=json"],
                "Unexpected Android test compile command")
        require(build.get("linkEnvironment")==android_link_environment(build["toolchain"]["ndkClang"]["path"],build["target"]),
                "Android admission linker/API/flags changed")
    else:
        require(command[1:]==["test","--target",build["target"],"--manifest-path","admission-tests/Cargo.toml",
                "--lib","--no-run","--frozen","--message-format=json"],"Unexpected Linux test compile command")
    require(sha(command[0])==build["toolchain"]["cargo"]["sha256"],"Compile command/compiler mismatch")
    events=[json.loads(line) for line in checked_row(build["cargoLog"]).read_text(encoding="utf-8").splitlines() if line.strip()]
    artifacts=[e for e in events if e.get("reason")=="compiler-artifact" and e.get("profile",{}).get("test") is True
               and e.get("target",{}).get("name")=="mcrf_vk_backoff_admission_tests" and e.get("executable")]
    require(len(artifacts)==1 and pathlib.Path(artifacts[0]["executable"]).absolute()==binary and
            any(e.get("reason")=="build-finished" and e.get("success") is True for e in events),
            "Native ELF is not the successful Cargo test artifact")
    return build


def android_link_environment(clang,target="aarch64-linux-android"):
    from bs_engine_inputs import require
    require(target in ("aarch64-linux-android","x86_64-linux-android"),"Explicit Android 64-bit test target required")
    return {"CARGO_TARGET_"+target.upper().replace('-','_')+"_LINKER":str(clang),"CARGO_NET_OFFLINE":"true",
            "RUSTFLAGS":"-C link-arg=--target="+target+"26 -C link-arg=-Wl,-z,max-page-size=16384"}


def compile_android_tests(args,stage,prepared,target="aarch64-linux-android"):
    """Offline explicit Android 64-bit --no-run. Never accesses a device."""
    import json
    import subprocess
    from bs_engine_inputs import require
    import csqtt_multicall_build as producer
    env=producer.environment(args,stage)
    linker=pathlib.Path(env["LIBCLANG_PATH"])/"clang.exe"
    link_env=android_link_environment(linker,target)
    env.update(link_env)
    cargo=pathlib.Path(args.toolchain)/"bin/cargo.exe"
    out=stage/"android-unix-proof"
    out.mkdir()
    producer.write_json(out/"prepared-inputs.json",prepared)
    with (out/"resolve.log").open("wb") as stream:
        resolved=subprocess.run([str(cargo),"generate-lockfile","--manifest-path","admission-tests/Cargo.toml","--offline"],
                                cwd=stage,env=env,stdout=stream,stderr=subprocess.STDOUT)
    require(resolved.returncode==0,"Offline admission lock resolution failed; see android-unix-proof/resolve.log")
    inputs=compile_inputs(stage,prepared)
    tools={"cargo":file_row(cargo),"rustc":file_row(env["RUSTC"]),
           "ndkClang":file_row(linker)}
    # cargo-ndk 4.1.2 consumes CompilerArtifact/build-finished JSON internally;
    # direct Cargo preserves the actual artifact stream instead of guessing paths.
    command=[str(cargo),"test","--target",target,"--manifest-path","admission-tests/Cargo.toml",
             "--lib","--no-run","--frozen","--message-format=json"]
    with (out/"cargo.jsonl").open("wb") as log,(out/"compile.stderr.log").open("wb") as error:
        result=subprocess.run(command,cwd=stage,env=env,stdout=log,stderr=error)
    require(result.returncode==0,"Android admission --no-run failed; see android-unix-proof/compile.stderr.log")
    events=[json.loads(line) for line in (out/"cargo.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    artifacts=[e for e in events if e.get("reason")=="compiler-artifact" and e.get("profile",{}).get("test") is True
               and e.get("target",{}).get("name")=="mcrf_vk_backoff_admission_tests" and e.get("executable")]
    require(len(artifacts)==1,"Exactly one Android admission test executable required")
    require(inputs==compile_inputs(stage,prepared) and all(file_row(row["path"])==row for row in tools.values()),
            "Source/toolchain changed during test compilation")
    receipt={"state":"DIRECT_TUN_TESTS_COMPILED_NOT_EXECUTED","platform":"android","target":target,
             "stage":str(stage),"preparedInputs":file_row(out/"prepared-inputs.json"),"inputs":inputs,"sources":native_sources(),
             "toolchain":tools,"linkEnvironment":link_env,"command":command,"exitCode":result.returncode,"sourceStable":True,
             "testBinary":file_row(artifacts[0]["executable"]),"cargoLog":file_row(out/"cargo.jsonl")}
    producer.write_json(out/"compile-receipt.json",receipt)
    verify_test_compile(out/"compile-receipt.json")
    return receipt

def once(text,old,new):
    if text.count(old)!=1:
        raise ValueError("Direct TUN native source boundary changed: "+old[:64])
    return text.replace(old,new,1)

def transform(main,tun,dispatcher):
    main=once(main,"mod tun;","mod tun;\nmod mcrf_direct_tun;")
    main=once(main,"async fn run(arguments: Arguments) -> Result<i32> {",
              "async fn run(arguments: Arguments) -> Result<i32> {\n    mcrf_direct_tun::configure(&arguments.tun_uds)?;")
    # Delegate BEFORE nix::RecvMsg::cmsgs: MSG_CTRUNC otherwise returns ENOBUFS
    # before raw descriptors can be owned and closed.
    tun=once(tun,"    pub async fn receive(&self, cancel: &CancellationToken) -> Result<File> {",
             "    pub async fn receive(&self, cancel: &CancellationToken) -> Result<File> {\n        if crate::mcrf_direct_tun::active() {\n            return crate::mcrf_direct_tun::receive(&self.listener, cancel).await;\n        }")
    # Exact upstream error path: strict direct-mode cannot continue after a bad FD.
    dispatcher=once(dispatcher,'                                    "[ОШИБКА] Не удалось получить TUN FD из UDS: {error}"\n                                );',
                    '                                    "[ОШИБКА] Не удалось получить TUN FD из UDS: {error}"\n                                );\n                                if crate::mcrf_direct_tun::active() {\n                                    crate::mcrf_direct_tun::guard();\n                                    receive_dispatcher.terminate(Termination::TunLost);\n                                    break;\n                                }')
    # Keep the pre-existing error/termination owner, adding a clone for strict errors.
    dispatcher=once(dispatcher,"            let receive_cancel = dispatcher.cancel.clone();",
                    "            let receive_cancel = dispatcher.cancel.clone();\n            let receive_dispatcher = dispatcher.clone();")
    # A second receive would reuse SESSION.started's 60s initial accept deadline
    # and kill an already adopted tunnel. Strict mode owns exactly one handoff.
    dispatcher=once(dispatcher,"                        if let Err(error) = fd_tx.send(file).await {",
                    "                        if crate::mcrf_direct_tun::active() {\n                            if crate::mcrf_direct_tun::handoff_once(receiver, file, &fd_tx, &receive_cancel).await.is_err() && !receive_cancel.is_cancelled() {\n                                crate::mcrf_direct_tun::guard();\n                                receive_dispatcher.terminate(Termination::TunLost);\n                            }\n                            return;\n                        }\n                        if let Err(error) = fd_tx.send(file).await {")
    dispatcher=once(dispatcher,"        let mut file = initial_file;",
                    "        let _mcrf_direct_guard = crate::mcrf_direct_tun::guard();\n        let mut file = initial_file;")
    dispatcher=once(dispatcher,"            let received = tokio::select! {",
                    "            if self.cancel.is_cancelled() || read_task.is_finished() || write_task.is_finished() || crate::mcrf_direct_tun::adopted().is_err() {\n                generation_cancel.cancel();\n                let _ = read_task.await;\n                let _ = write_task.await;\n                self.terminate(Termination::TunLost);\n                return;\n            }\n            let received = tokio::select! {")
    dispatcher=once(dispatcher,"                replacement = replacements.recv() => replacement,",
                    "                replacement = replacements.recv(), if !crate::mcrf_direct_tun::active() => replacement,")
    dispatcher=once(dispatcher,"            if self.cancel.is_cancelled() {\n                return;\n            }\n            let next = match received {",
                    "            if self.cancel.is_cancelled() {\n                return;\n            }\n            if crate::mcrf_direct_tun::active() {\n                self.terminate(Termination::TunLost);\n                return;\n            }\n            let next = match received {")
    return main,tun,dispatcher

def apply(root):
    root=pathlib.Path(root)
    paths=[root/name for name in ("main.rs","tun.rs","dispatcher.rs")]
    transformed=transform(*(p.read_text(encoding="utf-8") for p in paths))
    for path,text in zip(paths,transformed):path.write_text(text,encoding="utf-8",newline="\n")
    for name in ("mcrf_direct_tun.rs","mcrf_direct_tun_receive.rs"):
        shutil.copyfile(HERE/name,root/name)
