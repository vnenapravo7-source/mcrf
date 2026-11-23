"""Freeze explicit native stage receipts; never discover APK or Gradle outputs."""
import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
import bs_engine_inputs as bs


def freeze(stages, output, version_code, layout=bs.LAYOUT):
    bs.require_layout(layout)
    if layout==bs.LAYOUT:
        from bs_multicall_inputs import freeze as freeze_multicall
        return freeze_multicall(stages,output,version_code)
    bs.require(set(stages) == set(bs.KINDS) | {"wrapper", "versions"}, "Exact BS inputs required; WDTT is standalone")
    output = pathlib.Path(output).absolute()
    pending = output.with_name(output.name + ".pending")
    bs.require(not output.exists() and not pending.exists(), "Fresh frozen gate required")
    inputs = {}
    for kind, stage in stages.items():
        stage = pathlib.Path(stage).absolute()
        receipt = stage / "receipt.json"
        proof = stage / ("bs-wrapper-source.json" if kind == "wrapper" else
                         "source-manifest.json" if kind in ("csqtt20", "csqtt215") else
                         "receipt.json" if kind == "versions" else "source-manifest.json")
        inputs[kind] = {"path": str(receipt), "sha256": bs.sha(receipt),
                        "sourceProof": {"path": str(proof), "sha256": bs.sha(proof)}}
    paths = {
        "transports/swg/bs_engine_inputs.py", "transports/swg/prepare_bs_wrapper.py",
        "transports/swg/mcrf_bs_transport.go", "transports/swg/mcrf_bs_transport_test.go",
        "transports/swg/Build-BsWrapper.ps1", "transports/swg/Build-BsBaseline.ps1",
        "transports/swg/Invoke-BsWrapperHost.ps1", "transports/swg/csqtt25/versioned_inputs.py",
        "transports/swg/csqtt25/swg-versions.json", "engines/EngineUpdateBsManifest.xml",
        "engines/build.gradle.kts", "maintenance/Package-BsEngine.ps1",
        "maintenance/freeze_bs_inputs.py",
        "maintenance/prepare_swg_armv7.py", "maintenance/Build-SwgVersionArmv7.ps1",
        "maintenance/Build-AlignedSwgLoader.ps1", "transports/swg/csqtt25/aligned_inputs.py",
        "transports/swg/csqtt25/aligned_wrapper.py", "transports/swg/csqtt25/swg-armv7-alignment.json",
        "transports/swg/csqtt25/aligned_native_inputs_test.go",
        "maintenance/recover_bs_source_stages.py", "maintenance/finalize_recovered_wdtt.py",
    }
    wrapper = bs.load(pathlib.Path(stages["wrapper"]) / "receipt.json")
    baseline = pathlib.Path(wrapper["baselineReceipt"]).parent
    transitions = []
    baseline_receipt = bs.load(baseline / "receipt.json")
    if "canonicalPreparedSha256" in baseline_receipt:
        preparation = bs.load(baseline / "canonical-prepared-source.json")
        for name, expected in preparation["canonicalInputs"].items():
            relative = "transports/swg/" + name.replace("\\", "/")
            actual = bs.sha(ROOT / relative)
            if actual != expected:
                # Legacy preparation snapshots also inventory unused generation-25
                # files. Preserve that historical proof; the actual legacy Go input
                # inventory must not consume this file. Independently verify the
                # exact ARMv7-only modern loader delta and retained per-ABI binaries.
                bs.require(relative == "transports/swg/csqtt25/versions.go" and
                           expected == "b1c387a5142b040f4e7d79b808f90a3c802a0cee35dc08458fba06e8f64e436a",
                           "Canonical baseline preparation drift")
                manifest = bs.load(baseline / "source-manifest.json")
                bs.require(not any(r["path"].startswith("internal/csqtt25/") for r in manifest), "Changed selector belongs to legacy baseline")
                from csqtt25 import aligned_wrapper
                aligned_wrapper.verify(pathlib.Path(stages["csqtt25"]), pathlib.Path(stages["versions"]))
                transitions.append({"path": relative, "historicalSha256": expected, "currentSha256": actual,
                                    "reason": "Exact ARMv7-only modern loader pin delta; absent from compiled legacy baseline"})
            paths.add(relative)
    gate = {"schema": 2, "layout": bs.SPLIT_LAYOUT, "state": "BS_SOURCE_BOUND_INPUTS_READY",
            "package": "org.mcrf.engines.bs", "versionCode": version_code,
            "logicalEngines": list(bs.KINDS), "inputs": inputs,
            "canonicalSources": [{"path": p, "sha256": bs.sha(ROOT / p)} for p in sorted(paths)],
            "retainedPreparationTransitions": transitions,
            "notices": bs.notice_inputs({kind: pathlib.Path(stage) for kind, stage in stages.items()})}
    output.parent.mkdir(parents=True, exist_ok=True)
    pending.write_text(json.dumps(gate, indent=2) + "\n", encoding="utf-8")
    digest = bs.sha(pending)
    bs.inspect(pending, digest)
    pending.rename(output)
    print(json.dumps({"gate": str(output), "sha256": digest, "verifier": "PASS", "abis": list(bs.ABIS)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    stage_options=(*bs.KINDS, "wrapper", "versions", "rust", "rust-post-build", "rust-private-udp")
    for name in stage_options:
        parser.add_argument("--" + name, dest=name)
    parser.add_argument("--output", required=True)
    parser.add_argument("--layout", default=bs.LAYOUT, choices=(bs.LAYOUT, bs.SPLIT_LAYOUT))
    parser.add_argument("--version-code", type=int, required=True)
    args = parser.parse_args()
    freeze({name: getattr(args, name) for name in stage_options if getattr(args,name)}, args.output, args.version_code, args.layout)
