"""Verify physical ARM64 native smoke and source-bound complete evidence."""
import argparse
import json
import pathlib
import re
import zipfile
import hashlib

from bs_engine_inputs import bound, load, require, sha
from native_routing_evidence import GENERATIONS, frozen, source_matches
from native_routing_physical_build import ABIS, BIN, bootstrap_entry, elf, verify_stage
from prepare_csqtt_direct_tun import checked_row
from run_direct_tun_android_proof import PHONE

GROUPS = {
    "tcp-policy": "mcrf_bs_tcp_policy::tests::",
    "udp-policy": "mcrf_bs_udp_policy::tests::",
    "dns-transport": "mcrf_bs_dns_transport::tests::",
    "udp-transport": "mcrf_bs_udp_transport::tests::",
    "native-session": "mcrf_bs_session::tests::",
    "forced-tunnel-health": "mcrf_bs_health::tests::",
    "certificate-roots": "mcrf_bs_certificates::tests::",
    "proxy-udp": "mcrf_bs_proxy_udp::tests::",
    "proxy-backend": "mcrf_bs_proxy_backend::tests::",
    "proxy-protocol": "mcrf_bs_proxy_protocol::tests::",
    "proxy-listener": "mcrf_bs_proxy_listener::tests::",
    "proxy-stream": "mcrf_bs_proxy_stream::tests::",
    "dns-resolver": "mcrf_bs_dns_resolver::tests::",
    "startup-context": "mcrf_bs_bootstrap::tests::",
    "underlay-context": "mcrf_bs_network::tests::",
    "dispatcher-runtime": "dispatcher::mcrf_native_runtime_tests::",
    "owner-channel": "mcrf_bs_owner::tests::",
}
# Native service registration is a process-wide OnceLock. Run each selected
# association test in a fresh process, as required by the original tests.
# Shell SELinux does not permit creating FIFO/hardlink fixtures here.
OWNER_CASES = {
    "owner-only-startup": "owner_only_startup_policy_parses_and_preserves_tunnel_fallback",
    "owner-no-producer": "metadata_without_a_native_producer_is_rejected_before_startup",
    "owner-service-refresh": "native_owned_service_survives_tracker_idle_and_rechecks_owner_without_reclassifying_into_itself",
    "owner-tcp-reuse": "owner_cached_tcp_uses_no_more_lookups_until_a_new_syn_reuses_the_tuple",
    "owner-ip-only": "owner_channel_presence_does_not_query_every_udp_packet_for_an_ip_only_policy",
    "owner-denied": "owner_denial_never_becomes_a_route_or_a_permanently_cached_missing_owner",
    "owner-service-uid": "owner_service_candidate_is_not_authorization_without_the_correct_uid",
    "owner-service-tcp": "owner_service_tcp_pin_survives_socket_close_but_not_a_reused_syn",
    "owner-cancellation": "owner_slow_lookup_does_not_hold_the_cached_flow_policy_lock_and_cancellation_blocks",
    "owner-udp-rebind": "owner_udp_rebind_does_not_reuse_the_previous_apps_cached_verdict",
    "private-bundle": "private_bundle_uses_its_exact_name_digest_bounds_modes_and_no_symlink",
    "private-policy-mode": "private_policy_digest_and_private_modes_are_checked",
    "private-policy-symlink": "private_policy_symlinks_are_rejected",
}
GROUPS.update({group: "mcrf_bs_routing::tests::" + case for group, case in OWNER_CASES.items()})
MAIN_PACKAGE = "org.mcrf.client"
TEST_PACKAGE = "org.mcrf.client.test"
MODULE_PACKAGE = "org.mcrf.engines.bs"
HELPER_PACKAGE = "org.mcrf.p1owner"
PEER_PACKAGE = "org.mcrf.p1owner.peer"
SRS_SHA256 = "7ef7d515db575537a90fd2645ee79619082600009d6b19db827e1433158bc203"
RUNNERS = {
    "kernel": TEST_PACKAGE + "/" + MAIN_PACKAGE + ".BsNativePhysicalBootstrapInstrumentation",
    "service": TEST_PACKAGE + "/" + MAIN_PACKAGE + ".BsNativePhysicalServiceInstrumentation",
    "direct-owner": TEST_PACKAGE + "/" + MAIN_PACKAGE + ".BsNativePhysicalDirectOwnerInstrumentation",
    "router-entry": TEST_PACKAGE + "/" + MAIN_PACKAGE + ".BsNativePhysicalRouterEntryInstrumentation",
}


def ordered_pair_pin(helper_sha, peer_sha):
    return hashlib.sha256(f"p1-shared-scope-v1\n{helper_sha}\n{peer_sha}\n".encode("ascii")).hexdigest()


def router_artifact(compilation, stage):
    stage = pathlib.Path(stage).resolve(strict=True)
    require(pathlib.Path(compilation).resolve(strict=True) == stage / "receipt.json", "Wrong root router production stage")
    proof, sources = verify_stage(stage)
    source_names = {row["path"] for row in sources}
    require({"native_router_entry.rs", "adapters/g253/rust-client/main.rs", "adapters/g253/rust-client/dispatcher.rs",
             "adapters/g253/rust-client/mcrf_bs_owner.rs", "adapters/g253/rust-client/mcrf_bs_bootstrap.rs",
             "adapters/g253/rust-client/mcrf_bs_tcp_dispatch.rs", "adapters/g253/rust-client/mcrf_bs_udp_dispatch.rs"}
            <= source_names, "Root router dispatch and owner sources missing")
    require("mcrf_adapter_g253::mcrf_native_router_run();" in
            (stage / "native_router_entry.rs").read_text(encoding="utf-8"), "Root router adapter binding changed")
    rows = proof["routerArtifacts"]
    require({row["abi"] for row in rows} == set(ABIS) and len(rows) == len(ABIS), "Three production root router ABIs required")
    for row in rows:
        binary = checked_row({key: row[key] for key in ("path", "sha256", "bytes")})
        require(binary == stage / "router-artifacts" / row["abi"] / "libmcrf_router.so", "Production router ELF moved")
        elf(binary, row["abi"])
    return pathlib.Path(next(row["path"] for row in rows if row["abi"] == "arm64-v8a"))


def check_scope(serial, fixture):
    require(serial == PHONE and isinstance(fixture, dict) and set(fixture) == {"kind", "abi", "sdk", "shellUid"} and
            fixture["kind"] == "physical" and fixture["abi"] == "arm64-v8a" and fixture["shellUid"] == 2000 and
            type(fixture["sdk"]) is int and fixture["sdk"] >= 29, "Wrong physical Android device scope")


def compiled(path, stage):
    stage = pathlib.Path(stage).resolve(strict=True)
    path = pathlib.Path(path).resolve(strict=True)
    receipt = load(path)
    require(receipt["schema"] == 1 and receipt["state"] == "NATIVE_PHYSICAL_FIXTURES_COMPILED_NOT_EXECUTED" and
            receipt["sourceStable"] is True and receipt["productionActivated"] is False and
            receipt["providerTraffic"] is False and
            receipt["producerSha256"] == sha(pathlib.Path(__file__).with_name("native_routing_physical_build.py")) and
            checked_row(receipt["seedReceipt"]) == stage / "receipt.json", "Unfrozen native fixture compilation")
    production, production_rows = verify_stage(stage)
    sources = {row["path"]: row for row in receipt["sources"]}
    require(len(sources) == len(receipt["sources"]), "Duplicate fixture source record")
    for row in production_rows:
        name = row["path"]
        if name.startswith("admission-tests/"):
            continue
        if name in {f"adapters/{key}/rust-client/{file}" for key in GENERATIONS.values()
                    for file in ("dispatcher.rs", "Cargo.toml")}:
            continue
        require(bound(sources[name], path.parent).read_bytes() == (stage / name).read_bytes(),
                "Unexpected fixture source replacement: " + name)
    for generation, key in GENERATIONS.items():
        source_matches(stage, {"source": path}, receipt, generation)
        name = f"adapters/{key}/rust-client/owned_native_udp_main.rs"
        fixture_entry = bound(sources[name], path.parent)
        original = stage / "adapters" / key / "rust-client" / "main.rs"
        require(fixture_entry.read_text(encoding="utf-8") == bootstrap_entry(original, generation), "Bootstrap fixture entry changed")
    require(sha(bound(sources["Cargo.lock"], path.parent)) == production["workspaceLockSha256"], "Workspace lock changed")
    for row in receipt["sources"]:
        bound(row, path.parent)
    tools = receipt["toolchain"]
    for tool, key in (("cargo", "cargoSha256"), ("rustc", "rustcSha256"), ("ndkClang", "ndkClangSha256")):
        require(sha(checked_row(tools[tool])) == production["toolchain"][key], "Unexpected native compile tool")
    binaries = {}
    for row in receipt["binaries"]:
        generation, abi, purpose = row["generation"], row["abi"], row["purpose"]
        identity = (generation, abi, purpose)
        require(identity not in binaries and generation in GENERATIONS and abi in ABIS and
                (purpose == "unit" or (abi, purpose) == ("arm64-v8a", "bootstrap")), "Unexpected binary receipt")
        prefix = ["build", "--bin", BIN] if purpose == "bootstrap" else ["test", "--lib", "--no-run"]
        key = GENERATIONS[generation]
        require(row["command"] == [str(checked_row(tools["cargo"])), *prefix, "--package", "mcrf-adapter-" + key,
                "--target", ABIS[abi][0], "--frozen", "--message-format=json"], "Unexpected compile command")
        events = [json.loads(line) for line in checked_row(row["cargoLog"]).read_text(encoding="utf-8").splitlines() if line]
        checked_row(row["cargoStderr"])
        target = BIN if purpose == "bootstrap" else "mcrf_adapter_" + key
        hits = [event for event in events if event.get("reason") == "compiler-artifact" and
                event["target"]["name"] == target and event.get("executable") and
                event["profile"]["test"] == (purpose == "unit")]
        require(len(hits) == 1 and any(e.get("reason") == "build-finished" and e.get("success") is True for e in events),
                "Successful Cargo ELF not observed")
        binary = checked_row({key: row[key] for key in ("path", "sha256", "bytes")})
        elf(binary, abi)
        binaries[identity] = binary
    require(set(binaries) == {(gen, abi, "unit") for gen in GENERATIONS for abi in ABIS} |
            {(gen, "arm64-v8a", "bootstrap") for gen in GENERATIONS}, "Both generations and all three ABIs are required")
    return binaries


def unit_names(log, name):
    text = log.read_text(encoding="utf-8")
    if name == "dispatcher-runtime":
        case = "test dispatcher::mcrf_native_runtime_tests::native_dispatcher_dns_origin_separation_and_shutdown_release_buffers ... "
        text = text.replace(case + "BS_NATIVE_PACKET_DROP Packet count=1\nok\n", case + "ok\n")
    items = re.findall(r"^test ([A-Za-z0-9_:]+) \.\.\. ok$", text, re.MULTILINE)
    require(items and len(items) == len(set(items)) and all(item.startswith(GROUPS[name]) for item in items) and
            re.search(rf"^test result: ok\. {len(items)} passed; 0 failed; 0 ignored;", text, re.MULTILINE) and
            not re.search(r"\b(?:FAILED|ignored,)", text), "Native test group failed or test selection changed: " + name)
    if name in OWNER_CASES:
        require(items == [GROUPS[name]], "Exact isolated native owner case required")
    return set(items)


def units(path, stage):
    data = load(path)
    check_scope(data["serial"], data["fixture"])
    require(data["state"] == "OWNED_PHYSICAL_ANDROID_NATIVE_REGRESSIONS_PASSED" and data["providerTraffic"] is False and
            data["productionActivated"] is False and data["cleanupComplete"] is True and
            data["producerSha256"] == sha(pathlib.Path(__file__).with_name("run_native_routing_physical_units.py")),
            "Scoped regression execution receipt required")
    binaries = compiled(checked_row(data["compilation"]), stage)
    require(set(data["generations"]) == set(GENERATIONS), "Both generations are required")
    totals = {}
    for gen, execution in data["generations"].items():
        binary = binaries[gen, "arm64-v8a", "unit"]
        identity = {"sha256": sha(binary), "bytes": binary.stat().st_size}
        require(execution["binaryBefore"] == execution["binaryAfter"] == identity and
                re.fullmatch(r"/data/local/tmp/mcrf-native-regression-[a-f0-9]{32}/tests", execution["remote"]) and
                set(execution["groups"]) == set(GROUPS), "ARM64 binary or test coverage changed")
        names = set()
        for group, row in execution["groups"].items():
            require(type(row["exitCode"]) is int and row["exitCode"] == 0 and row["command"] ==
                    [execution["remote"], GROUPS[group], "--test-threads=1"], "Unexpected Android test invocation")
            seen = unit_names(checked_row(row["log"]), group)
            require(not (names & seen) and row["passed"] == len(seen), "Duplicated native test case")
            names |= seen
        require(len(names) >= 81, "Incomplete native runtime regression suite")
        totals[gen] = len(names)
    return totals


def apk_digest(apk, name):
    with zipfile.ZipFile(apk) as archive, archive.open(name) as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def instrument_result(log, generation, purpose, pin, pair=None):
    text = log.read_text(encoding="utf-8")
    rows = re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z0-9]+)=(.*)$", text, re.MULTILINE)
    result = dict(rows)
    marker, target = {
        "kernel": ("physicalBootstrap", "ARM64_ORIGINAL_DISPATCHER_VPN_FD_OWNER_ACCEPTED"),
        "service": ("physicalService", "ARM64_MAIN_START_NATIVE_WORKER_TUN_OWNER_ACCEPTED"),
        "direct-owner": ("physicalDirectOwner", "ARM64_ORIGINAL_DIRECT_FD_OWNER_CAPTURE_ACCEPTED"),
        "router-entry": ("physicalRouterEntry", "ARM64_PRODUCTION_ROUTER_ENTRY_OWNER_NAMED_EGRESSES_ACCEPTED"),
    }[purpose]
    pin_key = "moduleSha256" if purpose == "service" else "fixtureSha256"
    require(len(rows) == len(result) and re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", text, re.MULTILINE) == ["-1"] and
            not any(key in result for key in ("failure", "cleanupFailure", "shortMsg")) and
            result.get("generation") == generation and result.get(marker) == target and result.get(pin_key) == pin and
            result.get("ownedCleanupAndProfileParity") == "true" and result.get("providerTraffic") == "false",
            "Android native instrumentation did not report verified success")
    if purpose in ("direct-owner", "router-entry"):
        require(result.get("pairSha256") == pair, "Native owner capture used a different installed pair")
    return result


def runtime(path, stage):
    data = load(path)
    check_scope(data["serial"], data["fixture"])
    require(data["state"] == "OWNED_PHYSICAL_ANDROID_NATIVE_KERNEL_OR_SERVICE_PASSED" and data["user"] == 0 and
            data["providerTraffic"] is False and data["productionActivated"] is False and
            data["producerSha256"] == sha(pathlib.Path(__file__).with_name("observe_native_routing_physical.py")),
            "Scoped native instrumentation receipt required")
    generation, purpose = data["generation"], data["purpose"]
    require(generation in GENERATIONS and purpose in RUNNERS, "Unknown native instrumentation scope")
    compilation = checked_row(data["compilation"])
    if purpose == "direct-owner":
        from build_physical_direct_owner_capture import verify
        native = verify(compilation, stage)[generation]
    elif purpose == "router-entry":
        require(generation == "2.5.3", "Production root router must dispatch the g253 adapter")
        native = router_artifact(compilation, stage)
    else:
        native = compiled(compilation, stage)[generation, "arm64-v8a", "bootstrap"]
    expected = {MAIN_PACKAGE, TEST_PACKAGE}
    if purpose == "service":
        expected.add(MODULE_PACKAGE)
    if purpose in ("direct-owner", "router-entry"):
        expected.update((HELPER_PACKAGE, PEER_PACKAGE))
    require(set(data["packages"]) == expected, "Incomplete APK identity")
    packages = {}
    for package, row in data["packages"].items():
        host = checked_row(row["artifact"])
        require(row["installedBefore"] == row["installedAfter"] == row["artifact"]["sha256"],
                "Installed fixture APK changed: " + package)
        packages[package] = host
    extra_args = []
    pair = None
    if purpose in ("kernel", "direct-owner"):
        digest = apk_digest(packages[TEST_PACKAGE], "lib/arm64-v8a/libmcrf_owned_native_udp_fixture.so")
        with zipfile.ZipFile(packages[TEST_PACKAGE]) as archive:
            metadata = json.loads(archive.read("assets/bs-owned-native-udp-fixture.json"))
        require(metadata.get("abi") == "arm64-v8a" and metadata.get("generation") == generation and
                metadata.get("bootstrap") is True and metadata.get("sha256") == sha(native) and
                metadata.get("bytes") == native.stat().st_size and metadata.get("srsSha256") == SRS_SHA256 and
                apk_digest(packages[TEST_PACKAGE], "assets/bs-owned-bootstrap-routes.srs") == SRS_SHA256,
                "Bootstrap fixture metadata or SRS changed")
        require(metadata.get("directOwnerCapture", False) is (purpose == "direct-owner"),
                "Dispatcher fixture path changed")
        pin, arg = digest, "fixtureSha256"
    elif purpose == "router-entry":
        digest = apk_digest(packages[MAIN_PACKAGE], "lib/arm64-v8a/libmcrf_router.so")
        pin, arg = digest, "fixtureSha256"
    else:
        digest = apk_digest(packages[MODULE_PACKAGE], "lib/arm64-v8a/libcsqtt_transport.so")
        require(apk_digest(packages[MODULE_PACKAGE], "lib/arm64-v8a/libbs.so") == digest,
                "BS test-only module contains a different bootstrap escape path")
        pin, arg = sha(packages[MODULE_PACKAGE]), "moduleSha256"
    if purpose in ("direct-owner", "router-entry"):
        pair = ordered_pair_pin(sha(packages[HELPER_PACKAGE]), sha(packages[PEER_PACKAGE]))
        extra_args.extend(("-e", "pairSha256", pair))
    require(digest == sha(native) and data["command"] ==
            ["am", "instrument", "--user", "0", "-w", "-r", "-e", "generation", generation, "-e", arg, pin, *extra_args, RUNNERS[purpose]],
            "Executed fixture or instrumentation command changed")
    instrument_result(checked_row(data["log"]), generation, purpose, pin, pair)
    return {"purpose": purpose, "generation": generation, "abi": "arm64-v8a", "path": str(pathlib.Path(path).absolute()),
            "sha256": sha(path), "providerTraffic": False, "performanceAccepted": False}


def verify(path, stage):
    path = pathlib.Path(path).resolve(strict=True)
    evidence = load(path)
    require(set(evidence) == {"schema", "scope", "unitTests", "kernels", "services", "directOwners", "routerEntry"}
            and evidence["schema"] == 2 and evidence["scope"] == "owned-physical-arm64",
            "Complete versioned physical native evidence required")
    unit_counts = units(frozen(evidence["unitTests"]), stage)
    runtime_paths = [("router-entry", "2.5.3", frozen(evidence["routerEntry"]))]
    for key, purpose in (("kernels", "kernel"), ("services", "service"), ("directOwners", "direct-owner")):
        require(set(evidence[key]) == set(GENERATIONS), "Both actual physical native generations required: " + key)
        runtime_paths.extend((purpose, gen, frozen(row)) for gen, row in evidence[key].items())
    require(len({row[2] for row in runtime_paths}) == len(runtime_paths), "One fresh transcript per physical case required")
    main_apks = set()
    pairs = set()
    for purpose, generation, receipt in runtime_paths:
        actual = runtime(receipt, stage)
        require((actual["purpose"], actual["generation"]) == (purpose, generation), "Wrong physical smoke result")
        packages = load(receipt)["packages"]
        main_apks.add(packages[MAIN_PACKAGE]["artifact"]["sha256"])
        if purpose in ("direct-owner", "router-entry"):
            pairs.add(tuple(packages[package]["artifact"]["sha256"] for package in (HELPER_PACKAGE, PEER_PACKAGE)))
    require(len(main_apks) == len(pairs) == 1, "Use one frozen Main APK and owner helper pair across all physical cases")
    return {"path": str(path), "sha256": sha(path), "state": "OWNED_NATIVE_ROUTING_ACCEPTED",
            "scope": "owned-physical-arm64", "nativeIngressGenerations": ["2.5.1", "2.5.3"],
            "rootEntryGeneration": "2.5.3", "unitCounts": unit_counts, "providerTraffic": False,
            "performanceAccepted": False, "syntheticEgress": True}


def main():
    from native_routing_evidence import BUILD
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("stage", "output", "unit-tests", "router-entry", "kernel-g251", "kernel-g253", "service-g251",
                "service-g253", "owner-g251", "owner-g253"):
        parser.add_argument("--" + key, required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output).resolve()
    require(BUILD in output.parents and not output.exists(), "Fresh native evidence output required")

    def row(path):
        path = pathlib.Path(path).resolve(strict=True)
        return {"path": str(path), "sha256": sha(path)}

    evidence = {"schema": 2, "scope": "owned-physical-arm64", "unitTests": row(args.unit_tests),
                "kernels": {"2.5.1": row(args.kernel_g251), "2.5.3": row(args.kernel_g253)},
                "services": {"2.5.1": row(args.service_g251), "2.5.3": row(args.service_g253)},
                "directOwners": {"2.5.1": row(args.owner_g251), "2.5.3": row(args.owner_g253)},
                "routerEntry": row(args.router_entry)}
    pending = output.with_name(output.name + ".pending")
    with pending.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(evidence, stream, indent=2)
        stream.write("\n")
    try:
        verify(pending, args.stage)
        pending.rename(output)
        print(json.dumps(verify(output, args.stage)))
    finally:
        if pending.exists():
            pending.unlink()


if __name__ == "__main__":
    main()
