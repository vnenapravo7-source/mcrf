"""Source-bound candidate gate for two-file BS native payload. Never compiles."""
import json
import pathlib
import itertools
import bs_engine_inputs as bs
from csqtt_multicall_producer import verify_compiled_proof, verified_direct_tun_unix, verified_native_routing_contract

NATIVE_MAIN={
    "ObservedRouteMetadata.kt","BsNativeRoutedFlow.kt","RuAppTunnelNotice.kt",
    "CsqttRoutingPrecedence.kt","CsqttNativeEvents.kt","ServerRoutingMode.kt",
    "BsNativeContract.kt","BsNativeCertificates.kt","BsNativeDnsRpc.kt","BsNativeProfilePreparation.kt",
    "BsNativeRoutingSnapshot.kt","BsNativeRuleSetFiles.kt","BsNativeSessionLaunch.kt","BsNativeTunPlan.kt",
    "BsNativeWorkerBackend.kt","BsRuleSetBinary.kt","ClientPrimaryWorker.kt","ClientPrimaryWorkerService.kt",
    "ClientPrimaryHostPlatform.kt","ClientPrimaryPlatformBinder.kt","McrfVpnService.kt","MainActivity.kt",
}

CANONICAL = {
    "transports/swg/prepare_csqtt_gooby.py", "transports/swg/mcrf_gooby.rs", "transports/swg/mcrf_gooby_stream_tests.rs", "transports/swg/gooby_evidence.py",
    "transports/swg/csqtt25/profile_gooby_test.go",
    "app/src/main/kotlin/org/mcrf/client/CsqttGooby.kt", "app/src/test/kotlin/org/mcrf/client/CsqttGoobyTest.kt",
    "app/src/main/kotlin/org/mcrf/client/ConnectionEditor.kt", "app/src/main/kotlin/org/mcrf/client/SwgProfile.kt",
    "app/src/main/kotlin/org/mcrf/client/CsqttGenerations.kt", "app/src/main/kotlin/org/mcrf/client/CsqttDirectTunSession.kt",
    "transports/swg/bs_direct_tun_pair.py", "tests/test_bs_direct_tun_pair.py",
    "transports/swg/prepare_csqtt_direct_tun.py", "transports/swg/mcrf_direct_tun.rs",
    "transports/swg/mcrf_direct_tun_receive.rs",
    "transports/swg/direct_tun_harness.rs", "transports/swg/test_prepare_csqtt_direct_tun.py",
    "app/src/main/kotlin/org/mcrf/client/BsDirectTunContract.kt", "app/src/test/kotlin/org/mcrf/client/BsDirectTunContractTest.kt",
    "transports/swg/bs_engine_inputs.py", "transports/swg/bs_multicall_inputs.py",
    "transports/swg/csqtt_multicall_build.py", "transports/swg/csqtt_multicall_producer.py",
    "transports/swg/prepare_csqtt_vk_backoff.py", "transports/swg/mcrf_vk_backoff.rs", "transports/swg/test_prepare_csqtt_vk_backoff.py",
    "transports/swg/vk_backoff_harness/Cargo.toml", "transports/swg/vk_backoff_harness/lib.rs",
    "transports/swg/prepare_csqtt_flow_harness.py", "transports/swg/vk_backoff_harness/auth.rs.in",
    "transports/swg/vk_backoff_harness/worker.rs.in", "transports/swg/vk_backoff_harness/bootstrap.rs.in", "transports/swg/vk_backoff_harness/flow.rs.in",
    "transports/swg/mcrf_child_failure.go", "transports/swg/mcrf_child_failure_test.go",
    "transports/swg/mcrf_child_packet_output_test.go",
    "transports/swg/csqtt25/profile.go", "transports/swg/csqtt25/profile_test.go",
    "app/src/main/kotlin/org/mcrf/client/CarrierStreamDefaults.kt", "app/src/main/kotlin/org/mcrf/client/Csqtt25Profile.kt",
    "transports/swg/csqtt_multicall/lib.rs", "transports/swg/csqtt_multicall/main.rs", "transports/swg/csqtt_multicall/Cargo.toml",
    "transports/swg/csqtt_multicall/native_router_entry.rs",
    "transports/swg/csqtt_multicall/contract.json",
    "transports/swg/prepare_bs_wrapper.py", "transports/swg/mcrf_bs_transport.go", "transports/swg/mcrf_bs_transport_test.go",
    "transports/swg/mcrf_underlay.go", "transports/swg/mcrf_underlay_android.go",
    "transports/swg/mcrf_underlay_test.go", "transports/swg/mcrf_underlay_android_test.go",
    "transports/swg/bs_multicall_runtime.go.in", "transports/swg/Build-BsWrapper.ps1", "transports/swg/Invoke-BsWrapperHost.ps1",
    "maintenance/freeze_bs_inputs.py", "maintenance/Package-BsEngine.ps1", "engines/build.gradle.kts", "engines/EngineUpdateBsManifest.xml",
    "app/src/main/kotlin/org/mcrf/client/BsEngineContract.kt", "app/src/main/kotlin/org/mcrf/client/BsEnginePackage.kt",
    "app/src/main/kotlin/org/mcrf/client/NativeCarrier.kt", "app/src/main/kotlin/org/mcrf/client/EngineModules.kt",
    "app/src/main/kotlin/org/mcrf/client/CarrierOptionContract.kt", "app/src/test/kotlin/org/mcrf/client/CarrierOptionContractTest.kt",
    "engines/src/main/AndroidManifest.xml",
    "app/src/main/kotlin/org/mcrf/client/VkCredentialLimit.kt", "app/src/main/kotlin/org/mcrf/client/VkCredentialBackoffStore.kt",
    "app/src/main/kotlin/org/mcrf/client/VkRelayRuntime.kt", "app/src/main/kotlin/org/mcrf/client/VkRelayAccount.kt",
    "app/src/main/kotlin/org/mcrf/client/VkTurnSubnets.kt", "app/src/main/kotlin/org/mcrf/client/VkTurnSubnetsUi.kt",
    "app/src/main/kotlin/org/mcrf/client/AndroidDeployBackupSettings.kt", "app/src/main/kotlin/org/mcrf/client/AndroidDeployUi.kt",
    "app/src/main/kotlin/org/mcrf/client/BsEngineMigration.kt",
    "maintenance/Test-BsMulticall.ps1", "app/src/test/kotlin/org/mcrf/client/BsEngineContractTest.kt",
    "tests/test_bs_multicall_integration.py", "tests/test_csqtt_multicall_build.py",
    "transports/swg/original_packet_evidence.py",
    "transports/swg/run_original_packet_physical.py",
    "transports/swg/native-packet/mcrf_original_packet_bridge_unix.go",
    "transports/swg/native-packet/mcrf_original_packet_bridge_other.go",
    "transports/swg/native-packet/mcrf_original_packet_bridge_unix_test.go",
    "transports/swg/native-packet/mcrf_authenticated_udp.go",
    "transports/swg/native-packet/mcrf_authenticated_udp_test.go",
    "transports/swg/native-packet/mcrf_private_udp.rs",
    "transports/swg/native-packet/mcrf_private_udp_dispatcher_test.rs.in",
    "transports/swg/private_udp_physical_evidence.py",
    "transports/swg/mcrf_socks_udp_source_test.go",
    "transports/socks-owner/overlay.py",
    "transports/socks-owner/worker_test.go.txt",
    "transports/swg/prepare_csqtt_private_udp.py",
    "transports/swg/native-packet/mcrf_packet_port_unix.go",
    "transports/swg/native-packet/mcrf_packet_port_unix_test.go",
}
from prepare_csqtt_gooby import source_inventory as gooby_inventory
CANONICAL |= {"transports/swg/"+name for name in gooby_inventory()}
CANONICAL |= {"app/src/main/kotlin/org/mcrf/client/"+name for name in NATIVE_MAIN}
CANONICAL |= {"transports/swg/"+name for name in ("prepare_csqtt_routing.py","native_routing_evidence.py","native_routing_cli_evidence.py")}

def canonical_sources(rust):
    names=set(CANONICAL)
    external=rust.get("postBuildEvidence")
    if external is not None:
        names.add("transports/swg/csqtt_post_build_evidence.py")
        routing=bs.load(bs.bound(external)).get("nativeRouting")
        if routing and bs.load(bs.bound(routing)).get("scope")=="owned-physical-arm64":
            names.add("transports/swg/native_routing_physical_evidence.py")
    return names

GOOBY_TESTS = {
    "Test25GoobyPresetsReachNativeAndDisabledKeepsOriginalArgv",
    "Test25GoobyPrivateJsonRejectsCoercionsNullAndUnknownModes",
}

def gooby_contract(gate, wrapper, rust):
    """An enabled UI option requires BOTH the wrapper and executed native proof."""
    key="csqttGoobyBuiltinContract"
    values=[source.get(key,0) for source in (gate,wrapper,rust)]
    bs.require(all(type(value) is int and value in (0,1) for value in values)
               and len(set(values))==1,"Gooby native/wrapper capability mismatch")
    if values[0]:
        bs.require(type(gate.get("versionCode")) is int and gate["versionCode"]>=10,
                   "Gooby requires a new BS package version")
    return values[0]

SRTP_TESTS = {
    "Test25OmittedSRTPRetainsNativeAutoAndOldProfileShape",
    "Test252253ExplicitSRTPReachesCLIWithoutChangingOtherOptions",
    "Test25ExplicitInvalidSRTPJSONCannotBecomeAuto",
    "Test25DirectLaunchAlsoRejectsUnsupportedSRTP",
}


def srtp_mode_contract(gate, wrapper):
    """Absent legacy feature stays zero; neither version nor Rust CLI alone grants it."""
    advertised = gate.get("csqtt25SrtpModeContract", 0)
    compiled = wrapper.get("csqtt25SrtpModeContract", 0)
    bs.require(type(advertised) is int and advertised in (0, 1) and
               type(compiled) is int and advertised == compiled, "SRTP wrapper capability mismatch")
    if advertised:
        bs.require(type(gate.get("versionCode")) is int and gate["versionCode"] >= 5,
                   "SRTP wrapper requires a new BS package version, not installed BS.4")
    return advertised


def direct_tun_contract(gate,rust):
    advertised=gate.get("directTunFdContract",0)
    compiled=rust.get("directTunFdContract",0)
    bs.require(type(advertised) is int and advertised in (0,1,2) and type(compiled) is int and advertised==compiled,
               "Direct TUN requires exact compiled native capability")
    if advertised:
        bs.require(rust.get("directTunGenerations")==(["2.5.1","2.5.2","2.5.3"] if advertised==2 else ["2.5.2","2.5.3"]) and gate["versionCode"]>=(11 if advertised==2 else 6),
                   "Direct TUN requires a new BS package and supported native adapters")
        bs.require(verified_direct_tun_unix(rust).get("state")=="UNIX_DIRECT_TUN_TESTS_PASSED",
                   "Direct TUN capability cannot replace real Unix FD test evidence")
    native=gate.get("nativeRoutingContract",0);actual=verified_native_routing_contract(rust)
    bs.require(type(native) is int and native in (0,1) and type(actual) is int and native==actual and (native==1)==(advertised==2),"Native routing requires versioned FD and exact compiled runtime acceptance")
    return advertised


def original_packet_contract(gate,wrapper,root):
    advertised=gate.get("originalPacketContract",0)
    compiled=wrapper.get("originalPacketContract",0)
    bs.require(type(advertised) is int and advertised in (0,1) and
               type(compiled) is int and advertised==compiled,"Original packet wrapper capability mismatch")
    if advertised:
        bs.require(gate["versionCode"]>=8,"Original packet bridge requires a new BS package")
        evidence=root / "original-packet-evidence.json"
        bs.require(bs.sha(evidence)==wrapper["originalPacketEvidenceSha256"],"Android bridge evidence changed")
        from original_packet_evidence import verify
        verify(evidence,wrapper["preparedSourceSha256"])
    return advertised


def notices(roots):
    result={}
    for key,root in roots.items():
        root=pathlib.Path(root)
        candidates=(root / "licenses").rglob("*") if key=="wrapper" else itertools.chain((root / "adapters").rglob("*"),(root / "gooby_builtin").rglob("*"))
        rows=[]
        for p in sorted(candidates):
            name=p.name.upper()
            if p.is_file() and (key=="wrapper" or name.split(".")[0] in ("LICENSE","LICENCE","NOTICE","COPYING") or name.startswith(("LICENSE-","NOTICE-"))):
                rel=p.relative_to(root).as_posix()
                bs.regular(p)
                rows.append({"location":"stage","path":rel,"sha256":bs.sha(p)})
        bs.require(rows,"Missing native notices: "+key)
        result[key]=rows
    return result


def inspect(gate):
    bs.require(gate["schema"]==3 and gate["state"]=="BS_SOURCE_BOUND_INPUTS_READY" and gate["layout"]==bs.LAYOUT and
               gate["package"]=="org.mcrf.engines.bs" and gate["logicalEngines"]==list(bs.KINDS),"Wrong multi-call gate")
    bs.require(type(gate["versionCode"]) is int and 1<=gate["versionCode"]<=2100000000,"Invalid BS version")
    bs.require(gate["performanceStatus"]=="CANDIDATE_NOT_MEASURED" and gate["releaseAccepted"] is False,"Unmeasured profile must remain candidate")
    bs.require(set(gate["inputs"])=={"wrapper","rust"},"Exact multicall inputs required; WDTT is standalone")
    rust_row=gate["inputs"]["rust"]
    required_sources=canonical_sources(rust_row)
    rows=gate["canonicalSources"]
    bs.require(len(rows)==len(required_sources) and {r["path"] for r in rows}==required_sources,"Missing/duplicate canonical inputs")
    for row in rows: bs.bound(row,bs.ROOT)
    rust=verify_compiled_proof(rust_row,all_abis=True)
    from private_udp_physical_evidence import verify_receipt as verify_private_udp
    verify_private_udp(bs.bound(gate["privateUdpEvidence"]),bs.bound(rust_row).parent)
    direct_tun_contract(gate,rust)
    wrapper_path=bs.bound(gate["inputs"]["wrapper"])
    wrapper=bs.load(wrapper_path)
    srtp_contract=srtp_mode_contract(gate,wrapper)
    gooby=gooby_contract(gate,wrapper,rust)
    root=wrapper_path.parent
    original_packet=original_packet_contract(gate,wrapper,root)
    bs.require(wrapper["state"]=="COMPILED_NOT_EXECUTED" and wrapper["sourceStable"] is True and
               wrapper["bsModeContract"]==3 and wrapper["kind"]=="csqtt" and
               all(wrapper[k]==1 for k in ("bsTransportContract","processGroupContract","accountOptionsContract","turnHashPoolContract")),"Fresh common wrapper required")
    bs.require(wrapper["producerSha256"]==bs.sha(bs.ROOT / "transports/swg/Build-BsWrapper.ps1"),"Wrapper compiler changed")
    prepared_path=root / "bs-wrapper-source.json"
    bs.require(bs.sha(prepared_path)==wrapper["preparedSourceSha256"],"Wrapper proof changed")
    prepared=bs.load(prepared_path)
    bs.require(all(type(row.get("privatePacketBridgeContract")) is int and row["privatePacketBridgeContract"]==1
                   for row in (gate,wrapper,prepared,rust)), "Authenticated CSQTT packet bridge required for both binaries")
    expected_socks={kind:1 for kind in bs.KINDS}
    bs.require(all(record.get("socksUdpSource")==expected_socks for record in (gate,wrapper,prepared)),
               "All common-wrapper roles require private SOCKS UDP source binding")
    prepared_post = prepared["rust"].get("postBuildEvidence")
    gate_post = rust_row.get("postBuildEvidence")
    same_post = (
        prepared_post is None and gate_post is None
        or prepared_post is not None and gate_post is not None
        and bs.bound(prepared_post).resolve() == bs.bound(gate_post).resolve()
        and prepared_post["sha256"] == gate_post["sha256"]
    )
    bs.require(prepared["modeContract"]==3 and bs.bound(prepared["rust"]).absolute()==bs.bound(rust_row).absolute() and
               prepared["rust"]["sha256"]==rust_row["sha256"] and same_post,
               "Wrapper uses another Rust build or evidence")
    if gate.get("directTunFdContract",0) >= 1:
        bs.require(prepared.get("pinOnlyRebuild") is not None or original_packet,
                   "Direct TUN requires audited pin-only or Android-tested original bridge source")
    bs.verify_multimode_preparation(prepared)
    bs.require(bs.sha(root / "source-manifest.json")==wrapper["sourceManifestSha256"] and
               bs.load(root / "source-manifest.json")==prepared["sources"],"Compiled wrapper source changed")
    baseline_path=pathlib.Path(wrapper["baselineReceipt"])
    bs.require(bs.sha(baseline_path)==wrapper["baselineReceiptSha256"]==bs.sha(root / "baseline-receipt.json"),"Wrapper baseline changed")
    baseline=bs.load(baseline_path)
    bs.require(baseline["sourceStable"] is True and baseline["kind"]=="csqtt" and
               baseline["sourceManifestSha256"]==prepared["baselineManifestSha256"],"Wrong wrapper baseline")
    bs.require(bs.sha(root / "host-evidence.json")==wrapper["hostEvidenceSha256"],"Host proof changed")
    host=bs.load(root / "host-evidence.json")
    bs.require(host["state"]=="HOST_CHECKS_PASSED" and host["preparedSourceSha256"]==wrapper["preparedSourceSha256"] and
               host["producerSha256"]==bs.sha(bs.ROOT / "transports/swg/Invoke-BsWrapperHost.ps1") and
               bs.sha(root / "host-tests.jsonl")==host["logSha256"],"Wrong wrapper host evidence")
    events=[json.loads(line) for line in bs.regular(root / "host-tests.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    required=set(bs.MODE_TESTS)|{"TestBSRustModeIsolation","TestBSRustChildEnv","TestBSModernMismatch","TestBSRustParallelProcesses","TestMcrfChildFailureForwardsOnlyBoundedTypedVKLimits"}
    required |= {"TestPrivatePacketUDPGoldenVectors","TestPrivatePacketUDPRejectsForgeryWrongKeyDirectionAndReplay",
                 "TestPrivateSOCKSUDPBoundSourceRejectsFirstSender", "TestMcrfSocksUdpSourcePinAndControlEOF",
                 "TestMcrfChildPrivatePacketKeyNeverCrossesOutputBoundary"}
    if srtp_contract: required |= SRTP_TESTS
    if gooby: required |= GOOBY_TESTS
    bs.require(not any(e.get("Action") in ("fail","skip") for e in events) and required<={e.get("Test") for e in events if e.get("Action")=="pass"},"Missing/failed common host tests")
    bs.native_inventory(wrapper,"wrapper")
    outputs={}
    for abi in bs.ABIS:
        native=root / abi / "libqwdtt.so"
        row=next(r for r in wrapper["artifacts"] if r["abi"]==abi)
        bs.require(bs.sha(native)==row["sha256"] and native.stat().st_size==row["bytes"],"Common wrapper native changed")
        bs.elf(native,abi)
        data=native.read_bytes()
        bs.require(b"MCRF_CSQTT_PRIVATE_UDP_KEY_V1" in data and
                   all(("MCRF_SOCKS_UDP_SOURCE_V1_"+kind).encode("ascii") in data for kind in expected_socks),
                   "Authenticated wrapper marker missing")
        transport=next(r for r in rust["artifacts"] if r.get("abi")==abi)
        outputs[abi]={"libbs.so":native,"libcsqtt_transport.so":bs.regular(transport["path"])}
    roots={"wrapper":root,"rust":pathlib.Path(rust_row["path"]).parent}
    bs.require(gate["notices"]==notices(roots),"Frozen notices changed")
    return gate,roots,outputs


def freeze(stages, output, version_code):
    bs.require(set(stages) in ({"wrapper","rust","rust-private-udp"},
                              {"wrapper","rust","rust-post-build","rust-private-udp"}),
               "Exact multicall inputs and paired private UDP proof required")
    output=pathlib.Path(output).absolute()
    pending=output.with_name(output.name+".pending")
    bs.require(not output.exists() and not pending.exists(),"Fresh gate required")
    receipts = {key: pathlib.Path(value).absolute() for key, value in stages.items() if key in ("wrapper","rust")}
    receipts = {key: path / "receipt.json" if path.is_dir() else path for key, path in receipts.items()}
    inputs = {key: {"path": str(path), "sha256": bs.sha(path)} for key, path in receipts.items()}
    if stages.get("rust-post-build"):
        external=pathlib.Path(stages["rust-post-build"]).absolute()
        inputs["rust"]["postBuildEvidence"]={"path":str(external),"sha256":bs.sha(external)}
    roots = {key: path.parent for key, path in receipts.items()}
    gate={"schema":3,"layout":bs.LAYOUT,"state":"BS_SOURCE_BOUND_INPUTS_READY","package":"org.mcrf.engines.bs",
          "versionCode":version_code,"logicalEngines":list(bs.KINDS),"performanceStatus":"CANDIDATE_NOT_MEASURED","releaseAccepted":False,
          "inputs":inputs,"canonicalSources":[{"path":p,"sha256":bs.sha(bs.ROOT/p)} for p in sorted(canonical_sources(inputs["rust"]))],"notices":notices(roots)}
    gate["csqtt25SrtpModeContract"]=bs.load(receipts["wrapper"]).get("csqtt25SrtpModeContract",0)
    gate["directTunFdContract"]=bs.load(receipts["rust"]).get("directTunFdContract",0)
    gate["nativeRoutingContract"]=1 if stages.get("rust-post-build") else bs.load(receipts["rust"]).get("nativeRoutingContract",0)
    gate["originalPacketContract"]=bs.load(receipts["wrapper"]).get("originalPacketContract",0)
    gate["privatePacketBridgeContract"]=bs.load(receipts["wrapper"]).get("privatePacketBridgeContract",0)
    gate["socksUdpSource"]=bs.load(receipts["wrapper"]).get("socksUdpSource",{})
    paired=pathlib.Path(stages["rust-private-udp"]).absolute()
    gate["privateUdpEvidence"]={"path":str(paired),"sha256":bs.sha(paired)}
    gate["csqttGoobyBuiltinContract"]=bs.load(receipts["wrapper"]).get("csqttGoobyBuiltinContract",0)
    output.parent.mkdir(parents=True,exist_ok=True)
    pending.write_text(json.dumps(gate,indent=2)+"\n",encoding="utf-8")
    digest=bs.sha(pending)
    inspect(gate)
    pending.rename(output)
    print(json.dumps({"gate":str(output),"sha256":digest,"layout":bs.LAYOUT,"performanceStatus":gate["performanceStatus"]}))
