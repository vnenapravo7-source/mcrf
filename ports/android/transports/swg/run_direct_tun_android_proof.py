"""Execute the frozen Unix FD regression binary on the authorized Android phone."""
import argparse
import json
import pathlib
import subprocess

from bs_engine_inputs import require, sha
from native_routing_cli_evidence import scope
from native_routing_evidence import BUILD
from prepare_csqtt_direct_tun import checked_row, file_row, verify_test_compile, verify_unix_proof

PHONE = "R5CX73QYN3D"
TARGET = "aarch64-linux-android"
TEST_ARGUMENTS = ["direct_tun_harness::", "--test-threads=1", "--nocapture"]


def run(adb, serial, compilation, destination):
    require(serial == PHONE, "Explicit authorized physical ARM64 fixture required")
    compilation = pathlib.Path(compilation).resolve(strict=True)
    destination = pathlib.Path(destination).resolve()
    require(BUILD in destination.parents, "Store Android evidence under the build directory")
    adb = pathlib.Path(adb).resolve(strict=True)
    adb_sha = sha(adb)
    fixture = scope(adb, serial, physical=True)
    built = verify_test_compile(compilation)
    require(built["platform"] == "android" and built["target"] == TARGET, "Android ARM64 test ELF required")
    binary = checked_row(built["testBinary"])
    expected = {"sha256": sha(binary), "bytes": binary.stat().st_size}
    remote = "/data/local/tmp/mcrf-direct-tun-" + expected["sha256"][:16]
    test_log = destination / "arm64-v8a-direct-tun.log"
    proof = destination / "unix-direct-tun-proof.json"

    def command(*arguments, timeout=15, check=True):
        return subprocess.run([str(adb), "-s", serial, *arguments], timeout=timeout, check=check,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def remote_identity():
        words = command("shell", "sha256sum", remote).stdout.decode("ascii").split()
        require(len(words) == 2 and words[1] == remote, "Unexpected device SHA-256 output")
        size = command("shell", "stat", "-c", "%s", remote).stdout.decode("ascii").strip()
        require(size.isascii() and size.isdecimal(), "Unexpected device size output")
        identity = {"sha256": words[0], "bytes": int(size)}
        require(identity == expected, "Executed test ELF differs from Cargo artifact")
        return identity

    destination.mkdir()
    command("shell", "test", "!", "-e", remote)
    command("shell", "test", "!", "-L", remote)
    try:
        command("push", str(binary), remote, timeout=120)
        command("shell", "chmod", "700", remote)
        before = remote_identity()
        result = command("shell", "timeout", "-s", "KILL", "45", "env", "-i",
                         "PATH=/system/bin:/system/xbin", "TMPDIR=/data/local/tmp",
                         remote, *TEST_ARGUMENTS, timeout=55, check=False)
        test_log.write_bytes(result.stdout)
        after = remote_identity()
        require(result.returncode == 0, f"Android Unix FD regression failed, see {test_log}")
        require(verify_test_compile(compilation) == built and sha(adb) == adb_sha and
                scope(adb, serial, physical=True) == fixture, "Proof inputs or device scope changed")
    finally:
        try:
            command("shell", "rm", "-f", remote)
            command("shell", "test", "!", "-e", remote)
            command("shell", "test", "!", "-L", remote)
        except (OSError, subprocess.SubprocessError):
            (destination / "cleanup-pending.json").write_text(json.dumps({
                "serial": serial, "remote": remote, "cleanupComplete": False
            }), encoding="utf-8")
            raise

    data = {
        "state": "UNIX_DIRECT_TUN_TESTS_PASSED",
        "platform": "android", "target": TARGET, "exitCode": result.returncode,
        "providerTraffic": False, "productionActivated": False, "sourceStable": True,
        "sources": built["sources"], "testBinary": built["testBinary"],
        "compileReceipt": file_row(compilation), "testLog": file_row(test_log),
        "deviceBinaryBefore": before, "deviceBinaryAfter": after,
        "executedBinaryPath": remote, "command": [remote, *TEST_ARGUMENTS],
        "serial": serial, "fixture": fixture, "cleanupComplete": True,
        "producerSha256": sha(__file__), "adbSha256": adb_sha,
    }
    proof.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    try:
        return verify_unix_proof(proof)
    except Exception:
        proof.unlink()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--compile-receipt", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.adb, args.serial, args.compile_receipt, args.output)))


if __name__ == "__main__":
    main()
