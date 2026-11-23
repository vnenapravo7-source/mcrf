"""Record one owned physical Android kernel or Main-service instrumentation run.

All APKs must already be installed for user 0 by the device coordinator.
This command never installs packages or changes VPN consent.
"""
import argparse
import ipaddress
import json
import pathlib
import re
import subprocess

from bs_engine_inputs import require, sha
from native_routing_cli_evidence import scope
from native_routing_evidence import BUILD, GENERATIONS
from native_routing_physical_evidence import (
    HELPER_PACKAGE, MAIN_PACKAGE, MODULE_PACKAGE, PEER_PACKAGE, RUNNERS, TEST_PACKAGE, apk_digest, check_scope, compiled,
    instrument_result, ordered_pair_pin, router_artifact, runtime,
)
from prepare_csqtt_direct_tun import file_row


def observe(args):
    adb = pathlib.Path(args.adb).resolve(strict=True)
    serial = args.serial
    transport = args.transport or serial
    if args.transport:
        endpoint = re.fullmatch(r"(\d+\.\d+\.\d+\.\d+):(\d{1,5})", transport)
        require(endpoint and any(ipaddress.IPv4Address(endpoint[1]) in ipaddress.IPv4Network(prefix)
                for prefix in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) and
                1 <= int(endpoint[2]) <= 65535, "Explicit local wireless ADB endpoint required")

    def fixture_scope():
        if not args.transport:
            return scope(adb, serial, physical=True)
        def value(*command):
            return subprocess.check_output([str(adb), "-s", transport, *command], timeout=15).decode().strip()
        require(value("shell", "getprop", "ro.serialno") == serial and value("get-state") == "device" and
                value("shell", "id", "-u") == "2000", "Wireless ADB must identify the same unprivileged physical fixture")
        sdk = value("shell", "getprop", "ro.build.version.sdk")
        require(sdk.isascii() and sdk.isdecimal() and int(sdk) >= 29 and
                value("shell", "getprop", "ro.kernel.qemu") in ("", "0") and
                value("shell", "getprop", "ro.product.cpu.abi") == "arm64-v8a", "Physical ARM64 fixture required")
        return {"kind": "physical", "abi": "arm64-v8a", "sdk": int(sdk), "shellUid": 2000}

    fixture = fixture_scope()
    check_scope(serial, fixture)
    adb_sha = sha(adb)
    out = pathlib.Path(args.output).resolve()
    require(BUILD in out.parents, "Keep Android receipts in controlled build storage")
    compilation = pathlib.Path(args.compilation).resolve(strict=True)
    generation, purpose = args.generation, args.purpose
    if purpose == "direct-owner":
        from build_physical_direct_owner_capture import verify
        native = verify(compilation, args.stage)[generation]
    elif purpose == "router-entry":
        require(generation == "2.5.3", "Bundled production router dispatches only the g253 adapter")
        native = router_artifact(compilation, args.stage)
    else:
        native = compiled(compilation, args.stage)[generation, "arm64-v8a", "bootstrap"]
    packages = {MAIN_PACKAGE: args.main_apk, TEST_PACKAGE: args.test_apk}
    pair = None
    if purpose in ("direct-owner", "router-entry"):
        require(args.helper_apk and args.peer_apk, "Pinned helper and peer APKs required for native owner capture")
        pair = ordered_pair_pin(sha(args.helper_apk), sha(args.peer_apk))
        packages.update({HELPER_PACKAGE: args.helper_apk, PEER_PACKAGE: args.peer_apk})
    else:
        require(args.helper_apk is None and args.peer_apk is None, "Owner pair is scoped to Direct owner capture")
    if purpose == "service":
        require(args.module_apk, "Owned test-only BS module APK required")
        packages[MODULE_PACKAGE] = args.module_apk
        require(apk_digest(args.module_apk, "lib/arm64-v8a/libcsqtt_transport.so") == sha(native),
                "BS module contains a different native executable")
        pin, arg = sha(args.module_apk), "moduleSha256"
    elif purpose == "router-entry":
        require(args.module_apk is None, "Root router executable must come from Main")
        require(apk_digest(args.main_apk, "lib/arm64-v8a/libmcrf_router.so") == sha(native),
                "Main APK contains a different production root router executable")
        pin, arg = sha(native), "fixtureSha256"
    else:
        require(args.module_apk is None, "Kernel proof uses the signed test harness executable")
        require(apk_digest(args.test_apk, "lib/arm64-v8a/libmcrf_owned_native_udp_fixture.so") == sha(native),
                "Test APK contains a different native executable")
        pin, arg = sha(native), "fixtureSha256"
    host_rows = {package: file_row(pathlib.Path(path).resolve(strict=True)) for package, path in packages.items()}
    compile_row = file_row(compilation)
    out.mkdir()

    def command(*arguments, timeout=15, check=True):
        return subprocess.run([str(adb), "-s", transport, "shell", *arguments], stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout, check=check)

    def installed(package):
        lines = command("pm", "path", "--user", "0", package).stdout.decode("ascii").splitlines()
        require(len(lines) == 1 and lines[0].startswith("package:"), "Exact installed base APK required: " + package)
        remote = lines[0].removeprefix("package:")
        require(re.fullmatch(r"/data/app/[A-Za-z0-9.~_+=/-]+/base\.apk", remote) and ".." not in remote.split("/"),
                "Unexpected installed APK path")
        words = command("sha256sum", remote, timeout=30).stdout.decode("ascii").split()
        require(len(words) == 2 and words[1] == remote and words[0] == host_rows[package]["sha256"],
                "Installed user-0 APK differs: " + package)
        return words[0]

    before = {package: installed(package) for package in packages}
    extra_args = ["-e", "pairSha256", pair] if pair else []
    invocation = ["am", "instrument", "--user", "0", "-w", "-r", "-e", "generation", generation,
                  "-e", arg, pin, *extra_args, RUNNERS[purpose]]
    log = out / "instrumentation.log"
    try:
        result = command(*invocation, timeout=150, check=False)
    except subprocess.TimeoutExpired as error:
        log.write_bytes(error.stdout or b"")
        raise
    log.write_bytes(result.stdout)
    after = {package: installed(package) for package in packages}
    require(result.returncode == 0 and before == after and file_row(compilation) == compile_row and
            all(file_row(row["path"]) == row for row in host_rows.values()) and sha(adb) == adb_sha and
            fixture_scope() == fixture, "Instrumentation failed or fixture identity changed")
    instrument_result(log, generation, purpose, pin, pair)
    proof = out / "physical-runtime.json"
    proof.write_text(json.dumps({
        "state": "OWNED_PHYSICAL_ANDROID_NATIVE_KERNEL_OR_SERVICE_PASSED", "serial": serial, "fixture": fixture,
        "user": 0, "purpose": purpose, "generation": generation, "command": invocation, "adbTransport": transport,
        "packages": {package: {"artifact": host_rows[package], "installedBefore": before[package],
                               "installedAfter": after[package]} for package in packages},
        "compilation": compile_row, "log": file_row(log), "producerSha256": sha(__file__), "adbSha256": adb_sha,
        "providerTraffic": False, "productionActivated": False,
    }, indent=2) + "\n", encoding="utf-8")
    try:
        return runtime(proof, args.stage)
    except Exception:
        proof.unlink()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("adb", "serial", "stage", "compilation", "output", "main-apk", "test-apk"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--generation", choices=GENERATIONS, required=True)
    parser.add_argument("--purpose", choices=RUNNERS, required=True)
    parser.add_argument("--module-apk")
    parser.add_argument("--helper-apk")
    parser.add_argument("--peer-apk")
    parser.add_argument("--transport")
    print(json.dumps(observe(parser.parse_args())))


if __name__ == "__main__":
    main()
