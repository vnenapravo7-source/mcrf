"""Package alternate fixture assets into one frozen signed AndroidTest APK code build."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

from bs_engine_inputs import require, sha
from native_routing_evidence import BUILD
from native_routing_physical_evidence import SRS_SHA256
from prepare_csqtt_direct_tun import file_row

NATIVE = "lib/arm64-v8a/libmcrf_owned_native_udp_fixture.so"
METADATA = "assets/bs-owned-native-udp-fixture.json"
SRS = "assets/bs-owned-bootstrap-routes.srs"
PAYLOAD = {NATIVE, METADATA}


def entries(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and not any(name.upper().startswith("META-INF/") and
                name.upper().endswith((".MF", ".RSA", ".DSA", ".EC", ".SF")) for name in names),
                "Expected one v2/v3 signed APK and unique archive entries")
        identities = {}
        for name in names:
            with archive.open(name) as stream:
                identities[name] = hashlib.file_digest(stream, "sha256").hexdigest()
        return identities


def package(args):
    baseline = pathlib.Path(args.baseline).resolve(strict=True)
    out = pathlib.Path(args.output).resolve(strict=True)
    require(BUILD in baseline.parents and BUILD in out.parents and sha(baseline) == args.baseline_sha256,
            "Exact frozen base AndroidTest APK required")
    build_tools = pathlib.Path(args.build_tools).resolve(strict=True)
    aapt = build_tools / "aapt.exe"
    zipalign = build_tools / "zipalign.exe"
    sign = [args.java, "--enable-native-access=ALL-UNNAMED", "-jar", str(build_tools / "lib/apksigner.jar")]

    def signer(apk):
        output = subprocess.run([*sign, "verify", "--print-certs", str(apk)], capture_output=True,
                                check=True, encoding="utf-8", timeout=30).stdout
        certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$", output, re.MULTILINE)
        require(len(certs) == 1, "Exactly one APK signer required")
        return certs[0]

    def verify_test_manifest(apk):
        text = subprocess.run([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"], capture_output=True,
                              check=True, encoding="utf-8", timeout=30).stdout
        require('package="org.mcrf.client.test"' in text and
                re.search(r"android:testOnly.*0xffffffff", text) and
                re.search(r"android:debuggable.*0xffffffff", text),
                "Owned testOnly / DEBUG AndroidTest package required")

    verify_test_manifest(baseline)
    base_sha = entries(baseline)
    base_signer = signer(baseline)
    require(PAYLOAD <= base_sha.keys() and base_sha[SRS] == SRS_SHA256, "Frozen fixture APK payload missing")
    rows = []
    for location in args.stage:
        stage = pathlib.Path(location).resolve(strict=True)
        require(BUILD in stage.parents, "Owned fixture stage must stay in Android build storage")
        receipt = stage / "receipt.json"
        data = json.loads(receipt.read_text(encoding="utf-8"))
        require(data["stage"] == "debug-only-bs-physical-native-bootstrap" and data["abi"] == "arm64-v8a" and
                data["generation"] in ("2.5.1", "2.5.3") and data["providerTraffic"] is False and
                data["productionActivated"] is False and data["srsSha256"] == SRS_SHA256, "Wrong staged fixture")
        prefix = "direct" if data.get("directOwnerCapture") is True else "bootstrap"
        generation = data["generation"].replace(".", "")
        name = f"runner-{prefix}-g{generation}.apk"
        native = stage / "fixtures/arm64-v8a/libmcrf_owned_native_udp_fixture.so"
        metadata = stage / "test-assets/bs-owned-native-udp-fixture.json"
        metadata_json = json.loads(metadata.read_text(encoding="utf-8"))
        require(sha(native) == data["sha256"] and native.stat().st_size == data["bytes"] and
                sha(metadata) == data["metadataSha256"] and all(metadata_json.get(key) == data.get(key) for key in
                    ("abi", "generation", "bytes", "sha256", "srsSha256", "directOwnerCapture")), "Staged bytes changed")
        require(sha(stage / "test-assets/bs-owned-bootstrap-routes.srs") == base_sha[SRS], "Owned SRS differs")
        output = out / name
        unsigned = out / (name + ".unsigned")
        aligned = out / (name + ".aligned")
        for path in (output, unsigned, aligned):
            require(not path.exists(), "Fresh scoped output required: " + path.name)
        replacement = {NATIVE: native.read_bytes(), METADATA: metadata.read_bytes()}
        with zipfile.ZipFile(baseline) as src, zipfile.ZipFile(unsigned, "x") as dst:
            for info in src.infolist():
                dst.writestr(info, replacement[info.filename] if info.filename in replacement else src.read(info))
        subprocess.run([str(zipalign), "-P", "16", "4", str(unsigned), str(aligned)], check=True, timeout=60)
        password = args.password_env
        require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", password), "Invalid password variable name")
        subprocess.run([*sign, "sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias,
                        "--ks-pass", "env:" + password, "--key-pass", "env:" + password,
                        "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                        "--v3-signing-enabled", "true", "--out", str(output), str(aligned)],
                       check=True, timeout=60)
        subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(output)], check=True, timeout=30)
        verify_test_manifest(output)
        actual = entries(output)
        expected = {**base_sha, NATIVE: data["sha256"], METADATA: data["metadataSha256"]}
        require(actual == expected and signer(output) == base_signer, "Nonfixture APK entry or signer changed")
        rows.append({"apk": file_row(output), "stageReceipt": file_row(receipt),
                     "generation": data["generation"], "purpose": prefix, "nativeSha256": actual[NATIVE]})
        for intermediate in (unsigned, aligned):
            intermediate.unlink()
        print(name, sha(output), flush=True)
    require(sha(baseline) == args.baseline_sha256, "Frozen code APK changed during fixture packaging")
    proof = out / "runner-variant-identities.json"
    with proof.open("x", encoding="utf-8") as stream:
        json.dump({"baseline": file_row(baseline), "sameSignerSha256": base_signer,
                   "unchangedEntries": {name: value for name, value in base_sha.items() if name not in PAYLOAD},
                   "variants": rows, "producerSha256": sha(__file__)}, stream, indent=2)
        stream.write("\n")
    return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("baseline", "baseline-sha256", "output", "build-tools", "java", "keystore", "key-alias", "password-env"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--stage", action="append", required=True)
    print(package(parser.parse_args()))


if __name__ == "__main__":
    main()
