"""BS packaging from an explicitly pinned source-bound input gate. Never compiles.

No discovery of build/outputs APKs or loose .so files. The gate SHA is supplied by
the coordinator after freezing/reviewing the six existing native input receipts
and the new shared-wrapper receipt. Updating a binary requires a new gate.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import struct
import sys
import zlib
import zipfile

ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}
KINDS = ("csqtt", "csqtt20", "csqtt215", "csqtt25", "qwdtt")
SPLIT_LAYOUT = "split-rust-no-wdtt-v1"
LAYOUT = "rust-multicall-v1"
ROOT = pathlib.Path(__file__).resolve().parents[2]
MODE_TESTS = ("TestBSTransportDefaultPreserved", "TestBSTransportRejectsExternalPath",
              "TestBSModeRejectsMismatch", "TestBSModeParallelProcesses", "TestBSModeSupersetProfiles")


def require_layout(layout):
    require(layout in (LAYOUT,SPLIT_LAYOUT), "Unknown BS transport layout")


def verify_multimode_preparation(prepared, superset=None, duplicate=None):
    import prepare_bs_wrapper as adapter
    require(prepared.get("modeContract") in (2,3) and prepared.get("superset") and prepared.get("duplicate"),
            "Multi-mode source proof required")
    for key, pinned in (("superset", superset), ("duplicate", duplicate)):
        row = prepared[key]
        if pinned is not None:
            require(row["sha256"] == pinned["sha256"] and
                    regular(row["path"]) == regular(pinned["path"]), "Wrapper source receipt pairing changed")
    source, rows = adapter.selected_sources(pathlib.Path(prepared["baselineSource"]),
        prepared["baselineManifest"], prepared["baselineManifestSha256"], prepared["superset"], prepared["duplicate"])
    files = adapter.adapted_sources(source, rows, True, prepared.get("rust"))
    require(prepared.get("canonicalProfileOverlays")==adapter.modern_profile_overlays(),
            "Current modern profile source/test binding changed")
    expected = {r["path"]: r["sha256"] for r in prepared["sources"]}
    require(len(expected) == len(prepared["sources"]) and set(expected) == set(files), "Adapted source set changed")
    root = pathlib.Path(prepared["root"])
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and
              (p.suffix == ".go" or p.name in ("go.mod", "go.sum"))}
    require(actual == set(files), "Prepared source inventory changed")
    for name, content in files.items():
        require(hashlib.sha256(content).hexdigest() == expected[name] == sha(root / name), "Adapted source bytes changed")
    require(prepared["adapterSha256"] == sha(ROOT / "transports/swg/prepare_bs_wrapper.py") and
            prepared["helperSha256"] == sha(ROOT / "transports/swg/mcrf_bs_transport.go"), "Wrapper adapter changed")
    if prepared.get("pinOnlyRebuild") is not None:
        from bs_direct_tun_pair import verify_pairing
        verify_pairing(prepared, prepared.get("rust"))


def require(ok, message):
    if not ok:
        raise ValueError(message)


def regular(path):
    path = pathlib.Path(path).absolute()
    require(not any(p.is_symlink() or (hasattr(p, "is_junction") and p.is_junction()) for p in (path, *path.parents)), "Link/junction input")
    require(path.is_file(), f"Missing input: {path}")
    return path


def sha(path):
    with regular(path).open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
        return digest.hexdigest()


def bound(row, base=None):
    path = pathlib.Path(row["path"])
    if base is not None:
        require(not path.is_absolute() and all(p not in ("", ".", "..") for p in row["path"].split("/")) and
                not any(c in row["path"] for c in ("\\", ":", "\x00")), "Unsafe relative input")
        path = base / path
    require(sha(path) == row["sha256"], "Frozen input changed")
    return regular(path)


def load(path):
    # Repeated JSON keys must never override a pinned field.
    def pairs(rows):
        require(len(rows) == len(dict(rows)), "Duplicate JSON key")
        return dict(rows)
    return json.loads(regular(path).read_text(encoding="utf-8-sig"), object_pairs_hook=pairs)


def native_inventory(receipt, kind):
    names = {"wrapper": {"libqwdtt.so"}, "csqtt": {"libqwdtt.so", "libcsqtt_transport.so"},
             "csqtt20": {"libcsqtt20.so", "libcsqtt_transport.so"},
             "csqtt215": {"libcsqtt215.so", "libcsqtt_transport.so"},
             "csqtt25": {"libcsqtt25.so", "libcsqtt25_transport.so"},
             "qwdtt": {"libqwdtt.so"}, "wdtt": {"libwdtt.so"}}[kind]
    keys = [(row.get("abi"), pathlib.PurePosixPath(str(row.get("path", row.get("file", ""))).replace("\\", "/")).name)
            for row in receipt["artifacts"]]
    require(len(keys) == len(set(keys)) and set(keys) == {(abi, name) for abi in ABIS for name in names},
            "Native receipt ABI inventory changed")


def elf(path, abi):
    with regular(path).open("rb") as stream:
        data = stream.read(64)
        cls, machine = ABIS[abi]
        require(len(data) == 64 and data[:4] == b"\x7fELF" and data[4:6] == bytes([cls, 1]) and
                struct.unpack_from("<HH", data, 16) == (3, machine), "ELF/ABI mismatch")
        offset = struct.unpack_from("<Q" if cls == 2 else "<I", data, 32 if cls == 2 else 28)[0]
        size, count = struct.unpack_from("<HH", data, 54 if cls == 2 else 42)
        require(size == (56 if cls == 2 else 32) and 1 <= count <= 128, "ELF program table missing")
        loads = 0
        for index in range(count):
            stream.seek(offset + index * size)
            header = stream.read(size)
            require(len(header) == size, "ELF segment truncated")
            if struct.unpack_from("<I", header)[0] != 1:
                continue
            loads += 1
            file_offset, virtual = struct.unpack_from("<QQ" if cls == 2 else "<II", header, 8 if cls == 2 else 4)
            alignment = struct.unpack_from("<Q" if cls == 2 else "<I", header, 48 if cls == 2 else 28)[0]
            require(alignment >= 16384 and alignment & (alignment - 1) == 0 and
                    file_offset % alignment == virtual % alignment, "ELF lacks 16KiB alignment")
            file_size, memory_size = struct.unpack_from("<QQ" if cls == 2 else "<II", header, 32 if cls == 2 else 16)
            require(file_size <= memory_size and file_offset + file_size <= pathlib.Path(path).stat().st_size,
                    "ELF LOAD segment outside file")
        require(loads > 0, "No ELF LOAD segment")


def verify_wdtt_rebuild(root, receipt):
    recovery = receipt.get("sourceRecovery", {})
    require(recovery.get("rebuilt") is True and
            recovery.get("canonicalRevision") == "f706ccd373685bbde20cae33b2b7f400e8335ecc",
            "WDTT canonical rebuild proof missing")
    log = root / "host-tests.jsonl"
    require(sha(log) == receipt["hostEvidenceSha256"], "WDTT host log changed")
    events = [json.loads(line) for line in regular(log).read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    required = {"TestMcrfRawMTUCeilingPreservesSmallerServerValue", "TestMcrfIPRelayBuffersOnOwnedSocket",
                "TestMcrfSOCKSAdmissionQueuesAndResumes", "TestMcrfWdtt512ClientsAndOverflowBackpressure"}
    require(not any(e.get("Action") in ("fail", "skip") for e in events) and
            required <= {e.get("Test") for e in events if e.get("Action") == "pass"} and
            any(e.get("Action") == "pass" and not e.get("Test") for e in events),
            "WDTT host cases missing/failed/skipped")


def notice_inputs(roots):
    """Only admitted stage notices and legal files in receipt-bound source manifests.

    Derived stages need not duplicate a licenses directory. Never search parents,
    old build outputs, receipt 'stage' hints or arbitrary fallback paths.
    The frozen gate pins the complete resulting inventory and every byte.
    """
    result = {}
    for kind, root in roots.items():
        root = pathlib.Path(root)
        files = []
        directory = root / "licenses"
        if directory.exists():
            require(directory.is_dir(), f"Invalid notices directory: {kind}")
            for path in sorted(directory.rglob("*")):
                require(not path.is_symlink() and not (hasattr(path, "is_junction") and path.is_junction()),
                        "Link/junction notice")
                if path.is_file():
                    files.append({"location": "stage", "path": path.relative_to(root).as_posix(), "sha256": sha(path)})
        if kind in KINDS:
            receipt = load(root / "receipt.json")
            manifest = root / "source-manifest.json"
            require(sha(manifest) == receipt["sourceManifestSha256"], "Notice source manifest changed")
            for row in load(manifest):
                name = pathlib.PurePosixPath(row["path"]).name.upper()
                if name.split(".")[0] in ("LICENSE", "LICENCE", "COPYING", "NOTICE") or name.startswith(("LICENSE-", "NOTICE-")):
                    path = bound(row, pathlib.Path(receipt["source"]))
                    files.append({"location": "source", "path": row["path"], "sha256": sha(path)})
        require(files, f"Missing source-bound native notices: {kind} ({root})")
        result[kind] = files
    return result


def verified_notices(gate, roots):
    if gate.get("layout")==LAYOUT:
        from bs_multicall_inputs import notices
        require(gate["notices"]==notices(roots),"Multicall notices changed")
        return gate["notices"]
    require(gate.get("notices") == notice_inputs(roots), "Frozen native notices missing/changed; refreeze required")
    return gate["notices"]


def inspect(gate_path, expected):
    require(len(expected) == 64 and sha(gate_path) == expected, "Explicit frozen BS gate SHA required")
    gate = load(gate_path)
    if gate.get("layout")==LAYOUT:
        from bs_multicall_inputs import inspect as inspect_multicall
        return inspect_multicall(gate)
    require_layout(gate.get("layout"))
    require(gate["schema"] == 2 and gate.get("layout") == SPLIT_LAYOUT and gate["state"] == "BS_SOURCE_BOUND_INPUTS_READY" and
            gate["package"] == "org.mcrf.engines.bs" and gate["logicalEngines"] == list(KINDS), "Wrong BS input gate")
    require(isinstance(gate["versionCode"], int) and 1 <= gate["versionCode"] <= 2100000000, "Invalid version")
    # Canonical source changes invalidate the package gate, even with unchanged native bytes.
    sources = gate["canonicalSources"]
    required = {"transports/swg/bs_engine_inputs.py", "transports/swg/prepare_bs_wrapper.py",
                "transports/swg/mcrf_bs_transport.go", "transports/swg/mcrf_bs_transport_test.go",
                "transports/swg/Build-BsWrapper.ps1", "transports/swg/Build-BsBaseline.ps1",
                "transports/swg/Invoke-BsWrapperHost.ps1", "transports/swg/csqtt25/versioned_inputs.py",
                "transports/swg/csqtt25/swg-versions.json", "engines/EngineUpdateBsManifest.xml", "engines/build.gradle.kts",
                "maintenance/Package-BsEngine.ps1", "maintenance/prepare_swg_armv7.py",
                "maintenance/Build-SwgVersionArmv7.ps1", "maintenance/Build-AlignedSwgLoader.ps1",
                "transports/swg/csqtt25/aligned_inputs.py", "transports/swg/csqtt25/aligned_wrapper.py",
                "transports/swg/csqtt25/swg-armv7-alignment.json", "transports/swg/csqtt25/aligned_native_inputs_test.go",
                "maintenance/freeze_bs_inputs.py", "maintenance/recover_bs_source_stages.py",
                "maintenance/finalize_recovered_wdtt.py"}
    require(required <= {row["path"] for row in sources} and len(sources) == len({r["path"] for r in sources}), "Missing/duplicate canonical source bindings")
    for row in sources:
        bound(row, ROOT)
    require(set(gate["inputs"]) == set(KINDS) | {"wrapper", "versions"}, "Incomplete logical engine inputs")
    receipts = {}
    roots = {}
    for kind, row in gate["inputs"].items():
        receipt_path = bound(row)
        require(receipt_path.name in ("receipt.json", "versioned-inputs.json"), "Only native input receipts accepted")
        require(not {"outputs", "intermediates"} & set(receipt_path.parts), "Build outputs are not native provenance")
        receipt = load(receipt_path)
        if kind != "versions":
            native_inventory(receipt, kind)
            require(receipt.get("sourceStable") is True, "Unstable native source")
            require(receipt.get("state") in ("COMPILED_NOT_EXECUTED", "HISTORICAL_ENGINE_INPUTS_READY"), "Unadmitted native receipt")
        # Source proof is independently hash-bound by the frozen gate; never trust a .so alone.
        proof = bound(row["sourceProof"])
        require(proof.stat().st_size > 0, "Missing source proof")
        receipts[kind], roots[kind] = receipt, receipt_path.parent
    wrapper = receipts["wrapper"]
    require(wrapper.get("bsTransportContract") == 1 and wrapper.get("bsModeContract") == 2 and wrapper.get("kind") == "csqtt", "Fresh multi-mode shared wrapper required")
    prepared = load(bound(gate["inputs"]["wrapper"]["sourceProof"]))
    require(prepared["state"] == "BS_WRAPPER_PREPARED_NOT_COMPILED" and
            wrapper["preparedSourceSha256"] == sha(bound(gate["inputs"]["wrapper"]["sourceProof"])), "Wrapper/source receipt mismatch")
    require(prepared["adapterSha256"] == sha(ROOT / "transports/swg/prepare_bs_wrapper.py") and
            prepared["helperSha256"] == sha(ROOT / "transports/swg/mcrf_bs_transport.go"), "Wrapper adapter changed")
    source_root = pathlib.Path(prepared["root"])
    expected_sources = {r["path"] for r in prepared["sources"]}
    actual_sources = {p.relative_to(source_root).as_posix() for p in source_root.rglob("*") if p.is_file() and (p.suffix == ".go" or p.name in ("go.mod", "go.sum"))}
    require(expected_sources == actual_sources, "Prepared source inventory changed")
    for row in prepared["sources"]:
        bound(row, source_root)
    # Reconstruct the complete adapted tree, not just its self-reported hashes.
    # This binds the superset and exact-duplicate evidence to the compiled source.
    verify_multimode_preparation(prepared, gate["inputs"]["csqtt25"], gate["inputs"]["qwdtt"])
    require(sha(roots["wrapper"] / "source-manifest.json") == wrapper["sourceManifestSha256"], "Compiled wrapper manifest changed")
    require(load(roots["wrapper"] / "source-manifest.json") == prepared["sources"], "Compiled wrapper uses another source")
    require(wrapper["producerSha256"] == sha(ROOT / "transports/swg/Build-BsWrapper.ps1"), "Wrapper producer changed")
    baseline_path = pathlib.Path(wrapper["baselineReceipt"])
    require(sha(baseline_path) == wrapper["baselineReceiptSha256"] == sha(roots["wrapper"] / "baseline-receipt.json"),
            "Wrapper baseline receipt changed")
    baseline = load(baseline_path)
    require(baseline["kind"] == "csqtt" and baseline["state"] == "COMPILED_NOT_EXECUTED" and
            baseline["sourceStable"] is True and
            all(baseline.get(k) == 1 for k in ("processGroupContract", "accountOptionsContract", "turnHashPoolContract")) and
            baseline["sourceManifestSha256"] == prepared["baselineManifestSha256"], "Wrong wrapper baseline")
    require(sha(baseline_path.parent / "source-manifest.json") == baseline["sourceManifestSha256"], "Baseline manifest changed")
    require(regular(prepared["baselineManifest"]) == regular(baseline_path.parent / "source-manifest.json") and
            pathlib.Path(prepared["baselineSource"]).absolute() == pathlib.Path(baseline["source"]).absolute(),
            "Wrapper prepared from another baseline")
    for row in load(baseline_path.parent / "source-manifest.json"):
        bound(row, pathlib.Path(baseline["source"]))
    if "canonicalPreparedSha256" in baseline:
        require(baseline["producerSha256"] == sha(ROOT / "transports/swg/Build-BsBaseline.ps1") and
                sha(baseline_path.parent / "canonical-prepared-source.json") == baseline["canonicalPreparedSha256"] and
                sha(baseline_path.parent / "retained-baseline-receipt.json") == baseline["retainedReceiptSha256"],
                "Fresh baseline preparation changed")
        require(sha(baseline_path.parent / "host-evidence.json") == baseline["hostEvidenceSha256"], "Baseline host proof changed")
        baseline_host = load(baseline_path.parent / "host-evidence.json")
        require(baseline_host["state"] == "HOST_CHECKS_PASSED" and
                baseline_host["sourceManifestSha256"] == baseline["sourceManifestSha256"] and
                sha(baseline_path.parent / "host-tests.jsonl") == baseline_host["logSha256"], "Baseline host log changed")
        events = [json.loads(line) for line in regular(baseline_path.parent / "host-tests.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        required_cases = {"TestCSQTTAccountOptionsRequirePrivateMatchingSession", "TestCSQTTExtendedHashPoolSurvivesConfigAndLink",
                          "TestExtendedHashesKeepCompletePool", "TestMcrfProcessGroupPrivateAckIsExactAndOneShot",
                          "TestMcrfChildInputFailureWipesAndNeverRetriesOrReopens", "TestMcrfStreamsRetainedLegacyStatsAndExactUpgrade",
                          "TestMcrfRawMTUOnActualBulkUpload"}
        require(not any(e.get("Action") in ("fail", "skip") for e in events) and
                required_cases <= {e.get("Test") for e in events if e.get("Action") == "pass"},
                "Baseline host cases missing/failed/skipped")
    host_path = roots["wrapper"] / "host-evidence.json"
    require(sha(host_path) == wrapper["hostEvidenceSha256"], "Wrapper host proof changed")
    host = load(host_path)
    require(host["state"] == "HOST_CHECKS_PASSED" and host["preparedSourceSha256"] == wrapper["preparedSourceSha256"] and
            host["producerSha256"] == sha(ROOT / "transports/swg/Invoke-BsWrapperHost.ps1") and
            sha(roots["wrapper"] / "host-tests.jsonl") == host["logSha256"], "Wrapper host evidence does not bind source")
    events = [json.loads(line) for line in regular(roots["wrapper"] / "host-tests.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    require(not any(e.get("Action") in ("fail", "skip") for e in events) and
            set(MODE_TESTS) <=
            {e.get("Test") for e in events if e.get("Action") == "pass"}, "Wrapper affected tests missing/failed/skipped")
    for kind in KINDS:
        require(receipts[kind].get("kind") == kind, "Receipt logical ID mismatch")
        require(sha(bound(gate["inputs"][kind]["sourceProof"])) == receipts[kind]["sourceManifestSha256"], "Native/source manifest binding mismatch")
        rows = load(bound(gate["inputs"][kind]["sourceProof"]))
        source_root = pathlib.Path(receipts[kind]["source"])
        if kind in ("csqtt20", "csqtt215"):
            actual = {p.relative_to(source_root).as_posix() for p in source_root.rglob("*")
                      if p.is_file() and ".git" not in p.relative_to(source_root).parts}
            expected_all = {r["path"] for r in rows}
            require(len(rows) == len(expected_all) and actual == expected_all,
                    "Historical native source inventory changed")
            for row in rows:
                bound(row, source_root)
        else:
            actual = {p.relative_to(source_root).as_posix() for p in source_root.rglob("*")
                      if p.is_file() and (p.suffix == ".go" or p.name in ("go.mod", "go.sum"))}
            expected_go = {r["path"] for r in rows if pathlib.Path(r["path"]).suffix == ".go" or
                           pathlib.Path(r["path"]).name in ("go.mod", "go.sum")}
            require(len(rows) == len({r["path"] for r in rows}) and actual == expected_go,
                    "Native source inventory changed")
            for row in rows:
                bound(row, source_root)
        if kind in ("csqtt", "csqtt25"):
            require(receipts[kind].get("processGroupContract") == 1 and receipts[kind].get("turnHashPoolContract") == 1, "CSQTT ownership/pool contract missing")
        if kind in ("csqtt20", "csqtt215"):
            revision = {"csqtt20": "2ff8a1a4fbe031fb76b799e172dce25cfe1ca942", "csqtt215": "de7afc23cec7481f98845bf24fc8943af19b4cf8"}[kind]
            require(receipts[kind].get("revision") == revision, "Historical wire generation changed")
            require(receipts[kind].get("sourceRecovery", {}).get("revision") == revision,
                    "Historical exact source recovery missing")
            for filename, key in (("wrapper-receipt.json", "wrapperReceiptSha256"), ("native-receipt.json", "nativeReceiptSha256")):
                require(sha(roots[kind] / filename) == receipts[kind][key], "Historical pairing changed")
    require(wrapper.get("processGroupContract") == 1 and wrapper.get("accountOptionsContract") == 1 and wrapper.get("turnHashPoolContract") == 1, "Shared wrapper lost capabilities")
    require(receipts["qwdtt"].get("fingerprintContract") == 1 and receipts["qwdtt"].get("turnHashPoolContract") == 1, "QWDTT capabilities missing")
    from csqtt25 import versioned_inputs
    versioned_inputs.verify(roots["versions"], roots["csqtt25"])
    outputs = {}
    def artifact(kind, abi, name):
        if kind == "versions":
            path = roots[kind] / abi / name
            elf(path, abi)
            return path  # pinned upstream digest checked by versioned_inputs.verify
        rows = receipts[kind]["artifacts"]
        found = [r for r in rows if r.get("abi") == abi and pathlib.PurePosixPath(str(r.get("path", r.get("file", ""))).replace("\\", "/")).name == name]
        require(len(found) == 1, "Missing/duplicate ABI artifact")
        row = found[0]
        # Fixed stage-relative paths only; receipt absolute paths are not followed.
        path = roots[kind] / abi / name
        require(path.stat().st_size == row["bytes"] and sha(path) == row["sha256"], "Native byte identity changed")
        elf(path, abi)
        return path
    for abi in ABIS:
        # Removed physical wrappers remain receipt-verified provenance inputs.
        require(sha(artifact("qwdtt", abi, "libqwdtt.so")) == sha(artifact("csqtt", abi, "libqwdtt.so")),
                "QWDTT native is not an exact wrapper duplicate")
        artifact("csqtt25", abi, "libcsqtt25.so")
        result = {"libbs.so": artifact("wrapper", abi, "libqwdtt.so")}
        for kind in ("csqtt", "csqtt20", "csqtt215"):
            name = "libcsqtt_transport.so" if kind == "csqtt" else f"lib{kind}_transport.so"
            result[name] = artifact(kind, abi, "libcsqtt_transport.so")
        result["libcsqtt25_transport.so"] = artifact("csqtt25", abi, "libcsqtt25_transport.so")
        result["libcsqtt252_transport.so"] = artifact("versions", abi, "libcsqtt252_transport.so")
        outputs[abi] = result
    verified_notices(gate, roots)
    return gate, roots, outputs


def catalog(outputs):
    require(all("libwdtt.so" not in files for files in outputs.values()), "WDTT belongs to its standalone package")
    if all(set(files)=={"libbs.so","libcsqtt_transport.so"} for files in outputs.values()):
        return {"schema":4,"layout":LAYOUT,"package":"org.mcrf.engines.bs",
                "engines":{k:{"executable":"libbs.so"} for k in KINDS},
                "native":{abi:{n:{"bytes":p.stat().st_size,"sha256":sha(p)} for n,p in files.items()} for abi,files in outputs.items()}}
    return {"schema": 3, "package": "org.mcrf.engines.bs",
            "engines": {k: {"executable": "libbs.so"} for k in KINDS},
            "native": {abi: {n: {"bytes": p.stat().st_size, "sha256": sha(p)} for n, p in files.items()} for abi, files in outputs.items()}}


def inventory(outputs):
    rows = {}
    for abi, files in outputs.items():
        total = sum(p.stat().st_size for p in files.values())
        compressed = sum(len(zlib.compress(p.read_bytes(), 9)) for p in files.values())
        wrapper = files["libbs.so"].stat().st_size
        rows[abi] = {"uniqueNativeBytes": total, "compressedNativeEstimate": compressed,
                     "oldThreeWrappersAvoidedBytes": 2 * wrapper,
                     "apkEstimateRangeBytes": [compressed + 32768, compressed + 524288],
                     "installedEstimateRangeBytes": [total + compressed + 32768, total + compressed + 524288]}
    return {"scope": "Native payload + ZIP estimate, not a built APK or measured installed allocation", "abis": rows}


def stage(gate_path, expected, destination):
    gate, roots, outputs = inspect(gate_path, expected)
    target = pathlib.Path(destination).absolute()
    require(not target.exists(), "Stage destination must be fresh")
    target.mkdir(parents=True)
    for abi, files in outputs.items():
        for name, source in files.items():
            out = target / "jni" / abi / name
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, out)
            require(sha(out) == sha(source), "Copy changed native bytes")
    # One notice store, content-identical notices shared. No duplicate dex/resources.
    notices, notice_index = {}, []
    for kind, rows in verified_notices(gate, roots).items():
        root = roots[kind]
        for row in rows:
            base = root if row["location"] == "stage" else pathlib.Path(load(root / "receipt.json")["source"])
            source = bound(row, base)
            key = row["sha256"]
            if key not in notices:
                out = target / "assets" / "licenses" / f"{key}-{source.name}"
                out.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, out)
                require(sha(out) == key, "Copy changed notice bytes")
                notices[key] = out
            notice_index.append({"engine": kind, "name": source.name, "sha256": key, "asset": notices[key].name})
    require(notices, "Missing legal notices")
    for name, value in (("mcrf-bs.json", catalog(outputs)), ("size-estimate.json", inventory(outputs)), ("notices-index.json", notice_index)):
        path = target / "assets" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    # Detect any input mutation during staging.
    inspect(gate_path, expected)


def verify_direct_tun_metadata(xmltree, expected, key="csqtt_direct_tun_fd"):
    """Parse aapt's manifest tree, never infer capability from a name/version/CLI string."""
    require(type(expected) is int and expected in ((0,1,2) if key=="csqtt_direct_tun_fd" else (0,1)), "Invalid expected direct TUN capability")
    nodes = re.findall(r"(?ms)^[ \t]*E: meta-data\b[^\n]*\n(?:(?!^[ \t]*E:).)*", xmltree)
    keys = {"csqtt_direct_tun_fd", "original_packet", "csqtt_gooby_builtin", "bs_routing_session"}
    keys.update(f"socks_udp_source.{kind}" for kind in KINDS)
    require(key in keys, "Unknown BS capability")
    name = r'A: android:name(?:\([^)]*\))?="org\.mcrf\.engines\.'+re.escape(key)+'"'
    matches = [node for node in nodes if re.search(name, node)]
    require(len(matches) == 1, f"Missing/duplicate BS metadata: {key}")
    values = re.findall(r'A: android:value(?:\([^)]*\))?=\(type 0x(?:10|11)\)0x([0-9a-fA-F]+)\b', matches[0])
    require(len(values) == 1 and int(values[0], 16) == expected,
            f"BS metadata must be the verified integer capability: {key}")


def verify_native_routing_metadata(xmltree,expected):
    verify_direct_tun_metadata(xmltree,expected,"bs_routing_session")
    nodes=re.findall(r"(?ms)^[ \t]*E: meta-data\b[^\n]*\n(?:(?!^[ \t]*E:).)*",xmltree)
    matches=[node for node in nodes if re.search(r'A: android:name(?:\([^)]*\))?="org\.mcrf\.engines\.bs_routing_generations"',node)]
    require(len(matches)==1,"Missing/duplicate native generation declaration")
    value="2.5.1,2.5.2,2.5.3" if expected else ""
    require(re.search(r'A: android:value(?:\([^)]*\))?="'+re.escape(value)+r'"',matches[0]) is not None,"Native generations must match the executed source-bound contract")


def verify_apk(path, outputs, abi):
    """Packaging verification supplements (never replaces) Android APK signature checks."""
    require(abi in ABIS, "No universal BS APK")
    with zipfile.ZipFile(regular(path)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate APK entries")
        # An Android application APK always receives a generated R class. The
        # native-only engine must contain exactly that tiny DEX and no Kotlin,
        # adapter, or other executable bytecode.
        dex_names = [n for n in names if n.endswith(".dex")]
        require(dex_names == ["classes.dex"], "Unexpected BS dex payload")
        dex = archive.read("classes.dex")
        require(len(dex) <= 1024 and dex.startswith(b"dex\n") and
                struct.unpack_from("<I", dex, 32)[0] == len(dex) and
                struct.unpack_from("<I", dex, 96)[0] == 1 and
                b"Lorg/mcrf/engines/R;" in dex and b"kotlin/" not in dex,
                "BS dex must contain only the generated R class")
        require({n for n in names if n.startswith("lib/") and not n.endswith("/")} == {f"lib/{abi}/{n}" for n in outputs[abi]}, "Unexpected native payload/ABI")
        for name, source in outputs[abi].items():
            content = archive.read(f"lib/{abi}/{name}")
            require(len(content) == source.stat().st_size and hashlib.sha256(content).hexdigest() == sha(source), "APK native differs from source-bound input")
        require(json.loads(archive.read("assets/mcrf-bs.json")) == catalog(outputs), "APK catalog differs from frozen inputs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gate", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--stage")
    parser.add_argument("--verify-apk")
    parser.add_argument("--manifest-xmltree-stdin", action="store_true")
    parser.add_argument("--abi", choices=ABIS)
    args = parser.parse_args()
    if args.stage:
        stage(args.gate, args.sha256, args.stage)
    else:
        gate, _, files = inspect(args.gate, args.sha256)
        if args.verify_apk:
            verify_apk(args.verify_apk, files, args.abi)
        if args.manifest_xmltree_stdin:
            require(args.verify_apk and args.abi, "Manifest verification must accompany the exact APK/ABI")
            xmltree=sys.stdin.read()
            verify_direct_tun_metadata(xmltree, gate.get("directTunFdContract", 0))
            verify_direct_tun_metadata(xmltree, gate.get("originalPacketContract", 0), "original_packet")
            verify_direct_tun_metadata(xmltree, gate.get("csqttGoobyBuiltinContract", 0), "csqtt_gooby_builtin")
            verify_native_routing_metadata(xmltree,gate.get("nativeRoutingContract",0))
            for kind in KINDS:
                verify_direct_tun_metadata(xmltree, gate.get("socksUdpSource", {}).get(kind, 0),
                                           f"socks_udp_source.{kind}")
        print(json.dumps(inventory(files), indent=2))
