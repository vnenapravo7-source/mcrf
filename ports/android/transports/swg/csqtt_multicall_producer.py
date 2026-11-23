"""Fail-closed package gate; csqtt_multicall_build.py produces native proof only."""
import argparse
import json
import pathlib
from bs_engine_inputs import load, sha, require, bound

SOURCE = pathlib.Path(__file__).with_name("csqtt_multicall")
GENERATIONS = ("2.0", "2.1.5", "2.1.9", "2.5.1", "2.5.2/2.5.3")


def require_ported():
    # Release promotion remains closed. Candidate staging uses the separate
    # source-bound three-ABI verifier and never claims wire/performance acceptance.
    raise ValueError("Rust multi-call ACCEPTANCE_REQUIRED: Android runtime and performance checks remain")


def verified_direct_tun_unix(proof):
    external=proof.get("verifiedPostBuildEvidence")
    return external["directTunUnixTests"] if external else proof.get("directTunUnixTests",{})


def verified_native_routing_contract(proof):
    external=proof.get("verifiedPostBuildEvidence")
    return 1 if external and external["nativeRoutingUnixTests"] else proof.get("nativeRoutingContract",0)


def verify_compiled_proof(row, all_abis=False):
    path = pathlib.Path(row["path"]).absolute()
    require(sha(path) == row["sha256"], "Compiled proof receipt changed")
    proof = load(path)
    require("verifiedPostBuildEvidence" not in proof,"Compiled receipt must preserve the original evidence fields")
    require(proof["state"] == "COMPILED_MULTICALL_PROOF_NOT_PACKAGED" and proof["sourceStable"] is True and
            proof["packageReady"] is False and proof["producerSha256"] == sha(SOURCE.parent / "csqtt_multicall_build.py"), "Compiled producer/state mismatch")
    from prepare_csqtt_direct_tun import FILES
    direct_files=(*FILES,"direct_tun_harness.rs","test_prepare_csqtt_direct_tun.py")
    native=proof.get("nativeRoutingPrepared",False)
    require(type(native) is bool,"Invalid native routing preparation flag")
    post_build=row.get("postBuildEvidence")
    external=None
    if post_build is not None:
        from csqtt_post_build_evidence import verify as verify_post_build
        require(native,"External native routing evidence requires a native routing build")
        external=verify_post_build(post_build,row)
    level=2 if native else 1
    generations=["2.5.1","2.5.2","2.5.3"] if native else ["2.5.2","2.5.3"]
    require(proof.get("directTunFdContract")==level and proof.get("directTunGenerations")==generations and
            proof.get("directTunOverlays")=={name:sha(SOURCE.parent/name) for name in direct_files},"Direct TUN producer capability/source mismatch")
    if native:
        from csqtt_multicall_build import native_routing_inventory
        from native_routing_evidence import verify as verify_routing
        require(proof.get("nativeRoutingOverlays")==native_routing_inventory(),"Native routing source changed")
        if external is not None:
            require(external["nativeRoutingUnixTests"],"Post-build kernel and ordinary native service evidence required for packaging")
        else:
            evidence=proof.get("nativeRoutingUnixTests",{})
            require(proof.get("nativeRoutingContract")==1 and evidence and verify_routing(evidence["path"],path.parent)==evidence,
                    "Native routing source/runtime evidence missing or changed")
    else:require(proof.get("nativeRoutingContract",0)==0,"Unprepared native routing capability")
    boundary=proof.get("directTunBoundaryTests",{})
    from prepare_csqtt_gooby import source_inventory as gooby_inventory
    require(proof.get("goobyBuiltinOverlays") == gooby_inventory(), "Gooby producer source mismatch")
    gooby=proof.get("csqttGoobyBuiltinContract",0)
    require(type(gooby) is int and gooby in (0,1),"Invalid Gooby capability")
    if gooby:
        from gooby_evidence import verify as verify_gooby
        evidence=proof.get("goobyAndroidTests",{})
        require(evidence and verify_gooby(evidence["path"],path.parent)==evidence,
                "Gooby native execution proof changed")
    require(boundary.get("passed")==3 and boundary.get("providerTraffic") is False and
            boundary.get("logSha256")==sha(path.parent/"direct-tun-boundary-tests.log"),"Direct TUN entry tests missing")
    require(proof.get("vkBackoffOverlays") == {name: sha(SOURCE.parent / name) for name in
            ("prepare_csqtt_vk_backoff.py", "mcrf_vk_backoff.rs", "prepare_csqtt_flow_harness.py", "vk_backoff_harness/Cargo.toml", "vk_backoff_harness/lib.rs", "vk_backoff_harness/auth.rs.in", "vk_backoff_harness/worker.rs.in", "vk_backoff_harness/bootstrap.rs.in", "vk_backoff_harness/flow.rs.in")}, "VK backoff overlay proof mismatch")
    admission=proof.get("vkBackoffAdmission",{})
    require(admission.get("passed",0)>=22 and admission.get("flowCases")==6 and admission.get("providerTraffic") is False and
            admission.get("logSha256")==sha(path.parent / "admission-tests.log") and
            admission.get("extractionSha256")==sha(path.parent / "admission-tests/extracted-flow-source.json") and
            admission.get("lockSha256")==sha(path.parent / "admission-tests/Cargo.lock"),"Admission source/test proof missing")
    require(set(proof["inputs"]) == {"g20", "g215", "g219", "g251", "g253"}, "Five distinct adapters required")
    from csqtt_multicall_build import checked_sources
    _, g253 = checked_sources("g253")
    require(proof["inputs"]["g253"].get("alignedSource")==g253["alignedSource"],
            "Exact g253 recovered source provenance required")
    require(sha(path.parent / "Cargo.lock") == proof["workspaceLockSha256"], "Resolved lock changed")
    targets={r["target"] for r in proof["artifacts"]}
    require(len(proof["artifacts"])==len(targets) and ({"x86_64","arm64"} if native else {"host","arm64"})<=targets, "CLI execution and arm64 proof required")
    if all_abis:
        require(targets==({"arm64","armeabi-v7a","x86_64"} if native else {"host","arm64","armeabi-v7a","x86_64"}) and proof["performanceStatus"]=="CANDIDATE_NOT_MEASURED", "All ABI candidate proof required")
        from prepare_csqtt_direct_tun import verify_unix_proof
        unix=external["directTunUnixTests"] if external else proof.get("directTunUnixTests",{})
        require(unix.get("state")=="UNIX_DIRECT_TUN_TESTS_PASSED" and verify_unix_proof(unix["path"])==unix,
                "Cannot promote direct TUN capability without real Unix FD test proof")
    for name in ("lib.rs","main.rs"):
        require(sha(SOURCE/name)==sha(path.parent/name),"Compiled host source differs from canonical source")
    require((path.parent/"Cargo.toml").read_text(encoding="utf-8").startswith((SOURCE/"Cargo.toml").read_text(encoding="utf-8")),"Compiled Cargo profile differs from canonical source")
    for artifact in proof["artifacts"]:
        artifact_path = pathlib.Path(artifact["path"]).absolute()
        expected=path.parent / "artifacts" / ("mcrf-csqtt-transport.exe" if artifact["target"]=="host" else artifact.get("abi","arm64-v8a")+"/libcsqtt_transport.so")
        require(artifact_path==expected and sha(artifact_path) == artifact["sha256"] and
                artifact_path.stat().st_size == artifact["bytes"], "Compiled artifact changed")
        if artifact["target"]!="host":
            from bs_engine_inputs import elf
            elf(artifact_path,artifact["abi"])
            package = artifact_path.read_bytes()
            require(proof.get("privatePacketBridgeContract")==1 and b"MCRF_CSQTT_PRIVATE_UDP_KEY_V1" in package,
                    "Private UDP bridge capability missing")
            require(b"MCRF_DIRECT_TUN_FD_V1" in package,"Direct TUN compiled native marker missing")
            if native:
                require(b"MCRF_BS_OWNER_ISOLATION_READY " in package,"Direct TUN owner admission marker missing")
    for source in proof["generated"]:
        if source["path"] not in ("Cargo.lock","admission-tests/Cargo.lock"):
            bound(source,path.parent)
    for generation,source in proof["inputs"].items():
        if generation!="g219":
            for original in source["inventory"]:
                bound(original,pathlib.Path(source["path"]))
        else:
            for overlay,digest in source["overlays"].items():
                require(sha(overlay)==digest,"Legacy Rust overlay changed")
    host = load(path.parent / "host-proof.json")
    require(host.get("directTunFdContract")==level and host.get("directTunCapability")==
            {"marker":"MCRF_DIRECT_TUN_FD_V1","version":level,"generations":generations,"fdAdoptionRequired":True},"Compiled direct TUN CLI capability missing")
    cli_target="host"
    if native:
        from native_routing_cli_evidence import verify_fixture
        cli=proof.get("nativeRoutingCliTests",{})
        cli_target=verify_fixture(cli)
    executable=next(r["sha256"] for r in proof["artifacts"] if r["target"]==cli_target)
    if native:
        require(host.get("platform")=="android" and cli.get("platform")=="android" and cli.get("binaryBefore")==cli.get("binaryAfter")==executable and pathlib.Path(cli.get("path","")).absolute()==path.parent/"host-proof.json" and cli.get("sha256")==sha(path.parent/"host-proof.json"),"Actual owned Android CLI evidence missing")
    require(sha(path.parent / "host-proof.json") == proof["hostProofSha256"] and
            host["state"] == "CLI_RUNTIME_PARALLEL_PASS" and host["providerTraffic"] is False and
            host["binarySha256"] == executable and len(host["cases"]) >= 27, "Real CLI runtime proof required")
    return {**proof, "verifiedPostBuildEvidence":external} if external else proof


def prepare(output):
    contract = load(SOURCE / "contract.json")
    require(contract["generations"] == list(GENERATIONS), "Unexpected multi-call generations")
    compiled = contract["state"] == "COMPILED_PROOF_NOT_PACKAGED"
    if compiled:
        require(contract["implementedAdapters"] == list(GENERATIONS), "Adapter contract changed")
        verify_compiled_proof(contract["proof"])
    else:
        require(contract["state"] == "PORTING_REQUIRED" and not contract["implementedAdapters"], "Unexpected multi-call contract")
    output = pathlib.Path(output).absolute()
    require(not output.exists(), "Fresh multi-call plan directory required")
    receipt = {"state": "ACCEPTANCE_REQUIRED" if compiled else "PORTING_REQUIRED", "packageReady": False, "artifacts": [],
               "producerSha256": sha(__file__), "sources": [
                   {"path": p.name, "sha256": sha(p)} for p in sorted(SOURCE.iterdir()) if p.is_file()],
               "remaining": contract["requiredEvidence"] if compiled else list(GENERATIONS)}
    output.mkdir(parents=True)
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan")
    parser.add_argument("--publish-stage", action="store_true")
    args = parser.parse_args()
    if args.publish_stage:
        require_ported()
    elif args.plan:
        print(json.dumps(prepare(args.plan)))
    else:
        parser.error("--plan or --publish-stage required")
