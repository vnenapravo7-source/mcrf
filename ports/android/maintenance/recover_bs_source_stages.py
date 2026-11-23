"""Create fresh source-present BS stages without relabelling retained receipts.

Retained native bytes are copied only after their manifests are reconstructed
from the canonical accepted source snapshots. Historical Rust transports use
their already source-bound current rebuilds, paired with the retained Go bridge.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import uuid


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8-sig"))


def save(path, value):
    pathlib.Path(path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def require(ok, message):
    if not ok:
        raise ValueError(message)


def manifest(root):
    root = pathlib.Path(root)
    return [{"path": p.relative_to(root).as_posix(), "sha256": sha(p)}
            for p in sorted(root.rglob("*")) if p.is_file() and ".git" not in p.relative_to(root).parts]


def reconstruct_go(kind, origin, destination, apply_script):
    origin, destination = pathlib.Path(origin), pathlib.Path(destination)
    prepared = load(origin / "prepared-expansion-source.json")
    require(prepared["kind"] == kind and prepared["sourceStable"] is True,
            "Wrong canonical expansion source")
    source = origin / ("wdtt/go_client" if kind == "wdtt" else "qwdtt/qwdtt")
    producer = pathlib.Path("D:/MCRF/Builds/Android") / f"p3-turn-source-{uuid.uuid4().hex}"
    work = producer / "module"
    work.mkdir(parents=True)
    for row in prepared["inputs"]:
        incoming = source / row["path"]
        require(incoming.is_file() and sha(incoming) == row["sha256"],
                "Canonical expansion input changed")
        output = work / row["path"]
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(incoming, output)
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", str(apply_script), "-ModuleSource", str(work),
                    "-Kind", kind], check=True)
    shutil.copytree(work, destination)


def reconstruct_wdtt_from_manifest(retained, origin, source_map, destination):
    retained, origin, destination = map(pathlib.Path, (retained, origin, destination))
    expected = load(retained / "source-manifest.json")
    found = load(source_map)
    destination.mkdir(parents=True)
    for row in expected:
        if row["path"] == "main.go":
            incoming = retained / "masque-main-input.go"
        elif row["path"] == "go.mod":
            incoming = origin / "wdtt/go_client/go.mod"
        else:
            incoming = pathlib.Path(found[row["path"]])
        output = destination / row["path"]
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(incoming, output)
        if row["path"] != "go.mod":
            require(sha(output) == row["sha256"], "Recovered WDTT source byte changed")
    # The only unavailable byte is formatting/order in go.mod. Its dependency
    # graph is recovered from the pinned canonical revision; this tree must be
    # rebuilt and may never be paired with the retained native bytes.
    actual = {row["path"]: row["sha256"] for row in manifest(destination)}
    wanted = {row["path"]: row["sha256"] for row in expected}
    mismatches = [(path, actual.get(path), digest) for path, digest in wanted.items()
                  if actual.get(path) != digest]
    require(set(actual) == set(wanted) and len(mismatches) == 1 and
            mismatches[0][0] == "go.mod", "WDTT recovery delta exceeds canonical go.mod")


def reconstruct_exact_from_map(retained, source_map, destination):
    retained, destination = pathlib.Path(retained), pathlib.Path(destination)
    rows, found = load(retained / "source-manifest.json"), load(source_map)
    destination.mkdir(parents=True)
    for row in rows:
        incoming = pathlib.Path(found[row["path"]])
        require(incoming.is_file() and sha(incoming) == row["sha256"],
                "Content-addressed source recovery changed")
        output = destination / row["path"]
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(incoming, output)


def source_present_copy(retained, source, destination):
    retained, source, destination = map(pathlib.Path, (retained, source, destination))
    require(not destination.exists(), "Fresh recovery stage required")
    shutil.copytree(retained, destination)
    local_source = destination / "module"
    shutil.copytree(source, local_source)
    rows = manifest(local_source)
    expected = load(destination / "source-manifest.json")
    require({r["path"]: r["sha256"] for r in rows} ==
            {r["path"]: r["sha256"] for r in expected},
            "Reconstructed Go source differs from retained manifest")
    receipt = load(destination / "receipt.json")
    receipt["source"] = str(local_source)
    receipt["sourceRecovery"] = {"schema": 1, "manifestSha256": sha(destination / "source-manifest.json")}
    save(destination / "receipt.json", receipt)


def historical(kind, native_stage, retained_package, destination):
    native_stage, retained_package, destination = map(pathlib.Path,
                                                       (native_stage, retained_package, destination))
    require(not destination.exists(), "Fresh historical recovery stage required")
    native = load(native_stage / "receipt.json")
    retained = load(retained_package / "receipt.json")
    require(native["engine"] == kind and native["state"] == "COMPILED_NOT_EXECUTED" and
            native["sourceStable"] is True and retained["kind"] == kind,
            "Wrong historical source/native pair")
    destination.mkdir(parents=True)
    shutil.copy2(retained_package / "wrapper-receipt.json", destination / "wrapper-receipt.json")
    shutil.copy2(native_stage / "receipt.json", destination / "native-receipt.json")
    shutil.copytree(native_stage / "source", destination / "source")
    shutil.copy2(native_stage / "source-manifest.json", destination / "source-manifest.json")
    rows = load(destination / "source-manifest.json")
    require({r["path"]: r["sha256"] for r in manifest(destination / "source")} ==
            {r["path"]: r["sha256"] for r in rows}, "Historical source tree changed")
    artifacts = []
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        out = destination / abi
        out.mkdir()
        wrapper_name = f"lib{kind}.so"
        wrapper = retained_package / abi / wrapper_name
        transport = native_stage / "candidates" / abi / "libcsqtt_transport.so"
        shutil.copy2(wrapper, out / wrapper_name)
        shutil.copy2(transport, out / "libcsqtt_transport.so")
        for name in (wrapper_name, "libcsqtt_transport.so"):
            path = out / name
            artifacts.append({"abi": abi, "path": f"{abi}/{name}",
                              "bytes": path.stat().st_size, "sha256": sha(path)})
    result = dict(retained)
    result.update({"state": "HISTORICAL_ENGINE_INPUTS_READY", "sourceStable": True,
                   "source": str(destination / "source"),
                   "sourceManifestSha256": sha(destination / "source-manifest.json"),
                   "wrapperReceiptSha256": sha(destination / "wrapper-receipt.json"),
                   "nativeReceiptSha256": sha(destination / "native-receipt.json"),
                   "artifacts": artifacts,
                   "sourceRecovery": {"schema": 1, "revision": native["revision"]}})
    save(destination / "receipt.json", result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--retained", required=True)
    parser.add_argument("--qwdtt-origin", required=True)
    parser.add_argument("--qwdtt-source-map", required=True)
    parser.add_argument("--wdtt-origin", required=True)
    parser.add_argument("--wdtt-source-map", required=True)
    parser.add_argument("--csqtt20-native", required=True)
    parser.add_argument("--csqtt215-native", required=True)
    args = parser.parse_args()
    output, retained = pathlib.Path(args.output), pathlib.Path(args.retained)
    require(not output.exists(), "Fresh recovery root required")
    output.mkdir(parents=True)
    apply_script = pathlib.Path(__file__).resolve().parents[1] / "transports/swg/performance/Apply-PerformanceSource.ps1"
    reconstruct_exact_from_map(retained / "native-stage-qwdtt", args.qwdtt_source_map,
                               output / "qwdtt-source")
    reconstruct_wdtt_from_manifest(retained / "native-stage-wdtt", args.wdtt_origin,
                                   args.wdtt_source_map, output / "wdtt-source")
    for kind in ("csqtt-legacy", "qwdtt"):
        source_present_copy(retained / f"native-stage-{kind}", output / "qwdtt-source",
                            output / f"native-stage-{kind}")
    historical("csqtt20", args.csqtt20_native, retained / "native-stage-csqtt20",
               output / "native-stage-csqtt20")
    historical("csqtt215", args.csqtt215_native, retained / "native-stage-csqtt215",
               output / "native-stage-csqtt215")
    print(output)
