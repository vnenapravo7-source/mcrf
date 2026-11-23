"""Configuration-only CLI verification on an explicit Android fixture."""
import json
import pathlib
import re
import subprocess
import uuid
import ipaddress

from bs_engine_inputs import require, sha


def output(adb, serial, *args):
    return subprocess.check_output([str(adb), "-s", serial, *args], timeout=15).decode().strip()


def physical_serial(serial):
    return isinstance(serial, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{5,63}", serial) is not None and not serial.startswith("emulator-")

def private_adb_endpoint(value):
    if not isinstance(value,str) or re.fullmatch(r"[0-9.]+:[1-9][0-9]{0,4}",value) is None:
        return False
    host,port=value.rsplit(":",1)
    try:
        address=ipaddress.IPv4Address(host)
    except ValueError:
        return False
    return 1<=int(port)<=65535 and any(address in ipaddress.IPv4Network(network)
        for network in ("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16"))


def scope(adb, serial, physical=False):
    def prop(name):
        return output(adb, serial, "shell", "getprop", name)

    if not physical:
        require(re.fullmatch(r"emulator-[0-9]{4,6}", serial) is not None, "Owned emulator serial required")
        require(prop("ro.kernel.qemu") == "1" and prop("ro.product.cpu.abi") == "x86_64",
                "Owned x86_64 emulator required")
        return {"kind": "emulator", "abi": "x86_64"}
    network=private_adb_endpoint(serial)
    require(physical_serial(serial) or network, "Explicit owned physical Android serial required")
    require(output(adb, serial, "get-state") == "device", "Authorized physical Android device required")
    require(output(adb, serial, "shell", "id", "-u") == "2000", "Unprivileged Android shell required")
    sdk = prop("ro.build.version.sdk")
    require(sdk.isascii() and sdk.isdecimal() and int(sdk) >= 29, "Android API 29 or newer required")
    require(prop("ro.kernel.qemu") in ("", "0") and prop("ro.product.cpu.abi") == "arm64-v8a",
            "Owned physical ARM64 Android fixture required")
    fixture={"kind": "physical", "abi": "arm64-v8a", "sdk": int(sdk), "shellUid": 2000}
    if network:
        device_serial=prop("ro.serialno")
        require(physical_serial(device_serial),"Authorized network ADB must identify the physical device serial")
        fixture["deviceSerial"]=device_serial
    return fixture


def run(binary, output_dir, adb, serial, proof, physical=False):
    fixture = scope(adb, serial, physical)
    adb_digest = sha(adb)
    binary = pathlib.Path(binary)
    digest = sha(binary)
    remote = "/data/local/tmp/mcrf-native-cli-" + uuid.uuid4().hex
    adb_command = [str(adb), "-s", serial]

    def command(*args, timeout=15):
        return subprocess.run([*adb_command, *args], capture_output=True, check=True, timeout=timeout)

    def identity():
        value = output(adb, serial, "shell", "sha256sum", remote).split()[0]
        require(value == digest, "Executed CLI binary identity changed")
        return value

    def invoke(mode, arguments, token):
        values = ["PATH=/system/bin:/system/xbin", "TMPDIR=/data/local/tmp"]
        if mode is not None:
            values.append("MCRF_BS_RUST_MODE=" + mode)
        if token is not None:
            values.append("MCRF_DIRECT_TUN_GENERATION=" + token)
        return subprocess.run([*adb_command, "shell", "timeout", "-s", "KILL", "15",
                               "env", "-i", *values, remote, *arguments], capture_output=True, timeout=20)

    command("shell", "test", "!", "-e", remote)
    try:
        command("push", str(binary), remote, timeout=120)
        command("shell", "chmod", "700", remote)
        before = identity()
        result = proof(binary, output_dir, native_routing=True, runner=invoke)
        after = identity()
        require(scope(adb, serial, physical) == fixture and sha(adb) == adb_digest,
                "Android CLI fixture identity changed")
    finally:
        try:
            command("shell", "rm", "-f", remote)
            command("shell", "test", "!", "-e", remote)
        except (OSError, subprocess.SubprocessError):
            (output_dir / "native-routing-cli-cleanup-pending.json").write_text(
                json.dumps({"serial": serial, "path": remote, "cleanupComplete": False}), encoding="utf-8")
            raise
    return {"path": str(output_dir / "host-proof.json"), "sha256": result, "platform": "android",
            "serial": serial, "fixture": fixture, "executedBinaryPath": remote, "binaryBefore": before,
            "binaryAfter": after, "cleanupComplete": True, "providerTraffic": False,
            "producerSha256": sha(__file__), "adbSha256": adb_digest}


def verify_fixture(evidence):
    fixture = evidence.get("fixture")
    remote = evidence.get("executedBinaryPath")
    adb_digest = evidence.get("adbSha256")
    require(type(fixture) is dict and evidence.get("cleanupComplete") is True and
            evidence.get("producerSha256") == sha(__file__) and evidence.get("providerTraffic") is False and
            isinstance(adb_digest, str) and re.fullmatch(r"[a-f0-9]{64}", adb_digest) and
            isinstance(remote, str) and re.fullmatch(r"/data/local/tmp/mcrf-native-cli-[a-f0-9]{32}", remote),
            "Owned Android CLI source/cleanup evidence required")
    serial = evidence.get("serial", "")
    if fixture.get("kind") == "emulator":
        require(fixture == {"kind": "emulator", "abi": "x86_64"} and
                isinstance(serial, str) and re.fullmatch(r"emulator-[0-9]{4,6}", serial), "Owned x86_64 emulator proof required")
        return "x86_64"
    network=private_adb_endpoint(serial)
    expected={"kind","abi","sdk","shellUid"}|({"deviceSerial"} if network else set())
    require(set(fixture) == expected and fixture["kind"] == "physical" and
            fixture["abi"] == "arm64-v8a" and fixture["shellUid"] == 2000 and
            type(fixture["sdk"]) is int and fixture["sdk"] >= 29 and
            (physical_serial(serial) or network and physical_serial(fixture.get("deviceSerial"))),
            "Owned physical ARM64 Android CLI proof required")
    return "arm64"
