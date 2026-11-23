"""Source-bound Android kernel acceptance for the additive original-IP bridge.

This proves the Go hand-off/relay tests, not module SPI or provider throughput.
"""
import argparse
import pathlib
import re
import struct
from bs_engine_inputs import bound, load, require, sha, verify_multimode_preparation

CASES = {
    "main": ("TestMcrfOriginalPacketBridgeOwnedUDPAndRetirement", "TestMcrfOriginalPacketPrivateConfig"),
    "fd": ("TestMcrfOriginalPacketPortDirectReadBounds",
           "TestMcrfOriginalPacketPortHandshakePreservesPacketsAndFragments",
           "TestMcrfOriginalPacketPortRejectsWrongAdoptionNonce",
           "TestMcrfOriginalPacketPortCancellationAndInvalidPackets"),
}


def verify(path, prepared_sha):
    receipt = load(path)
    require(receipt.get("state") == "ANDROID_ORIGINAL_PACKET_TESTS_PASSED" and
            receipt.get("preparedSourceSha256") == prepared_sha,
            "Original packet execution belongs to another source")
    prepared_path = bound(receipt["prepared"])
    require(sha(prepared_path) == prepared_sha, "Prepared source changed")
    prepared = load(prepared_path)
    require(prepared.get("originalPacketContract") == 1, "Original packet source contract required")
    verify_multimode_preparation(prepared)
    rows = receipt["executions"]
    if receipt.get("scope") == "owned-physical-arm64":
        require(prepared.get("privatePacketBridgeContract") == 1 and
                receipt.get("providerTraffic") is False and receipt.get("remoteCleanupConfirmed") is True and
                receipt.get("deviceAbi") == "arm64-v8a" and receipt.get("shellUid") == 2000 and
                type(receipt.get("deviceSdk")) is int and receipt["deviceSdk"] >= 29 and
                isinstance(receipt.get("serial"), str) and bool(re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", receipt["serial"])) and
                not receipt["serial"].startswith("emulator-"),
                "Authenticated original bridge requires owned physical Android scope")
        expected_abis = ("arm64-v8a",)
    else:
        require("scope" not in receipt, "Unknown original packet execution scope")
        expected_abis = ("arm64-v8a", "x86_64")
    require(len(rows) == len(expected_abis)*len(CASES) and {(r["abi"], r["suite"]) for r in rows} ==
            {(abi, suite) for abi in expected_abis for suite in CASES},
            "Exact Android architectures and affected suites required")
    for row in rows:
        header = bound(row["binary"]).read_bytes()[:20]
        require(header[:6] == b"\x7fELF\x02\x01" and
                struct.unpack_from("<H", header, 18)[0] ==
                {"arm64-v8a": 183, "x86_64": 62}[row["abi"]], "Execution binary ABI mismatch")
        log = bound(row["log"]).read_text(encoding="utf-8-sig")
        require(re.search(r"^PASS\s*$", log, re.M) is not None and
                not re.search(r"^(?:FAIL|--- (?:FAIL|SKIP):)", log, re.M), "Android execution failed/skipped")
        for case in CASES[row["suite"]]:
            require(len(re.findall(r"^--- PASS: " + re.escape(case) + r" \(", log, re.M)) == 1,
                    "Missing/duplicate meaningful Android case")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--prepared-sha256", required=True)
    args = parser.parse_args()
    require(sha(pathlib.Path(args.receipt)) == args.sha256, "Execution receipt changed")
    verify(args.receipt, args.prepared_sha256)
    print("Original packet Android kernel evidence verified; no module/provider claim")
