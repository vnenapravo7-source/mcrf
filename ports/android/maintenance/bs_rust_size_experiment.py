"""Paired builds in fresh copies of exact pinned sources. No Gradle/APK or frozen writes.

No performance claim is made by compilation. Measurements must be bound to the
two resulting native hashes; incomplete, nonfinite or regressing cases reject.
"""
import argparse
import hashlib
import json
import math
import os
import pathlib
import shutil
import subprocess
import sys
import time
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
from bs_engine_inputs import ABIS, bound, load, regular, require, sha

THRESHOLDS = {"compressedSizeRatioMax": 0.90, "throughputRatioMin": 0.97,
              "p95RatioMax": 1.05, "coldStartRatioMax": 1.10}
SIZE_PROFILE = {"LTO": "fat", "OPT_LEVEL": "z", "PANIC": "abort", "CODEGEN_UNITS": "1"}
TARGETS = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7-linux-androideabi", "x86_64": "x86_64-linux-android"}
CASES = {"wire", "captcha", "timeout", "shutdown", "parallel-generations"}


def source_inventory(source):
    source = pathlib.Path(source).absolute()
    require(source.is_dir(), "Source directory required")
    # Reject links even if they occur in a filtered build or VCS directory.
    rows = []
    for directory, dirs, files in os.walk(source, followlinks=False):
        base = pathlib.Path(directory)
        for name in dirs + files:
            p = base / name
            require(not p.is_symlink() and not (hasattr(p, "is_junction") and p.is_junction()), "Source link/junction")
        dirs[:] = [d for d in dirs if d not in (".git", "target")]
        for name in files:
            p = base / name
            rows.append({"path": p.relative_to(source).as_posix(), "sha256": sha(p)})
    return sorted(rows, key=lambda r: r["path"])


def release_profile(text):
    """Read the narrow scalar [profile.release] contract on Python 3.10+."""
    profile = {}
    active = False
    for raw in text.lstrip("\ufeff").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line.startswith("["):
            active = line == "[profile.release]"
            continue
        if active and "=" in line:
            key, value = (part.strip() for part in line.split("=", 1))
            if key in {"opt-level", "lto", "panic", "codegen-units"}:
                if value in {"true", "false"}:
                    profile[key] = value == "true"
                elif value.isdigit():
                    profile[key] = int(value)
                else:
                    profile[key] = value.strip('"\'')
    return profile


def snapshot(source, manifest, expected):
    require(sha(manifest) == expected, "Pinned source manifest required")
    rows = load(manifest)
    require(isinstance(rows, list) and len(rows) == len({r["path"] for r in rows}), "Unique source inventory required")
    for row in rows:
        bound(row, pathlib.Path(source))
    normalized = [{"path": r["path"], "sha256": r["sha256"]} for r in rows]
    require(sorted(normalized, key=lambda r: r["path"]) == source_inventory(source), "Exact source inventory required")
    return sorted(normalized, key=lambda r: r["path"])


def percentile95(samples):
    require(len(samples) >= 20 and all(type(v) in (float, int) and math.isfinite(v) and v > 0 for v in samples),
            "At least 20 positive finite samples required")
    return sorted(samples)[math.ceil(0.95 * len(samples)) - 1]


def evaluate(receipt, measurements):
    require(receipt["state"] == "COMPILED_NOT_BENCHMARKED" and receipt.get("sourceStable") is True, "Paired stable compilation required")
    require(all(type(receipt["profiles"][n]["compressedBytes"]) is int and receipt["profiles"][n]["compressedBytes"] > 0 for n in ("baseline","size")), "Positive native sizes required")
    require(measurements["sourceManifestSha256"] == receipt["sourceManifestSha256"] and
            measurements["abi"] == receipt["abi"] and measurements["generation"] == receipt["generation"], "Measurement source/ABI/generation mismatch")
    measured = {}
    for name in ("baseline", "size"):
        row = measurements[name]
        require(row["artifactSha256"] == receipt["profiles"][name]["artifactSha256"], "Measurement native identity mismatch")
        require(set(row["protocolCases"]) == CASES and all(v is True for v in row["protocolCases"].values()) and
                row["panicCount"] == 0, "Protocol/panic regression")
        measured[name] = {"latency": percentile95(row["latencyMs"]), "start": percentile95(row["coldStartMs"])}
        throughput = row["throughputMbps"]
        require(len(throughput) >= 3 and all(type(v) in (float,int) and math.isfinite(v) and v > 0 for v in throughput), "Invalid throughput samples")
        measured[name]["throughput"] = sum(throughput) / len(throughput)
    a, b = measured["baseline"], measured["size"]
    ratios = {"compressedSizeRatio": receipt["profiles"]["size"]["compressedBytes"] / receipt["profiles"]["baseline"]["compressedBytes"],
              "throughputRatio": b["throughput"] / a["throughput"], "p95Ratio": b["latency"] / a["latency"], "coldStartRatio": b["start"] / a["start"]}
    accepted = (ratios["compressedSizeRatio"] <= .90 and ratios["throughputRatio"] >= .97 and
                ratios["p95Ratio"] <= 1.05 and ratios["coldStartRatio"] <= 1.10)
    return {"state": "ACCEPTED_EXPERIMENT" if accepted else "REJECTED_EXPERIMENT", "ratios": ratios, "thresholds": THRESHOLDS}


def run(args):
    source = pathlib.Path(args.source).absolute()
    rows = snapshot(source, args.manifest, args.manifest_sha256)
    output = pathlib.Path(args.output).absolute()
    require(not output.exists(), "Fresh experiment directory required")
    require(output != source and source not in output.parents and output not in source.parents, "Experiment must be outside frozen source")
    crate = pathlib.PurePosixPath(args.crate)
    require(not crate.is_absolute() and crate.parts and all(p not in ("..", ".") for p in crate.parts) and
            not any(c in args.crate for c in ("\\", ":")), "Relative crate path required")
    lock = regular(source / crate / "Cargo.lock")
    toml = regular(source / crate / "Cargo.toml")
    release = release_profile(toml.read_text(encoding="utf-8-sig"))
    require(release.get("opt-level") == 3 and release.get("lto") in (True, "fat") and
            release.get("codegen-units") == 1 and release.get("panic") == "unwind", "Pinned baseline release profile changed")
    output.mkdir(parents=True)
    receipt = {"state": "PREPARED_NOT_COMPILED", "generation": args.generation, "abi": args.abi,
               "sourceManifestSha256": args.manifest_sha256, "lockSha256": sha(lock), "crateManifestSha256": sha(toml),
               "producerSha256": sha(__file__), "baselineProfile": release, "thresholds": THRESHOLDS, "profiles": {}, "sourceStable": False}
    try:
        for name in ("baseline", "size"):
            copy = output / name / "source"
            for row in rows:
                dest = copy / row["path"]
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(bound(row, source), dest)
            require(source_inventory(copy) == rows, "Copied source changed")
            receipt["profiles"][name] = {"overrides": {} if name == "baseline" else SIZE_PROFILE,
                                          "sourceManifestSha256": args.manifest_sha256, "lockSha256": sha(copy / crate / "Cargo.lock")}
        if not args.execute:
            return receipt
        cargo = regular(pathlib.Path(args.toolchain) / "bin/cargo.exe")
        rustc = regular(pathlib.Path(args.toolchain) / "bin/rustc.exe")
        compiler = pathlib.Path(args.ndk) / "toolchains/llvm/prebuilt/windows-x86_64/bin"
        regular(compiler / "llvm-dlltool.exe")
        require(pathlib.Path(args.cargo_home).is_dir(), "Existing Cargo cache required")
        tools = output / "host-tools"
        tools.mkdir()
        shutil.copyfile(compiler / "llvm-dlltool.exe", tools / "dlltool.exe")
        receipt["toolchain"] = {"rustcSha256": sha(rustc), "cargoSha256": sha(cargo), "ndkClangSha256": sha(compiler / "clang.exe")}
        for name in ("baseline", "size"):
            target = output / name / "target"
            env = {k:v for k,v in os.environ.items() if not k.startswith(("CARGO_PROFILE_", "CARGO_TARGET_")) and k not in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER")}
            env.update({"RUSTC": str(rustc), "CARGO_HOME": str(args.cargo_home), "CARGO_TARGET_DIR": str(target),
                        "ANDROID_NDK_HOME": str(args.ndk), "ANDROID_NDK_ROOT": str(args.ndk), "LIBCLANG_PATH": str(compiler),
                        "CMAKE_GENERATOR": "Ninja", "RUSTFLAGS": "-C link-arg=-Wl,-z,max-page-size=16384"})
            toolchain = pathlib.Path(args.toolchain)
            env["PATH"] = os.pathsep.join(map(str, [tools, toolchain / "bin", toolchain / "lib/rustlib/x86_64-pc-windows-gnu/bin", toolchain / "lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained", pathlib.Path(args.cargo_ndk), compiler, pathlib.Path(args.cmake)])) + os.pathsep + env.get("PATH", "")
            if name == "size":
                env.update({"CARGO_PROFILE_RELEASE_" + k:v for k,v in SIZE_PROFILE.items()})
            command = [str(cargo), "ndk", "-t", args.abi, "-P", "26", "build", "--release", "--frozen", "--bin", "client"]
            start = time.monotonic()
            log = output / name / "build.log"
            with log.open("wb") as stream:
                result = subprocess.run(command, cwd=output / name / "source" / crate, env=env, stdout=stream, stderr=subprocess.STDOUT)
            row = receipt["profiles"][name]
            row.update(command=command, exitCode=result.returncode, buildSeconds=time.monotonic()-start, logSha256=sha(log))
            require(result.returncode == 0, f"{name} native build failed; log preserved")
            binary = target / TARGETS[args.abi] / "release/client"
            from bs_engine_inputs import elf
            elf(binary, args.abi)
            row.update(artifact=str(binary), artifactSha256=sha(binary), bytes=binary.stat().st_size,
                       compressedBytes=len(zlib.compress(binary.read_bytes(), 9)))
            require(source_inventory(output / name / "source") == rows, "Build mutated source copy/lock")
        receipt["state"] = "COMPILED_NOT_BENCHMARKED"
        return receipt
    except Exception as error:
        receipt.update(state="FAILED_PRESERVED", error=str(error))
        raise
    finally:
        receipt["sourceStable"] = snapshot(source, args.manifest, args.manifest_sha256) == rows
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--crate", default="rust-client")
    parser.add_argument("--generation", choices=("2.0","2.1.5","2.1.9","2.5.1","2.5.2","2.5.3"), required=True)
    parser.add_argument("--abi", choices=ABIS, required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--toolchain", default="D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain")
    parser.add_argument("--cargo-home", default="D:/MCRF/Projects/MCRF/work/toolchains/cargo-home")
    parser.add_argument("--cargo-ndk", default="D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin")
    parser.add_argument("--ndk", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018")
    parser.add_argument("--cmake", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin")
    print(json.dumps(run(parser.parse_args()), indent=2))
