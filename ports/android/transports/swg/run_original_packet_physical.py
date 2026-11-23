"""Compile and execute the exact Go carrier bridge tests on owned Android ARM64."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid

from bs_engine_inputs import load, require, sha, verify_multimode_preparation
from native_routing_cli_evidence import output, scope
from native_routing_physical_build import elf
from original_packet_evidence import CASES, verify


PROJECT = Path(__file__).resolve().parents[4]
TOOLCHAINS = PROJECT / "work/toolchains"
PACKAGES = {"main": ".", "fd": "./internal/csqtt"}


def row(path):
    return {"path": str(path), "sha256": sha(path)}


def run(args):
    prepared = args.prepared.resolve(strict=True)
    require(sha(prepared) == args.sha256, "Exact Go wrapper source proof required")
    capture = load(prepared)
    require(capture.get("privatePacketBridgeContract") == 1, "Authenticated wrapper fixture required")
    verify_multimode_preparation(capture)
    fixture = scope(args.adb, args.serial, physical=True)
    stage = args.output.resolve()
    builds = Path("D:/MCRF/Builds/Android").resolve()
    require(builds in stage.parents and not stage.exists(), "Fresh external Android stage required")
    stage.mkdir()
    tmp = stage / "tmp"
    tmp.mkdir()
    home = TOOLCHAINS / "go-home"
    go = TOOLCHAINS / "go1.26.8/go/bin/go.exe"
    environment = os.environ | {
        "GOOS": "linux", "GOARCH": "arm64", "CGO_ENABLED": "0", "GOTOOLCHAIN": "local",
        "GOENV": "off", "GOWORK": "off", "GOFLAGS": "-p=4", "GOPROXY": "off",
        "GOPATH": str(home), "GOMODCACHE": str(home / "pkg/mod"),
        "GOCACHE": str(TOOLCHAINS / "go-build-cache"), "GOTMPDIR": str(tmp),
    }
    environment.pop("GOROOT", None)
    captured = []
    adb = [args.adb, "-s", args.serial]

    def command(*values, timeout=20):
        subprocess.run([*adb, *values], capture_output=True, check=True, timeout=timeout)

    for suite, package in PACKAGES.items():
        binary = stage / f"original-packet-{suite}.test"
        with (stage / f"{suite}-compile.log").open("wb") as log:
            subprocess.run([str(go), "test", "-c", "-mod=readonly", "-trimpath", "-ldflags=-checklinkname=0", "-o", str(binary), package],
                           cwd=capture["root"], env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
        elf(binary, "arm64-v8a")
        remote = f"/data/local/tmp/mcrf-original-packet-{uuid.uuid4().hex}-{suite}"
        command("shell", "test", "!", "-e", remote)
        binary_hash = sha(binary)

        def check_device_hash():
            require(output(args.adb, args.serial, "shell", "sha256sum", remote).split()[0] == binary_hash,
                    "Executed Android fixture binary differs")

        try:
            command("push", str(binary), remote, timeout=90)
            command("shell", "chmod", "700", remote)
            check_device_hash()
            log = stage / f"{suite}.log"
            selector = "'-test.run=^(" + "|".join(CASES[suite]) + ")$'"
            result = subprocess.run([*adb, "shell", "timeout", "-s", "KILL", "25", remote, selector,
                                     "-test.v", "-test.count=1", "-test.timeout=20s"], capture_output=True, timeout=30)
            log.write_bytes(result.stdout + result.stderr)
            require(result.returncode == 0, f"Original packet {suite} Android test failed: {log}")
            check_device_hash()
        finally:
            command("shell", "rm", "-f", remote)
            command("shell", "test", "!", "-e", remote)
        captured.append({"abi": fixture["abi"], "suite": suite, "binary": row(binary), "log": row(log)})

    require(scope(args.adb, args.serial, physical=True) == fixture and sha(prepared) == args.sha256,
            "Physical Android or exact prepared proof changed")
    verify_multimode_preparation(capture)
    receipt = stage / "receipt.json"
    evidence = {"state": "ANDROID_ORIGINAL_PACKET_TESTS_PASSED", "scope": "owned-physical-arm64",
                "prepared": row(prepared), "preparedSourceSha256": args.sha256, "serial": args.serial,
                "shellUid": fixture["shellUid"], "deviceSdk": fixture["sdk"], "deviceAbi": fixture["abi"],
                "providerTraffic": False, "remoteCleanupConfirmed": True, "executions": captured}
    receipt.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    verify(receipt, args.sha256)
    print(json.dumps(row(receipt)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepared", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe")
    run(parser.parse_args())
