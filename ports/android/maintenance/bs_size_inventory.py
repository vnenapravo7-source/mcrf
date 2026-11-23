"""Read-only existing APK inventory/estimate. These APKs are NOT build inputs."""
import argparse
import hashlib
import json
import pathlib
import zipfile
import zlib

KINDS = ("csqtt", "csqtt20", "csqtt215", "csqtt25", "qwdtt", "wdtt")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")


def inventory(root, build_type="release"):
    result = {"scope": "Observed legacy APKs only; future shared wrapper size may change. Not native provenance.", "abis": {}}
    for abi in ABIS:
        rows, unique, count_bytes = [], {}, 0
        for kind in KINDS:
            path = root / kind / build_type / f"engines-{kind}-{abi}-{build_type}.apk"
            if not path.is_file():
                raise ValueError(f"Missing {path}")
            with zipfile.ZipFile(path) as archive:
                native = []
                for entry in archive.infolist():
                    if entry.filename.startswith(f"lib/{abi}/") and not entry.is_dir():
                        data = archive.read(entry)
                        digest = hashlib.sha256(data).hexdigest()
                        count_bytes += len(data)
                        native.append({"name": entry.filename, "bytes": len(data), "sha256": digest})
                        unique.setdefault(digest, (len(data), len(zlib.compress(data, 9))))
                rows.append({"kind": kind, "apkBytes": path.stat().st_size, "native": native})
        native_unique = sum(v[0] for v in unique.values())
        zipped = sum(v[1] for v in unique.values())
        apk_sum = sum(row["apkBytes"] for row in rows)
        result["abis"][abi] = {"legacyApkBytes": apk_sum, "legacyNativeBytes": count_bytes,
            "uniqueNativeBytes": native_unique, "identicalNativeAvoidedBytes": count_bytes-native_unique,
            "estimatedBsApkBytes": [zipped+32768, zipped+524288],
            "estimatedBsInstalledBytes": [native_unique+zipped+32768, native_unique+zipped+524288], "packages": rows}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-root", required=True)
    parser.add_argument("--build-type", default="release", choices=("release", "debug"))
    args = parser.parse_args()
    data = inventory(pathlib.Path(args.apk_root), args.build_type)
    print(json.dumps(data, indent=2))
