"""Strict source-built ARMv7 replacement; other ABI bytes remain upstream."""
import hashlib
import json
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import bs_engine_inputs as bs

LOCK = HERE / "swg-armv7-alignment.json"
UPSTREAM_RECEIPT = "2d7fdcf3e6eece44d56f19d862ba6547a708c86c08f9816b487414721120df30"
WRAPPER_RECEIPT = "64cde8af20f0cc4be0d85db1a9caefaf91f6997a49ea9f1a92c099ee3a9b9888"
DOMAINS_BLOB = "d52d29657e34ffa13544323087ed7f20b344737a"
DOMAINS_SHA = "3783f488db5378a9d91120c43f874bf70cab62bd0a799b6951f7859a492cecd1"
PREVIOUS_VERSION_PRODUCER_SHA = "446b6307092586b187c6ed4c4e95e550cdee899c7f7fccc798be0d060bd3504c"


def inventory(root, rows):
    bs.require(len(rows) == len({r["path"] for r in rows}), "Duplicate source identity")
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()}
    bs.require(actual == {r["path"] for r in rows}, "Source inventory changed")
    for row in rows:
        bs.bound(row, root)


def verify_source(root):
    """Verify the original recovered sources, independent of the rebuilt ELF."""
    from csqtt25 import versioned_inputs as v
    lock = bs.load(LOCK)
    root = pathlib.Path(root).absolute()
    bs.require(bs.sha(root / "receipt.json") == lock["receiptSha256"], "Aligned native receipt changed")
    receipt = bs.load(root / "receipt.json")
    bs.require(receipt["state"] == "COMPILED_NOT_EXECUTED" and receipt["kind"] == "csqtt252-armv7-aligned" and
               receipt["sourceStable"] is True and receipt["nativeExecuted"] is False and
               receipt["producerSha256"] == bs.sha(bs.ROOT / "maintenance/Build-SwgVersionArmv7.ps1"), "Aligned native producer/state changed")
    bs.require(bs.sha(root / "source-proof.json") == receipt["sourceProofSha256"] and
               bs.sha(root / "source-manifest.json") == receipt["sourceManifestSha256"], "Aligned source proof changed")
    proof = bs.load(root / "source-proof.json")
    bs.require(proof["state"] == "PINNED_RELEASE_SOURCE_RECOVERED" and proof["lockSha256"] == bs.sha(v.LOCK) and
               proof["revision"] == v.metadata()["versions"]["2.5.3"]["revision"] and
               proof["producerSha256"] == bs.sha(bs.ROOT / "maintenance/prepare_swg_armv7.py") and
               proof["sourceManifestSha256"] == receipt["sourceManifestSha256"] and
               bs.sha(root / "release-source.zip") == proof["archiveSha256"], "Canonical source recovery changed")
    upstream = pathlib.Path(proof["upstreamStage"])
    bs.require(bs.sha(upstream / "receipt.json") == proof["upstreamReceiptSha256"] == receipt["upstreamReceiptSha256"] == UPSTREAM_RECEIPT and
               bs.sha(root / "upstream-receipt.json") == UPSTREAM_RECEIPT, "Retained upstream source changed")
    v.verify(upstream)
    rows = bs.load(root / "source-manifest.json")
    inventory(root / "source", rows)
    original = bs.load(upstream / "receipt.json")["files"]
    prefix = "source/2.5.3/"
    expected = {n[len(prefix):]: sha for n, sha in original.items() if n.startswith(prefix)}
    expected["domains.txt"] = DOMAINS_SHA
    bs.require({r["path"]: r["sha256"] for r in rows} == expected, "Source delta is not solely canonical domains.txt")
    data = bs.regular(root / "source/domains.txt").read_bytes()
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    bs.require(blob == proof["domainsGitBlob"] == DOMAINS_BLOB and len(data) == proof["domainsBytes"] == 5708 and
               hashlib.sha256(data).hexdigest() == proof["domainsSha256"] == DOMAINS_SHA, "Canonical domains blob changed")
    return proof, receipt


def verify_native(root):
    root = pathlib.Path(root).absolute()
    proof, receipt = verify_source(root)
    lock = bs.load(LOCK)
    bs.require(len(receipt["artifacts"]) == 1, "Only ARMv7 may be rebuilt")
    row = receipt["artifacts"][0]
    bs.require(row["abi"] == "armeabi-v7a" and row["file"] == "armeabi-v7a/libcsqtt252_transport.so" and
               row["sha256"] == lock["sha256"] and row["bytes"] == lock["bytes"] and
               row["nativeExecuted"] is False, "Aligned artifact identity changed")
    native = bs.bound({"path": row["file"], "sha256": row["sha256"]}, root)
    bs.require(native.stat().st_size == row["bytes"], "Aligned native size changed")
    bs.elf(native, "armeabi-v7a")
    return proof, receipt


def prepare_versions(upstream, native, output):
    from csqtt25 import versioned_inputs as v
    upstream, native, output = map(lambda p: pathlib.Path(p).absolute(), (upstream, native, output))
    proof, _ = verify_native(native)
    bs.require(bs.sha(upstream / "receipt.json") == proof["upstreamReceiptSha256"], "Different upstream stage")
    bs.require(not output.exists(), "Fresh aligned version stage required")
    output.mkdir(parents=True)
    for abi in bs.ABIS:
        source = (native if abi == "armeabi-v7a" else upstream) / abi / v.LIBRARY
        bs.elf(source, abi)
        (output / abi).mkdir()
        shutil.copyfile(source, output / abi / v.LIBRARY)
    shutil.copytree(upstream / "licenses", output / "licenses")
    files = {p.relative_to(output).as_posix(): bs.sha(p) for p in output.rglob("*") if p.is_file()}
    v.write_json(output / "receipt.json", {"schema": 1, "state": "SOURCE_ALIGNED_INPUTS_READY",
        "versions": ["2.5.2", "2.5.3"], "compiledABIs": ["armeabi-v7a"], "executed": False,
        "upstreamStage": str(upstream), "upstreamReceiptSha256": UPSTREAM_RECEIPT,
        "nativeStage": str(native), "nativeReceiptSha256": bs.sha(native / "receipt.json"),
        "alignmentLockSha256": bs.sha(LOCK), "producerSha256": bs.sha(__file__), "files": files})
    verify_versions(output)


def verify_versions(stage, wrapper=None):
    from csqtt25 import versioned_inputs as v
    stage = pathlib.Path(stage)
    proof = bs.load(stage / "receipt.json")
    bs.require(proof["schema"] == 1 and proof["state"] == "SOURCE_ALIGNED_INPUTS_READY" and proof["versions"] == ["2.5.2", "2.5.3"] and
               proof["compiledABIs"] == ["armeabi-v7a"] and proof["executed"] is False and
               proof["producerSha256"] in (bs.sha(__file__), PREVIOUS_VERSION_PRODUCER_SHA) and
               proof["alignmentLockSha256"] == bs.sha(LOCK), "Wrong aligned version receipt")
    upstream, native = pathlib.Path(proof["upstreamStage"]), pathlib.Path(proof["nativeStage"])
    bs.require(bs.sha(upstream / "receipt.json") == proof["upstreamReceiptSha256"] == UPSTREAM_RECEIPT and
               bs.sha(native / "receipt.json") == proof["nativeReceiptSha256"], "Aligned input root changed")
    verify_native(native)
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file()} - {"receipt.json"}
    bs.require(actual == set(proof["files"]), "Aligned version file set changed")
    for name, sha in proof["files"].items():
        bs.bound({"path": name, "sha256": sha}, stage)
    for abi in bs.ABIS:
        source = (native if abi == "armeabi-v7a" else upstream) / abi / v.LIBRARY
        target = stage / abi / v.LIBRARY
        bs.require(bs.sha(target) == bs.sha(source), "Wrong aligned ABI payload")
        bs.elf(target, abi)
    bs.require(bs.regular(stage / "licenses/swg-versions.json").read_bytes() == v.regular(v.LOCK), "Aligned version notice changed")
    bs.require(bs.sha(stage / "licenses/CSQTT-LICENSE") == v.metadata()["licenseSha256"], "Aligned license changed")
    if wrapper:
        verify_wrapper(pathlib.Path(wrapper), stage)
    return {"versions": proof["versions"], "library": v.LIBRARY, "sourceBuiltABIs": ["armeabi-v7a"]}


def verify_wrapper(root, version_stage):
    # Implemented alongside the one-ABI source producer below. No unchecked
    # path/receipt can authorize a replacement loader.
    from csqtt25 import aligned_wrapper
    return aligned_wrapper.verify(root, version_stage)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--native", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    prepare_versions(args.upstream, args.native, args.output)
