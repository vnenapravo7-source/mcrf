"""One-ABI loader update with exact source and retained binary provenance."""
import pathlib
import re
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import bs_engine_inputs as bs
from csqtt25 import aligned_inputs as a
from csqtt25 import versioned_inputs as v

TEST = "aligned_native_inputs_test.go"


def expected_versions(old):
    pin = bs.load(a.LOCK)
    before = b'"f035907d404e93de2102f0dbb5240439a59bd31d8ea01d46fa380af3615b1c5d",6823768,1,40'
    after = ('"' + pin["sha256"] + '",' + str(pin["bytes"]) + ',1,40').encode()
    bs.require(old.count(before) == 1, "Retained ARMv7 selector identity changed")
    return old.replace(before, after)


def prepare(retained, versions, output):
    retained, versions, output = map(lambda p: pathlib.Path(p).absolute(), (retained, versions, output))
    a.verify_versions(versions)
    bs.require(bs.sha(retained / "receipt.json") == a.WRAPPER_RECEIPT, "Exact accepted loader stage required")
    base = bs.load(retained / "receipt.json")
    bs.require(base["kind"] == "csqtt25" and base["state"] == "COMPILED_NOT_EXECUTED" and base["sourceStable"] is True and
               base["processGroupContract"] == base["turnHashPoolContract"] == 1, "Retained loader contracts missing")
    bs.require(bs.sha(retained / "source-manifest.json") == base["sourceManifestSha256"], "Retained loader source proof changed")
    rows = bs.load(retained / "source-manifest.json")
    module = pathlib.Path(base["source"])
    a.inventory(module, rows)
    old_versions = bs.regular(module / "internal/csqtt25/versions.go").read_bytes()
    bs.require(expected_versions(old_versions) == bs.regular(HERE / "versions.go").read_bytes(), "Canonical loader delta is not solely ARMv7 digest/size")
    bs.require(not output.exists(), "Fresh loader stage required")
    output.mkdir(parents=True)
    for row in rows:
        source = bs.bound(row, module)
        target = output / "module" / row["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    shutil.copyfile(HERE / "versions.go", output / "module/internal/csqtt25/versions.go")
    shutil.copyfile(HERE / TEST, output / "module/internal/csqtt25" / TEST)
    shutil.copyfile(retained / "source-manifest.json", output / "retained-source-manifest.json")
    shutil.copyfile(retained / "receipt.json", output / "retained-receipt.json")
    shutil.copyfile(module / "internal/csqtt25/versions.go", output / "retained-versions.go")
    new_rows = [{"path": p.relative_to(output / "module").as_posix(), "sha256": bs.sha(p)}
                for p in sorted((output / "module").rglob("*")) if p.is_file()]
    v.write_json(output / "source-manifest.json", new_rows)
    proof = {"state": "ALIGNED_LOADER_SOURCE_PREPARED", "retainedStage": str(retained),
             "retainedReceiptSha256": a.WRAPPER_RECEIPT, "retainedManifestSha256": base["sourceManifestSha256"],
             "sourceManifestSha256": bs.sha(output / "source-manifest.json"), "versionStage": str(versions),
             "versionReceiptSha256": bs.sha(versions / "receipt.json"), "producerSha256": bs.sha(__file__)}
    v.write_json(output / "loader-source-proof.json", proof)
    verify_source(output)


def verify_source(root):
    proof = bs.load(root / "loader-source-proof.json")
    bs.require(proof["state"] == "ALIGNED_LOADER_SOURCE_PREPARED" and proof["producerSha256"] == bs.sha(__file__), "Loader source producer changed")
    retained = pathlib.Path(proof["retainedStage"])
    bs.require(bs.sha(retained / "receipt.json") == bs.sha(root / "retained-receipt.json") == proof["retainedReceiptSha256"] == a.WRAPPER_RECEIPT,
               "Retained loader receipt changed")
    base = bs.load(retained / "receipt.json")
    bs.require(bs.sha(retained / "source-manifest.json") == bs.sha(root / "retained-source-manifest.json") == proof["retainedManifestSha256"] == base["sourceManifestSha256"],
               "Retained loader manifest changed")
    old_rows = bs.load(root / "retained-source-manifest.json")
    a.inventory(pathlib.Path(base["source"]), old_rows)
    bs.require(bs.sha(root / "source-manifest.json") == proof["sourceManifestSha256"], "Loader source manifest changed")
    rows = bs.load(root / "source-manifest.json")
    a.inventory(root / "module", rows)
    old, current = {r["path"]: r["sha256"] for r in old_rows}, {r["path"]: r["sha256"] for r in rows}
    delta = {"internal/csqtt25/versions.go", "internal/csqtt25/" + TEST}
    bs.require(set(current) == set(old) | delta and all(current[p] == old[p] for p in set(old) - delta), "Unrelated loader source changed")
    old_versions = bs.regular(root / "retained-versions.go").read_bytes()
    bs.require(bs.sha(root / "retained-versions.go") == old["internal/csqtt25/versions.go"] and
               expected_versions(old_versions) == bs.regular(root / "module/internal/csqtt25/versions.go").read_bytes() == bs.regular(HERE / "versions.go").read_bytes() and
               current["internal/csqtt25/" + TEST] == bs.sha(HERE / TEST), "Loader delta changed")
    versions = pathlib.Path(proof["versionStage"])
    bs.require(bs.sha(versions / "receipt.json") == proof["versionReceiptSha256"], "Loader version inputs changed")
    a.verify_versions(versions)
    # Preserve every original artifact, including the old ARMv7 loader, as proof.
    for row in base["artifacts"]:
        name = pathlib.Path(row["file"].replace("\\", "/")).name
        file = retained / row["abi"] / name
        bs.require(bs.sha(file) == row["sha256"] and file.stat().st_size == row["bytes"], "Retained native identity changed")
        bs.elf(file, row["abi"])
    return proof, base


def verify(root, versions):
    proof, base = verify_source(root)
    receipt = bs.load(root / "receipt.json")
    bs.require(receipt["state"] == "COMPILED_NOT_EXECUTED" and receipt["kind"] == "csqtt25" and receipt["sourceStable"] is True and
               receipt["compiledABIs"] == ["armeabi-v7a"] and receipt["reusedABIs"] == ["arm64-v8a", "x86_64"] and
               receipt["producerSha256"] == bs.sha(bs.ROOT / "maintenance/Build-AlignedSwgLoader.ps1") and
               receipt["sourceManifestSha256"] == proof["sourceManifestSha256"] and
               receipt["sourceProofSha256"] == bs.sha(root / "loader-source-proof.json") and
               receipt["processGroupContract"] == receipt["turnHashPoolContract"] == 1, "Aligned loader receipt changed")
    bs.require(bs.sha(versions / "receipt.json") == proof["versionReceiptSha256"], "Different version stage supplied")
    bs.require(bs.sha(root / "host-evidence.json") == receipt["hostEvidenceSha256"], "Loader host proof changed")
    host = bs.load(root / "host-evidence.json")
    bs.require(host["state"] == "HOST_CHECKS_PASSED" and host["sourceManifestSha256"] == proof["sourceManifestSha256"] and
               bs.sha(root / "host-tests.jsonl") == host["logSha256"], "Loader host source/log mismatch")
    events = [__import__("json").loads(line) for line in bs.regular(root / "host-tests.jsonl").read_text(encoding="utf-8-sig").splitlines() if line]
    required = {"TestAlignedSwgNativeActualArtifacts/" + abi for abi in bs.ABIS}
    required |= {"TestSwgVersionsSelectExactTransportWithoutLegacyFallback", "TestNewSwgProfilesKeepImplementationAndRejectDuplicateOrWrongTypes",
                 "TestNewSwgNativePinsAreDistinctFrom251ForEveryAbi", "TestNewSwgGateRejectsOldOrUnknownContentUnderNewFilename"}
    bs.require(not any(e.get("Action") in ("fail", "skip") for e in events) and required <= {e.get("Test") for e in events if e.get("Action") == "pass"},
               "Aligned loader host cases missing/failed/skipped")
    retained = pathlib.Path(proof["retainedStage"])
    expected = {(r["abi"], pathlib.Path(r["file"].replace("\\", "/")).name): r for r in base["artifacts"]}
    seen = set()
    for row in receipt["artifacts"]:
        key = (row["abi"], pathlib.Path(row["file"].replace("\\", "/")).name)
        bs.require(key in expected and key not in seen, "Loader native inventory changed")
        seen.add(key)
        target = root / key[0] / key[1]
        bs.require(bs.sha(target) == row["sha256"] and target.stat().st_size == row["bytes"], "Loader native bytes changed")
        bs.elf(target, key[0])
        if key != ("armeabi-v7a", "libcsqtt25.so"):
            bs.require(row["sha256"] == expected[key]["sha256"] == bs.sha(retained / key[0] / key[1]), "Unchanged ABI was replaced")
    bs.require(seen == set(expected), "Loader native inventory incomplete")
    # Actual source provenance is per ABI: only ARMv7 consumes the changed pin.
    for abi in bs.ABIS:
        text = bs.regular(root / ("module/internal/csqtt25/versions.go" if abi == "armeabi-v7a" else "retained-versions.go")).read_text(encoding="utf-8")
        match = re.search(r'case "' + re.escape(abi) + r'": return nativeDescriptor\{abi,"[^"]+","([a-f0-9]{64})",([0-9]+),', text)
        native = versions / abi / v.LIBRARY
        bs.require(match and match.group(1) == bs.sha(native) and int(match.group(2)) == native.stat().st_size, "Compiled ABI loader pin differs from packaged transport")
    return {"compiledABIs": ["armeabi-v7a"], "reusedABIs": ["arm64-v8a", "x86_64"]}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--retained")
    parser.add_argument("--versions")
    parser.add_argument("--output")
    parser.add_argument("--verify-source")
    parser.add_argument("--verify")
    args = parser.parse_args()
    if args.verify_source:
        verify_source(pathlib.Path(args.verify_source))
    elif args.verify:
        verify(pathlib.Path(args.verify), pathlib.Path(args.versions))
    else:
        prepare(args.retained, args.versions, args.output)
