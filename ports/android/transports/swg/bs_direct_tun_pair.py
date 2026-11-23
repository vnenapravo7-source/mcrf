"""Prepare a fresh Go build with ONLY regenerated Rust ELF pins. Never compile/relabel binaries."""
import argparse
import hashlib
import json
import pathlib

import bs_engine_inputs as bs
import prepare_bs_wrapper as adapter
from csqtt_multicall_producer import verify_compiled_proof

PIN_FILE = "internal/csqtt25/mcrf_multicall.go"


def pin_source(rust):
    rows = [r for r in rust["artifacts"] if r["target"] != "host"]
    bs.require(len(rows) == len(bs.ABIS) and {r["abi"] for r in rows} == set(bs.ABIS),
               "Exact three-ABI Rust pins required")
    for row in rows:
        bs.require(type(row["bytes"]) is int and row["bytes"] > 0 and
                   len(row["sha256"]) == 64 and all(c in "0123456789abcdef" for c in row["sha256"]),
                   "Invalid Rust pin")
    template = bs.regular(pathlib.Path(__file__).with_name("bs_multicall_runtime.go.in")).read_text(encoding="utf-8")
    bs.require(template.count("// MCRF_NATIVE_PINS") == 1, "Native pin template changed")
    pins = "\n".join('    '+json.dumps(r['abi'])+': {'+json.dumps(r['sha256'])+', '+str(r['bytes'])+'},' for r in rows)
    return template.replace("// MCRF_NATIVE_PINS", pins).encode()


def only_pin_delta(previous, replacement, old_rust, new_rust):
    bs.require(set(previous) == set(replacement), "Go source inventory changed")
    changed = {name for name in previous if previous[name] != replacement[name]}
    bs.require(changed == {PIN_FILE}, "Only generated Rust ELF pins may change in the Go rebuild")
    bs.require(previous[PIN_FILE] == pin_source(old_rust) and replacement[PIN_FILE] == pin_source(new_rust),
               "Generated pin file contains a functional Go change")


def compiled_baseline(row):
    path = bs.bound(row)
    root = path.parent
    receipt = bs.load(path)
    bs.require(receipt["state"] == "COMPILED_NOT_EXECUTED" and receipt["sourceStable"] is True and
               receipt["kind"] == "csqtt" and receipt["bsModeContract"] == 3 and
               all(receipt[k] == 1 for k in ("bsTransportContract", "processGroupContract", "accountOptionsContract",
                                            "turnHashPoolContract", "csqtt25SrtpModeContract")), "SRTP5 compiled Go baseline required")
    bs.require(receipt["producerSha256"] == bs.sha(bs.ROOT / "transports/swg/Build-BsWrapper.ps1"),
               "Go producer changed; pin-only rebuild cannot cover it")
    proof_path = root / "bs-wrapper-source.json"
    bs.require(bs.sha(proof_path) == receipt["preparedSourceSha256"], "Baseline preparation changed")
    proof = bs.load(proof_path)
    bs.require(proof["modeContract"] == 3 and proof["adapterSha256"] == bs.sha(bs.ROOT / "transports/swg/prepare_bs_wrapper.py") and
               proof["helperSha256"] == bs.sha(bs.ROOT / "transports/swg/mcrf_bs_transport.go") and
               proof.get("canonicalProfileOverlays") == adapter.modern_profile_overlays(), "Baseline Go adapter/profile changed")
    bs.require(bs.sha(root / "source-manifest.json") == receipt["sourceManifestSha256"] and
               bs.load(root / "source-manifest.json") == proof["sources"], "Baseline Go manifest changed")
    source = pathlib.Path(proof["root"])
    expected = {r["path"] for r in proof["sources"]}
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file() and
              (p.suffix == ".go" or p.name in ("go.mod", "go.sum"))}
    bs.require(len(expected) == len(proof["sources"]) and actual == expected, "Baseline Go source inventory changed")
    files = {r["path"]: bs.bound(r, source).read_bytes() for r in proof["sources"]}
    bs.native_inventory(receipt, "wrapper")
    for artifact in receipt["artifacts"]:
        native = bs.bound({"path": artifact["file"], "sha256": artifact["sha256"]}, root)
        bs.require(artifact["file"] == artifact["abi"] + "/libqwdtt.so" and native.stat().st_size == artifact["bytes"],
                   "Baseline Go ELF changed")
        bs.elf(native, artifact["abi"])
    bs.require(bs.sha(root / "host-evidence.json") == receipt["hostEvidenceSha256"], "Baseline host evidence changed")
    host = bs.load(root / "host-evidence.json")
    bs.require(host["state"] == "HOST_CHECKS_PASSED" and host["preparedSourceSha256"] == receipt["preparedSourceSha256"] and
               host["producerSha256"] == bs.sha(bs.ROOT / "transports/swg/Invoke-BsWrapperHost.ps1") and
               bs.sha(root / "host-tests.jsonl") == host["logSha256"], "Baseline host proof not source-bound")
    from bs_multicall_inputs import SRTP_TESTS
    events = [json.loads(line) for line in bs.regular(root / "host-tests.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    required = set(bs.MODE_TESTS) | SRTP_TESTS | {"TestBSRustModeIsolation", "TestBSRustChildEnv", "TestBSModernMismatch",
                                               "TestBSRustParallelProcesses", "TestMcrfChildFailureForwardsOnlyBoundedTypedVKLimits"}
    bs.require(not any(e.get("Action") in ("fail", "skip") for e in events) and
               required <= {e.get("Test") for e in events if e.get("Action") == "pass"}, "Baseline Go/SRTP tests incomplete")
    baseline_path = pathlib.Path(receipt["baselineReceipt"])
    bs.require(bs.sha(baseline_path) == receipt["baselineReceiptSha256"] == bs.sha(root / "baseline-receipt.json"),
               "Baseline compilation ancestry changed")
    baseline = bs.load(baseline_path)
    bs.require(baseline["sourceStable"] is True and baseline["kind"] == "csqtt" and
               baseline["sourceManifestSha256"] == proof["baselineManifestSha256"], "Baseline source ancestry changed")
    # Historical Rust is read only to reconstruct the already compiled Go pins. It NEVER
    # grants a new capability. The replacement must independently pass the CURRENT producer.
    old_rust = bs.load(bs.bound(proof["rust"]))
    for artifact in old_rust["artifacts"]:
        native = bs.bound({"path": artifact["path"], "sha256": artifact["sha256"]})
        bs.require(native.stat().st_size == artifact["bytes"], "Historical Rust pin evidence changed")
        if artifact["target"] != "host": bs.elf(native, artifact["abi"])
    return receipt, proof, files, old_rust


def expected_pair(baseline_row, rust_row):
    receipt, old, previous, old_rust = compiled_baseline(baseline_row)
    rust = verify_compiled_proof(rust_row, all_abis=True)  # Includes actual source-bound Unix proof; no opt-out.
    bs.require(type(rust.get("directTunFdContract")) is int and rust["directTunFdContract"] in (1,2),
               "New Rust direct-FD capability required")
    source, rows = adapter.selected_sources(pathlib.Path(old["baselineSource"]), old["baselineManifest"],
        old["baselineManifestSha256"], old["superset"], old["duplicate"])
    replacement = adapter.adapted_sources(source, rows, True, rust_row)
    only_pin_delta(previous, replacement, old_rust, rust)
    return receipt, old, replacement


def verify_pairing(prepared, rust_row):
    pair = prepared.get("pinOnlyRebuild", {})
    bs.require(pair.get("schema") == 1 and pair.get("producerSha256") == bs.sha(__file__) and
               pair.get("rust") == rust_row and pair.get("changedSources") == [PIN_FILE], "Pin-only pairing receipt missing/changed")
    receipt, old, files = expected_pair(pair["baselineCompiled"], rust_row)
    bs.require(pair.get("baselinePreparedSourceSha256") == receipt["preparedSourceSha256"] and
               pair.get("baselineGoArtifacts") == receipt["artifacts"] and
               all(prepared[k] == old[k] for k in ("baselineSource", "baselineManifest", "baselineManifestSha256", "superset", "duplicate")),
               "Pin-only build changed baseline/Go identity")
    rows = {r["path"]: r["sha256"] for r in prepared["sources"]}
    bs.require(len(rows) == len(prepared["sources"]) and rows ==
               {name: hashlib.sha256(content).hexdigest() for name, content in files.items()}, "Paired source is not the pin-only rebuild")
    for name, digest in rows.items(): bs.bound({"path": name, "sha256": digest}, pathlib.Path(prepared["root"]))


def prepare(baseline_row, rust_row, output):
    receipt, old, _ = expected_pair(baseline_row, rust_row)
    output = pathlib.Path(output).absolute()
    bs.require(not output.exists(), "Fresh Go pairing source directory required")
    adapter.prepare(old["baselineSource"], old["baselineManifest"], old["baselineManifestSha256"], output,
                    old["superset"], old["duplicate"], rust_row)
    path = output / "bs-wrapper-source.json"
    proof = bs.load(path)
    proof["pinOnlyRebuild"] = {"schema": 1, "producerSha256": bs.sha(__file__), "baselineCompiled": baseline_row,
        "baselinePreparedSourceSha256": receipt["preparedSourceSha256"], "baselineGoArtifacts": receipt["artifacts"],
        "rust": rust_row, "changedSources": [PIN_FILE]}
    verify_pairing(proof, rust_row)
    path.write_text(json.dumps(proof, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"state": "PIN_ONLY_GO_REBUILD_PREPARED_NOT_COMPILED", "proof": str(path), "sha256": bs.sha(path)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline-receipt", "baseline-sha256", "rust-receipt", "rust-sha256", "output"):
        parser.add_argument("--"+name, required=True)
    parser.add_argument("--rust-post-build")
    parser.add_argument("--rust-post-build-sha256")
    args = parser.parse_args()
    bs.require(bool(args.rust_post_build)==bool(args.rust_post_build_sha256),"Complete post-build proof pin required")
    rust={"path": args.rust_receipt, "sha256": args.rust_sha256}
    if args.rust_post_build:
        rust["postBuildEvidence"]={"path":args.rust_post_build,"sha256":args.rust_post_build_sha256}
    prepare({"path": args.baseline_receipt, "sha256": args.baseline_sha256},
            rust, args.output)
