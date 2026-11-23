"""Build a same-signer Android test-only BS module from the exact synthetic fixture.

The APK contains no provider-capable payload. It is not a publishable or
production BS module. Signing credentials are supplied only to apksigner.
"""
import argparse
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as xml
import zipfile

from bs_engine_inputs import require, sha
from native_routing_evidence import BUILD, GENERATIONS
from native_routing_physical_evidence import MODULE_PACKAGE, apk_digest, compiled
from prepare_csqtt_direct_tun import file_row

ANDROID = "http://schemas.android.com/apk/res/android"
ENGINES = ("csqtt", "csqtt20", "csqtt215", "csqtt25", "qwdtt")


def manifest(path, version, generation):
    xml.register_namespace("android", ANDROID)

    def attrs(**values):
        return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}

    root = xml.Element("manifest", {"package": MODULE_PACKAGE, **attrs(versionCode=version,
                       versionName="owned-bs-" + generation)})
    xml.SubElement(root, "uses-sdk", attrs(minSdkVersion=29, targetSdkVersion=34))
    app = xml.SubElement(root, "application", attrs(label="MCRF BS Owned Fixture", debuggable="true",
                         testOnly="true", hasCode="false", allowBackup="false", extractNativeLibs="true"))
    metadata = {key: 1 for key in (
        "interface", "bs", "bs.rust_multicall", "swg", "csqtt_process_group", "csqtt_account_options",
        "turn_hash_pool", "csqtt_historical_options", "csqtt_captcha_ids", "csqtt25_swg_panel",
        "csqtt25_swg_252_253", "qwdtt_fingerprint", "bs_routing_session",
    )}
    metadata.update({"kind": "bs", "bs.logical": ",".join(ENGINES), "wdtt_provider_options": 0, "wdtt_masque": 0,
                     "wdtt_dual_family": 0, "csqtt_direct_tun_fd": 2, "bs_routing_generations": generation})
    for name, value in metadata.items():
        xml.SubElement(app, "meta-data", attrs(name="org.mcrf.engines." + name, value=value))
    xml.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def package(args):
    native = compiled(args.compilation, args.stage)[args.generation, "arm64-v8a", "bootstrap"]
    out = pathlib.Path(args.output).resolve()
    require(BUILD in out.parents and 1 <= args.version_code <= 2_100_000_000, "Fresh scoped Android module output required")
    aapt = pathlib.Path(args.build_tools).resolve(strict=True) / "aapt.exe"
    zipalign = aapt.with_name("zipalign.exe")
    apksigner = aapt.parent / "lib/apksigner.jar"
    sign = [args.java, "-jar", str(apksigner)]
    main_apk = pathlib.Path(args.main_apk).resolve(strict=True)

    def signer(apk):
        result = subprocess.run([*sign, "verify", "--print-certs", str(apk)], capture_output=True,
                                check=True, encoding="utf-8", timeout=30)
        digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$", result.stdout, re.MULTILINE)
        require(len(digests) == 1, "Exactly one APK signer required")
        return digests[0]

    main_signer = signer(main_apk)
    out.mkdir()
    manifest_path = out / "AndroidManifest.xml"
    manifest(manifest_path, args.version_code, args.generation)
    unsigned = out / "unsigned.apk"
    aligned = out / "aligned.apk"
    apk = out / "bs-native-test-only-arm64.apk"
    subprocess.run([str(aapt), "package", "-M", str(manifest_path), "-I", args.android_jar,
                    "-F", str(unsigned)], check=True, timeout=60)
    binaries = ("libbs.so", "libcsqtt_transport.so")
    native_row = {"bytes": native.stat().st_size, "sha256": sha(native)}
    catalog = {"schema": 4, "package": MODULE_PACKAGE, "layout": "rust-multicall-v1",
               "engines": {name: {"executable": "libbs.so"} for name in ENGINES},
               "native": {"arm64-v8a": {name: native_row for name in binaries}}}
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("assets/mcrf-bs.json", json.dumps(catalog) + "\n")
        for name in binaries:
            archive.write(native, "lib/arm64-v8a/" + name)
    subprocess.run([str(zipalign), "-P", "16", "4", str(unsigned), str(aligned)], check=True, timeout=60)
    # Default debug credentials need not be disclosed. Both variables may be
    # absent, in which case apksigner handles its usual prompt.
    sign_args = [*sign, "sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias]
    if args.store_password_env:
        require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.store_password_env), "Invalid password variable name")
        sign_args += ["--ks-pass", "env:" + args.store_password_env]
    if args.key_password_env:
        require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.key_password_env), "Invalid password variable name")
        sign_args += ["--key-pass", "env:" + args.key_password_env]
    subprocess.run([*sign_args, "--out", str(apk), str(aligned)], check=True, timeout=60)
    require(signer(apk) == main_signer and all(apk_digest(apk, f"lib/arm64-v8a/{name}") == native_row["sha256"]
            for name in binaries), "Signed test-only module does not match Main or the compiled ELF")
    receipt = {"testOnly": True, "generation": args.generation, "abi": "arm64-v8a", "providerTraffic": False,
               "productionActivated": False, "mainSignerSha256": main_signer, "apk": file_row(apk),
               "native": file_row(native), "manifest": file_row(manifest_path), "mainApk": file_row(main_apk),
               "compilation": file_row(args.compilation), "producerSha256": sha(__file__)}
    (out / "module-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    # Only remove the two intermediates created by this invocation.
    unsigned.unlink()
    aligned.unlink()
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("stage", "compilation", "output", "main-apk", "build-tools", "android-jar", "java", "keystore", "key-alias"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--generation", choices=GENERATIONS, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--store-password-env")
    parser.add_argument("--key-password-env")
    print(package(parser.parse_args()))


if __name__ == "__main__":
    main()
