"""Run the authenticated Go/Rust dispatcher pair on an owned ARM64 fixture."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import uuid

from bs_engine_inputs import bound, load, require, sha
import csqtt_multicall_build as producer
from native_routing_cli_evidence import output, scope
from native_routing_physical_build import ABIS, configure_abi, elf, verify_stage


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TOOLCHAINS = ROOT / "work/toolchains"
GO_INPUTS = tuple(HERE / "native-packet" / name for name in (
    "mcrf_authenticated_udp.go", "mcrf_authenticated_udp_test.go"))
GENERATIONS = ("g20", "g215", "g219", "g251", "g253")
GOLDEN = "mcrf_private_udp::tests::interop_forgery_direction_and_replay"
PAIR = "dispatcher::mcrf_private_udp_paired_dispatcher"
ANDROID_ABI = "arm64-v8a"


def row(path):
    return {"path": str(path), "sha256": sha(path)}


def test_manifest(production):
    text = (production / "Cargo.toml").read_text(encoding="utf-8")
    data = tomllib.loads(text)["workspace"]
    selected = [f"adapters/{key}/rust-client" for key in GENERATIONS]
    # The adapters are path dependencies and become implicit workspace members.
    # Explicit parent members make Cargo ignore excludes for their vendored dependencies.
    for field, values in (("members", []), ("exclude", [p for p in data["exclude"] if p not in selected])):
        original = f"{field} = " + json.dumps(data[field])
        require(text.count(original) == 1, "Unexpected production workspace anchor")
        text = text.replace(original, f"{field} = " + json.dumps(values))
    return text


def verify_lock(path, allowed):
    pins = {(p["name"], p["version"], p.get("checksum")) for p in allowed}
    rows = tomllib.loads(path.read_text(encoding="utf-8"))["package"]
    require(all((p["name"], p["version"], p.get("checksum")) in pins
                for p in rows if "source" in p), "Test workspace resolved unadmitted registry source")


def verify_receipt(path, stage):
    stage = Path(stage).resolve(strict=True)
    value = load(path)
    require(value.get("state") == "AUTHENTICATED_PRIVATE_UDP_PASS"
            and value.get("providerTraffic") is False and value.get("remoteCleanupConfirmed") is True
            and bound(value["compiledReceipt"]) == stage / "receipt.json"
            and value["producerSha256"] == sha(__file__), "Paired packet proof identity differs")
    fixture = value["fixture"]
    require(fixture.get("kind") == "physical" and fixture.get("abi") == "arm64-v8a"
            and fixture.get("shellUid") == 2000 and type(fixture.get("sdk")) is int and fixture["sdk"] >= 29,
            "Expect unprivileged physical ARM64 packet proof")
    require(value["goInputs"] == [row(p) for p in GO_INPUTS], "Paired Go source differs")
    workspace = bound(value["testWorkspace"])
    require(workspace.read_text(encoding="utf-8") == test_manifest(stage), "Unexpected paired workspace changes")
    production = load(stage / "receipt.json")
    verify_lock(bound(value["testLock"]), production["allowedRegistry"])
    elf(bound(value["goFixture"]), fixture["abi"])
    logs = value["generations"]
    require(set(logs) == set(GENERATIONS), "All five native generation fixtures required")
    for key, generation in logs.items():
        elf(bound(generation["binary"]), fixture["abi"])
        events = [json.loads(line) for line in bound(generation["compile"]).read_text(encoding="utf-8").splitlines()]
        require(any(entry.get("reason") == "compiler-artifact" and entry.get("target", {}).get("name") ==
                    f"mcrf_adapter_{key}" and entry.get("profile", {}).get("test") is True
                    and entry["profile"]["opt_level"] == "0" and entry["profile"]["debuginfo"] == 0
                    and entry.get("executable") for entry in events)
                and any(entry.get("reason") == "build-finished" and entry.get("success") is True for entry in events),
                "Rust generation compile evidence differs")
        for case, signal in (("golden", GOLDEN), ("paired", PAIR)):
            log = bound(generation[case]).read_text(encoding="utf-8")
            result = r"^test " + re.escape(signal) + r" \.\.\. (?:[^\n]*\r?\n)*?ok$"
            require(re.search(result, log, re.M) and
                    re.search(r"^test result: ok\. 1 passed; 0 failed; 0 ignored;", log, re.M),
                    "Missing completed generation case")
            if case == "paired":
                require(re.search(r"^--- PASS: TestPrivatePacketUDPGoPeer \(", log, re.M),
                        "The paired Go peer did not complete")
    return value


def compile_tests(workspace, stage_out, args, compiled):
    cargo = Path(args.toolchain) / "bin/cargo.exe"
    clang = Path(args.ndk) / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    require(sha(cargo) == compiled["toolchain"]["cargoSha256"]
            and sha(clang) == compiled["toolchain"]["ndkClangSha256"], "Production compiler differs")
    args.target_dir = compiled["targetDirectory"]
    environment = configure_abi(producer.environment(args, stage_out), clang, ANDROID_ABI)
    environment["CARGO_INCREMENTAL"] = "0"
    target = ABIS[ANDROID_ABI][0]
    lock = workspace / "Cargo.lock"
    with (stage_out / "test-lock-resolve.log").open("wb") as log:
        subprocess.run([str(cargo), "metadata", "--offline", "--format-version", "1",
                        "--filter-platform", target, "--features", "ported,native-routing"],
                       cwd=workspace, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=180, check=True)
    verify_lock(lock, compiled["allowedRegistry"])
    lock_pin = row(lock)
    result = {}
    for key in GENERATIONS:
        compile_log = stage_out / f"{key}-cargo.jsonl"
        # Use direct Cargo so the receipt contains its unfiltered artifact records.
        command = [str(cargo), "test", "--target", target, "--frozen",
                   "-j", "2", "-p", "mcrf-csqtt-multicall-boundary", "--features", "ported,native-routing",
                   "-p", f"mcrf-adapter-{key}", "--lib", "--no-run",
                   "--message-format=json"]
        with compile_log.open("wb") as out, (stage_out / f"{key}-cargo.stderr").open("wb") as errors:
            subprocess.run(command, cwd=workspace, env=environment, stdout=out, stderr=errors, check=True, timeout=900)
        events = [json.loads(line) for line in compile_log.read_text(encoding="utf-8").splitlines()]
        binaries = {e["executable"] for e in events if e.get("reason") == "compiler-artifact" and
                    e.get("target", {}).get("name") == f"mcrf_adapter_{key}" and
                    e.get("profile", {}).get("test") is True and e.get("executable")}
        require(len(binaries) == 1, "Expect one selected Rust generation test binary")
        binary = Path(binaries.pop()).resolve(strict=True)
        require(Path(environment["CARGO_TARGET_DIR"]) in binary.parents, "Rust binary outside controlled target")
        preserved = stage_out / f"{key}.test"
        shutil.copyfile(binary, preserved)
        elf(preserved, "arm64-v8a")
        result[key] = {"binary": row(preserved), "compile": row(compile_log)}
    require(row(lock) == lock_pin, "Paired test dependency lock changed while compiling")
    return result, lock_pin


def run(stage, stage_out, args):
    fixture = scope(args.adb, args.serial, physical=True)
    compiled, source_rows = verify_stage(stage)
    require(compiled.get("privatePacketBridgeContract") == 1, "Authenticated compiled source required")
    stage_out.mkdir(parents=True)
    original_receipt = row(stage / "receipt.json")
    go_inputs = [row(p) for p in GO_INPUTS]
    go_exe = TOOLCHAINS / "go1.26.8/go/bin/go.exe"
    go_temp = stage_out / "go-tmp"
    go_temp.mkdir()
    env = os.environ | {"GOOS": "linux", "GOARCH": "arm64", "CGO_ENABLED": "0", "GOTOOLCHAIN": "local",
                        "GOFLAGS": "-p=4", "GOENV": "off", "GOWORK": "off",
                        "GOCACHE": str(TOOLCHAINS / "go-build-cache"), "GOTMPDIR": str(go_temp)}
    go_binary = stage_out / "go-peer.test"
    with (stage_out / "go-compile.log").open("wb") as log:
        subprocess.run([str(go_exe), "test", "-c", "-trimpath", "-o", str(go_binary),
                        *(str(p) for p in GO_INPUTS)], env=env, stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=300)
    elf(go_binary, "arm64-v8a")
    # Cargo cannot test excluded dependencies that declare dev-dependencies.
    # Preserve exact source bytes and make only the test workspace include them.
    workspace = stage_out / "source"
    workspace.mkdir()
    frozen = [entry for entry in source_rows if entry["path"] != "Cargo.toml" and
              not entry["path"].startswith("admission-tests/")]
    for entry in frozen:
        destination = workspace / entry["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(stage / entry["path"], destination)
    manifest = workspace / "Cargo.toml"
    manifest.write_text(test_manifest(stage), encoding="utf-8", newline="\n")
    shutil.copyfile(stage / "Cargo.lock", workspace / "Cargo.lock")
    workspace_pin = row(manifest)
    rows, lock_pin = compile_tests(workspace, stage_out, args, compiled)
    nonce = uuid.uuid4().hex
    adb = [args.adb, "-s", args.serial]

    def command(*values, timeout=30):
        return subprocess.run([*adb, *values], capture_output=True, check=True, timeout=timeout).stdout

    def identity(remote, local):
        require(output(args.adb, args.serial, "shell", "sha256sum", remote).split()[0] == sha(local),
                "Fixture ELF changed on device")

    def remote_copy(local, label):
        remote = f"/data/local/tmp/mcrf-private-udp-{nonce}-{label}"
        command("shell", "test", "!", "-e", remote)
        candidates.append(remote)
        command("push", str(local), remote, timeout=90)
        command("shell", "chmod", "700", remote)
        identity(remote, local)
        return remote

    candidates = []
    try:
        peer = remote_copy(go_binary, "go")
        for key, generation in rows.items():
            candidate = bound(generation["binary"])
            remote = remote_copy(candidate, key)
            for case, test, extra in (("golden", GOLDEN, []), ("paired", PAIR, ["--ignored", "--nocapture"])):
                log = stage_out / f"{key}-{case}.log"
                result = subprocess.run(
                    [*adb, "shell", "timeout", "-s", "KILL", "18", "env", "-i",
                     "PATH=/system/bin:/system/xbin", "TMPDIR=/data/local/tmp", f"MCRF_CSQTT_PRIVATE_UDP_GO_FIXTURE={peer}",
                     remote, "--exact", test, *extra], capture_output=True, timeout=24,
                )
                log.write_bytes(result.stdout + result.stderr)
                require(result.returncode == 0, f"Owned {key} {case} execution failed: {log}")
                generation[case] = row(log)
            identity(remote, candidate)
            command("shell", "rm", "-f", remote)
            command("shell", "test", "!", "-e", remote)
            candidates.remove(remote)
        identity(peer, go_binary)
    finally:
        for remote in candidates:
            command("shell", "rm", "-f", remote)
            command("shell", "test", "!", "-e", remote)
    require(scope(args.adb, args.serial, physical=True) == fixture and original_receipt == row(stage / "receipt.json")
            and go_inputs == [row(p) for p in GO_INPUTS], "Physical fixture or production source changed")
    require(workspace_pin == row(manifest) and all(sha(workspace / entry["path"]) == entry["sha256"] for entry in frozen),
            "Paired fixture source changed")
    verify_stage(stage)
    evidence = {"state": "AUTHENTICATED_PRIVATE_UDP_PASS", "providerTraffic": False,
                "remoteCleanupConfirmed": True, "compiledReceipt": original_receipt, "fixture": fixture,
                "producerSha256": sha(__file__), "goInputs": go_inputs, "goFixture": row(go_binary),
                "testWorkspace": workspace_pin, "testLock": lock_pin,
                "generations": rows}
    path = stage_out / "receipt.json"
    path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    verify_receipt(path, stage)
    print(json.dumps(row(path)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--toolchain", default="D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain")
    parser.add_argument("--cargo-home", default=str(TOOLCHAINS / "cargo-home"))
    parser.add_argument("--cargo-ndk", default="D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin")
    parser.add_argument("--mingw", default="D:/MCRF/Builds/Tools/llvm-mingw-20261020-ucrt-x86_64")
    parser.add_argument("--ndk", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018")
    parser.add_argument("--cmake", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin")
    parser.add_argument("--nasm", default="D:/MCRF/Builds/Tools/nasm-3.01")
    values = parser.parse_args()
    run(values.stage.resolve(strict=True), values.output.resolve(), values)
