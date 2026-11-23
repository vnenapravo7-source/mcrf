"""Read-only P3 provenance checks. Historical receipt paths are data, not inputs."""
import hashlib
import importlib.util
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).parent
ACCEPTED_STAGE = pathlib.Path("D:/MCRF/Builds/Android/p3-csqtt25-native-b72a5217a8754ac0823f9ab93870a43d")
ACCEPTED_RECEIPT_SHA256 = "ae70862426723fcf604312b6397de3e1afc8ebc48a8c90de17e050c6a54d9d3a"
ACCEPTED_SOURCE = pathlib.Path("D:/MCRF/Builds/Android/p3-csqtt25-source-74230c54ed47451ba350309de6a0458c")
ACCEPTED_SOURCE_RECEIPT_SHA256 = "4cdbd912e633db19b5abf08f9677c997d5a0cbc65b5ad5ecf12fd1af0fae2c16"
OVERLAYS = ("runtime.go", "native_abi.go", "native_abi_test.go", "native_abi_inputs_test.go",
            "profile.go", "versions.go", "versions_test.go")
PERFORMANCE = ("mcrf_transport_performance.go", "mcrf_transport_performance_test.go",
               "mcrf_streams.go", "mcrf_streams_test.go", "mcrf_raw_upload_test.go")
# The immutable CSQTT25 baseline already consumes STATS. A legacy compatibility
# change in today's shared overlay must not invalidate this historical proof or
# silently claim that its newer tests ran against the retained binary.
RETAINED_STREAMS = {
    "mcrf_streams.go": "aaef34cc203e5986d489d82356e5ed1736f6fd730d4ba6a53287f7045324fd8f",
    "mcrf_streams_test.go": "b6cd76b3de8a78ae155db62e8530f2c42a96c2737e123e1ec7839bdf1cdfd390",
}


def regular(path):
    path = pathlib.Path(path).absolute()
    for part in (path, *path.parents):
        if part.is_symlink() or part.is_junction():
            raise ValueError("Redirected P3 input refused")
    if not path.is_file():
        raise ValueError(f"P3 input missing: {path}")
    return path


def digest(path):
    with regular(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load(path):
    return json.loads(regular(path).read_text(encoding="utf-8-sig"))


def require(value, message):
    if not value:
        raise ValueError(message)


def sampled_bridge():
    source = regular(HERE.parent / "mcrf_csqtt25.go").read_text(encoding="utf-8")
    anchor = "NormalizeTunnel: csqtt25.NormalizeTunnel,"
    require(source.count(anchor) == 1 and "McrfSampleStreamStats" not in source,
            "CS25 sampled stream bridge anchor changed")
    return source.replace(anchor, anchor + "\n\t\tMcrfSampleStreamStats: true,").encode("utf-8")


def inventory(module, manifest):
    rows = load(manifest)
    found = {}
    for row in rows:
        name = row["path"].replace("\\", "/")
        require(name and not re.search(r"[:\x00]", name)
                and not name.startswith("/") and all(p not in ("", ".", "..") for p in name.split("/")),
                "Unsafe P3 manifest path")
        require(name.casefold() not in {p.casefold() for p in found}, "Duplicate P3 manifest path")
        require(digest(module / name) == row["sha256"], "P3 source hash changed")
        found[name] = row["sha256"]
    actual = set()
    for path in module.rglob("*"):
        require(not path.is_symlink() and not path.is_junction(), "Redirected P3 source refused")
        if path.is_file():
            actual.add(path.relative_to(module).as_posix())
    require(actual == set(found), "P3 source set changed")
    return found


def baseline(stage=ACCEPTED_STAGE):
    stage = pathlib.Path(stage).absolute()
    require(stage == ACCEPTED_STAGE.absolute(), "Exact surviving accepted P3 stage required")
    require(digest(stage / "receipt.json") == ACCEPTED_RECEIPT_SHA256, "Accepted P3 receipt changed")
    receipt = load(stage / "receipt.json")
    require(receipt["kind"] == "csqtt25" and receipt["state"] == "COMPILED_NOT_EXECUTED"
            and receipt["sourceStable"] is True and receipt["ownerCommandUnchanged"] is True
            and receipt["processGroupContract"] == receipt["turnHashPoolContract"] == 1,
            "Accepted P3 contracts missing")
    module = ACCEPTED_SOURCE / "module"
    require(pathlib.Path(receipt["source"]) == module, "Accepted P3 module changed")
    prepared_path = ACCEPTED_SOURCE / "receipt.json"
    require(digest(prepared_path) == ACCEPTED_SOURCE_RECEIPT_SHA256, "Accepted source receipt changed")
    prepared = load(prepared_path)
    require(prepared["state"] == "P3_ABI_SOURCE_PREPARED_NOT_COMPILED"
            and prepared["sourceStable"] is True and pathlib.Path(prepared["module"]) == module,
            "Accepted source contracts missing")
    manifest = ACCEPTED_SOURCE / "SOURCE_MANIFEST.json"
    require(digest(manifest) == prepared["manifestSha256"] == receipt["sourceManifestSha256"]
            == digest(stage / "source-manifest.json"), "Accepted source manifest changed")
    rows = inventory(module, manifest)
    for key, folder in (("processGroupInputs", "process-group-inputs"), ("turnFeatureInputs", "turn-feature-inputs")):
        for row in receipt[key]:
            require(rows[row["file"]] == row["sha256"] == digest(stage / folder / row["file"]),
                    "Accepted owner/hash-pool input changed")
    expected = {(abi, name) for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")
                for name in ("libcsqtt25.so", "libcsqtt25_transport.so")}
    actual = set()
    for row in receipt["artifacts"]:
        path = pathlib.Path(row["file"])
        require(path == stage / row["abi"] / path.name, "Unexpected accepted artifact path")
        require(digest(path) == row["sha256"] and path.stat().st_size == row["bytes"], "Accepted artifact changed")
        require((row["abi"], path.name) not in actual, "Duplicate accepted artifact")
        actual.add((row["abi"], path.name))
    require(actual == expected, "Accepted artifact set changed")
    origin = load(stage / "p3-abi-origin.json")
    require(digest(stage / "p3-abi-origin.json") == receipt["p3AbiOriginSha256"], "Accepted origin changed")
    for name, key in (("retained-native-runtime.go", "oldRuntimeSha256"),
                      ("abi-runtime-input.go", "newRuntimeSha256"),
                      ("retained-performance-bridge.go", "oldPerformanceBridgeSha256"),
                      ("performance-bridge-input.go", "newPerformanceBridgeSha256"),
                      ("retained-p3-owner-evidence.json", "retainedEvidenceSha256")):
        require(digest(stage / name) == origin[key], "Accepted retained evidence changed")
    require(rows["csqtt_bridge.go"] == origin["newPerformanceBridgeSha256"]
            and rows["internal/csqtt25/runtime.go"] == origin["newRuntimeSha256"], "Accepted delta differs from module")
    host = load(stage / "new-abi-host-evidence.json")
    require(digest(stage / "new-abi-host-evidence.json") == receipt["hostEvidenceSha256"] == origin["hostEvidenceSha256"]
            and host["sourceManifestSha256"] == receipt["sourceManifestSha256"]
            and digest(stage / "abi-host-tests.jsonl") == host["logSha256"]
            and digest(stage / "abi-input-receipt.json") == host["inputReceiptSha256"], "Accepted host evidence changed")
    require(prepared["performanceSourceSha256"] == digest(HERE.parent / "performance/Apply-PerformanceSource.ps1"),
            "Reviewed performance transform changed")
    for name in PERFORMANCE:
        expected = RETAINED_STREAMS[name] if name in RETAINED_STREAMS else digest(HERE.parent / "performance" / name)
        require(rows[name] == expected, "Reviewed performance overlay changed")
    # Do not follow prepared.baselineReceipt/origin.baselineReceipt: those snapshots
    # were intentionally removed. The pinned surviving receipt closes this chain.
    return {"module": str(module), "receipt": str(stage / "receipt.json"),
            "receiptSha256": ACCEPTED_RECEIPT_SHA256, "prepared": str(prepared_path),
            "preparedSha256": ACCEPTED_SOURCE_RECEIPT_SHA256, "manifest": str(manifest),
            "manifestSha256": digest(manifest), "performanceAlreadyApplied": True}


def negotiated():
    spec = importlib.util.spec_from_file_location("p3_negotiated", HERE / "prepare-negotiated-workers.py")
    adapter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapter)
    return adapter


def prepared(stage, accepted=ACCEPTED_STAGE):
    base = baseline(accepted)
    stage = pathlib.Path(stage).absolute()
    require(re.fullmatch(r"D:/MCRF/Builds/Android/p3-csqtt25-source-[0-9a-f]{32}", stage.as_posix()),
            "Owned P3 source stage required")
    proof = load(stage / "receipt.json")
    module = stage / "module"
    require(proof["state"] == "P3_ABI_SOURCE_PREPARED_NOT_COMPILED" and proof["sourceStable"] is True
            and proof["compiled"] is False and proof["executed"] is False
            and proof["ownerCommandUnchanged"] is True and pathlib.Path(proof["module"]) == module
            and proof["baselineReceiptSha256"] == base["receiptSha256"]
            and pathlib.Path(proof["baselineReceipt"]) == pathlib.Path(base["receipt"])
            and pathlib.Path(proof["baselinePreparedReceipt"]) == pathlib.Path(base["prepared"])
            and proof["baselinePreparedSha256"] == base["preparedSha256"]
            and proof["baselineSourceManifestSha256"] == base["manifestSha256"]
            and proof["performanceAlreadyApplied"] is True, "Rebased P3 source provenance changed")
    require(proof["manifestSha256"] == digest(stage / "SOURCE_MANIFEST.json"), "Frozen P3 manifest changed")
    rows = inventory(module, stage / "SOURCE_MANIFEST.json")
    old = inventory(pathlib.Path(base["module"]), pathlib.Path(base["manifest"]))
    delta = {"internal/csqtt25/" + name for name in OVERLAYS} | {"mcrf_csqtt25.go"}
    require(rows.keys() == old.keys() | delta and all(rows[p] == old[p] for p in rows.keys() - delta),
            "Rebase changed owner/hash-pool/performance source")
    require(rows["mcrf_csqtt25.go"] == hashlib.sha256(sampled_bridge()).hexdigest(),
            "Versioned native selection or sampled STATS ingress changed")
    require(proof["performanceSourceSha256"] == digest(HERE.parent / "performance/Apply-PerformanceSource.ps1")
            and proof["provenanceVerifierSha256"] == digest(pathlib.Path(__file__)), "P3 verifier/overlay changed")
    require(proof["overlays"] == [{"name": name, "sha256": digest(HERE / name)} for name in OVERLAYS],
            "P3 canonical overlays changed")
    for name in OVERLAYS[2:]:
        require(rows["internal/csqtt25/" + name] == digest(HERE / name), "P3 test overlay changed")
    transport = pathlib.Path(proof["negotiatedTransportStage"])
    require(proof["workerNegotiationContract"] == 1 and proof.get("serverTunTxQueueContract") == 1
            and proof["negotiatedTransportReceiptSha256"] == digest(transport / "receipt.json")
            and proof["negotiatedSourceManifestSha256"] == digest(transport / "source-manifest.json"),
            "Frozen negotiated identity changed")
    negotiated().verify_wrapper(module, transport / "source")
    marker = "// Construction only:"
    before = (pathlib.Path(base["module"]) / "internal/csqtt25/runtime.go").read_text(encoding="utf-8")
    after = (module / "internal/csqtt25/runtime.go").read_text(encoding="utf-8")
    require(marker in before and marker in after and before.split(marker, 1)[1] == after.split(marker, 1)[1],
            "P3 process command changed")
    return base


def native(stage):
    """Admit rebuilt artifacts only through the exact surviving generation chain."""
    stage = pathlib.Path(stage).absolute()
    require(re.fullmatch(r"D:/MCRF/Builds/Android/p3-csqtt25-native-[0-9a-f]{32}", stage.as_posix()),
            "Owned P3 native stage required")
    proof = load(stage / "receipt.json")
    require(proof.get("kind") == "csqtt25" and proof.get("state") == "COMPILED_NOT_EXECUTED"
            and proof.get("sourceStable") is True and proof.get("nativeExecuted") is False
            and proof.get("apkBuilt") is False and proof.get("ownerCommandUnchanged") is True
            and all(proof.get(k) == 1 for k in ("workerNegotiationContract", "serverTunTxQueueContract",
                                               "processGroupContract", "turnHashPoolContract"))
            and proof.get("p3AbiAuthority") == "human-20261023-finish-current-fixes-then-p3-p4",
            "Native contracts missing")
    source = pathlib.Path(proof["preparedSource"])
    base = prepared(source)
    source_proof = load(source / "receipt.json")
    require(digest(source / "receipt.json") == proof["preparedReceiptSha256"]
            and pathlib.Path(proof["source"]) == source / "module"
            and proof["producerSha256"] == digest(HERE / "Build-P3AbiNative.ps1"),
            "Native producer/source changed")
    for key in ("sourceManifestSha256", "negotiatedTransportStage", "negotiatedTransportReceiptSha256",
                "negotiatedSourceManifestSha256", "baselineSourceManifestSha256", "provenanceVerifierSha256"):
        require(proof[key] == source_proof["manifestSha256" if key == "sourceManifestSha256" else key],
                "Native source identity changed")
    require(digest(stage / "source-manifest.json") == proof["sourceManifestSha256"], "Native manifest changed")
    accepted = load(ACCEPTED_STAGE / "receipt.json")
    require(proof["packageVersionCode"] == accepted["packageVersionCode"] + 1
            and proof["packageVersionName"] == f'2.5.1-swgpanel-abi.{proof["packageVersionCode"]}'
            and proof["retainedOwnerReceiptSha256"] == base["receiptSha256"], "Native generation/version changed")
    for key, folder in (("processGroupInputs", "process-group-inputs"), ("turnFeatureInputs", "turn-feature-inputs")):
        expected = [dict(row) for row in accepted[key]]
        if key == "turnFeatureInputs":
            for row in expected:
                if row["file"] == "internal/csqtt25/profile.go":
                    row["sha256"] = digest(HERE / "profile.go")
        require(proof[key] == expected, "Native owner/hash-pool identities changed")
        for row in proof[key]:
            require(digest(stage / folder / row["file"]) == row["sha256"], "Native retained input changed")
    origin = load(stage / "p3-abi-origin.json")
    require(digest(stage / "p3-abi-origin.json") == proof["p3AbiOriginSha256"]
            and origin["authority"] == proof["p3AbiAuthority"]
            and origin["newOwnerExecution"] is False and origin["newAbiRuntimeAccepted"] is False
            and origin["baselineReceiptSha256"] == base["receiptSha256"]
            and pathlib.Path(origin["baselineReceipt"]) == pathlib.Path(base["receipt"])
            and origin["sourceManifestSha256"] == proof["sourceManifestSha256"]
            and origin["performanceAlreadyApplied"] is True and proof["performanceAlreadyApplied"] is True,
            "Native origin changed")
    for name, key, expected in (
            ("retained-native-runtime.go", "oldRuntimeSha256", pathlib.Path(base["module"]) / "internal/csqtt25/runtime.go"),
            ("abi-runtime-input.go", "newRuntimeSha256", source / "module/internal/csqtt25/runtime.go"),
            ("retained-performance-bridge.go", "oldPerformanceBridgeSha256", pathlib.Path(base["module"]) / "csqtt_bridge.go"),
            ("performance-bridge-input.go", "newPerformanceBridgeSha256", source / "module/csqtt_bridge.go"),
            ("retained-p3-owner-evidence.json", "retainedEvidenceSha256", ACCEPTED_STAGE / "retained-p3-owner-evidence.json")):
        require(digest(stage / name) == origin[key] == digest(expected), "Native retained evidence changed")
    host = load(stage / "new-abi-host-evidence.json")
    require(digest(stage / "new-abi-host-evidence.json") == proof["hostEvidenceSha256"] == origin["hostEvidenceSha256"]
            and host["state"] == "PASS_P3_CS25_ABI_HOST" and host["failures"] == 0 and host["skipped"] == 0
            and host["sourceManifestSha256"] == proof["sourceManifestSha256"]
            and digest(stage / "abi-host-tests.jsonl") == host["logSha256"]
            and digest(stage / "abi-input-receipt.json") == host["inputReceiptSha256"]
            == proof["negotiatedTransportReceiptSha256"], "Native host/input evidence changed")
    required = {"Test25AdditionalAbi" + name for name in (
        "DescriptorsAreExactAndDistinct", "AndroidMustMatchWrapperArchitecture",
        "HeaderInspectionRejectsMalformedOrOtherGenerations", "RejectsCorrectHeaderWithWrongContent",
        "ActualArtifacts/arm64-v8a", "ActualArtifacts/armeabi-v7a", "ActualArtifacts/x86_64")}
    required.update("TestMcrf" + name for name in ("RawMTUCeilingPreservesSmallerServerValue",
        "IPRelayBuffersOnOwnedSocket", "SOCKSAdmissionQueuesAndResumes", "RawMTUOnActualBulkUpload"))
    require(required <= set(host["cases"]), "Native host cases missing")
    events = [json.loads(line) for line in regular(stage / "abi-host-tests.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    require(not any(e.get("Action") in ("fail", "skip") for e in events)
            and required <= {e.get("Test") for e in events if e.get("Action") == "pass"},
            "Native host log does not prove required cases")
    transport = pathlib.Path(proof["negotiatedTransportStage"])
    pins = {r["abi"]: r for r in load(transport / "receipt.json")["artifacts"]}
    expected = {(abi, name) for abi in pins for name in ("libcsqtt25.so", "libcsqtt25_transport.so")}
    actual = set()
    for row in proof["artifacts"]:
        path = pathlib.Path(row["file"])
        identity = (row["abi"], path.name)
        require(identity in expected and identity not in actual and path == stage / row["abi"] / path.name,
                "Native artifact set/path changed")
        require(digest(path) == row["sha256"] and path.stat().st_size == row["bytes"]
                and row.get("executed") is False, "Native artifact changed")
        elf_class, machine = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}[row["abi"]]
        with path.open("rb") as stream:
            header = stream.read(20)
        require(header[:6] == bytes((127, 69, 76, 70, elf_class, 1))
                and int.from_bytes(header[16:18], "little") == 3
                and int.from_bytes(header[18:20], "little") == machine, "Native ELF ABI changed")
        if path.name == "libcsqtt25_transport.so":
            require(row["sha256"] == pins[row["abi"]]["sha256"] and row["bytes"] == pins[row["abi"]]["bytes"],
                    "Native transport generation changed")
        actual.add(identity)
    require(actual == expected, "Native artifact set incomplete")
    return {"transportPins": {abi: row["sha256"] for abi, row in pins.items()}}


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--native":
        print(json.dumps(native(sys.argv[2])))
    elif len(sys.argv) == 3 and sys.argv[1] == "--baseline":
        print(json.dumps(baseline(sys.argv[2])))
    elif len(sys.argv) == 4 and sys.argv[1] == "--prepared":
        print(json.dumps(prepared(sys.argv[2], sys.argv[3])))
    else:
        raise SystemExit("usage: p3_abi_provenance.py --baseline ACCEPTED | --prepared SOURCE ACCEPTED | --native STAGE")
