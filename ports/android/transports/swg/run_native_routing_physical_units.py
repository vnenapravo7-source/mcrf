"""Run both compiled native regression suites on the explicit ARM64 phone."""
import argparse
import json
import pathlib
import subprocess
import uuid

from bs_engine_inputs import require, sha
from native_routing_cli_evidence import scope
from native_routing_evidence import BUILD, GENERATIONS
from native_routing_physical_evidence import GROUPS, check_scope, compiled, unit_names, units
from prepare_csqtt_direct_tun import file_row


def run(args):
    adb = pathlib.Path(args.adb).resolve(strict=True)
    serial = args.serial
    fixture = scope(adb, serial, physical=True)
    check_scope(serial, fixture)
    output = pathlib.Path(args.output).resolve()
    require(BUILD in output.parents, "Keep Android receipts in controlled build storage")
    compilation = pathlib.Path(args.compilation).resolve(strict=True)
    binaries = compiled(compilation, args.stage)
    compilation_row = file_row(compilation)
    adb_sha = sha(adb)
    output.mkdir()

    def command(*argv, timeout=15, check=True):
        return subprocess.run([str(adb), "-s", serial, *argv], stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout, check=check)

    executions = {}
    for generation, adapter in GENERATIONS.items():
        binary = binaries[generation, "arm64-v8a", "unit"]
        identity = {"sha256": sha(binary), "bytes": binary.stat().st_size}
        root = "/data/local/tmp/mcrf-native-regression-" + uuid.uuid4().hex
        remote = root + "/tests"

        def device_identity():
            words = command("shell", "sha256sum", remote).stdout.decode("ascii").split()
            require(len(words) == 2 and words[1] == remote, "Unexpected device SHA-256 output")
            size = command("shell", "stat", "-c", "%s", remote).stdout.decode("ascii").strip()
            require(size.isascii() and size.isdecimal(), "Unexpected device size output")
            value = {"sha256": words[0], "bytes": int(size)}
            require(value == identity, "Executed Android test differs from the compiled ELF")
            return value

        command("shell", "mkdir", "-m", "700", root)
        groups = {}
        try:
            command("push", str(binary), remote, timeout=120)
            command("shell", "chmod", "700", remote)
            before = device_identity()
            for name, test_filter in GROUPS.items():
                invocation = [remote, test_filter, "--test-threads=1"]
                execution = command("shell", "timeout", "-s", "KILL", "60", "env", "-i",
                                    "PATH=/system/bin:/system/xbin", "TMPDIR=" + root,
                                    *invocation, timeout=70, check=False)
                log = output / f"{adapter}-{name}.log"
                log.write_bytes(execution.stdout)
                require(execution.returncode == 0, f"Native regression failed: {log}")
                groups[name] = {"command": invocation, "log": file_row(log), "exitCode": execution.returncode,
                                "passed": len(unit_names(log, name))}
            after = device_identity()
        finally:
            try:
                command("shell", "rm", "-f", remote)
                command("shell", "rmdir", root)
                command("shell", "test", "!", "-e", root)
            except (OSError, subprocess.SubprocessError):
                (output / "cleanup-pending.json").write_text(json.dumps({
                    "serial": serial, "root": root, "cleanupComplete": False
                }), encoding="utf-8")
                raise
        executions[generation] = {"binaryBefore": before, "binaryAfter": after, "remote": remote, "groups": groups}
    require(file_row(compilation) == compilation_row and sha(adb) == adb_sha and
            scope(adb, serial, physical=True) == fixture, "Regression inputs or device changed")
    proof = output / "native-unit-execution.json"
    proof.write_text(json.dumps({
        "state": "OWNED_PHYSICAL_ANDROID_NATIVE_REGRESSIONS_PASSED",
        "serial": serial, "fixture": fixture, "compilation": compilation_row, "adbSha256": adb_sha,
        "producerSha256": sha(__file__), "cleanupComplete": True, "providerTraffic": False,
        "productionActivated": False, "generations": executions,
    }, indent=2) + "\n", encoding="utf-8")
    try:
        return units(proof, args.stage)
    except Exception:
        proof.unlink()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("adb", "serial", "stage", "compilation", "output"):
        parser.add_argument("--" + key, required=True)
    print(json.dumps(run(parser.parse_args())))


if __name__ == "__main__":
    main()
