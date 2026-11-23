"""Assemble the three ABI AARs from verified equivalent gomobile Java bindings."""
import argparse
from pathlib import Path
import shutil
import zipfile
from io import BytesIO


ABIS = ("armeabi-v7a", "arm64-v8a", "x86_64")


def java_payload(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(BytesIO(data)) as jar:
        return {row.filename: jar.read(row) for row in jar.infolist() if not row.is_dir()}


def common_payload(aar: zipfile.ZipFile, abi: str) -> dict[str, bytes]:
    entries = [row.filename for row in aar.infolist() if not row.is_dir()]
    if len(entries) != len(set(entries)):
        raise ValueError(f"Duplicate gomobile entries for {abi}")
    expected = f"jni/{abi}/libbox.so"
    if [name for name in entries if name.startswith("jni/")] != [expected]:
        raise ValueError(f"Incorrect gomobile ABI closure for {abi}")
    result = {name: aar.read(name) for name in entries if not name.startswith("jni/")}
    if "classes.jar" not in result or "AndroidManifest.xml" not in result:
        raise ValueError(f"Missing gomobile Java payload for {abi}")
    return result


def merge(stage: Path, output: Path) -> None:
    if output.exists():
        raise ValueError("Final AAR path already exists")
    common = None
    for abi in ABIS:
        with zipfile.ZipFile(stage / f"libbox-{abi}.aar") as candidate:
            current = common_payload(candidate, abi)
        if common is None:
            common = current
        elif current.keys() != common.keys() or any(
            (java_payload(data) != java_payload(common[name])) if name == "classes.jar" else (data != common[name])
            for name, data in current.items()
        ):
            raise ValueError(f"ABI-specific Java or common resources differ for {abi}")
    if common is None:
        raise ValueError("No gomobile AAR inputs")
    native = zipfile.ZIP_STORED
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as result:
        for name, data in common.items():
            result.writestr(name, data)
        for abi in ABIS:
            name = f"jni/{abi}/libbox.so"
            row = zipfile.ZipInfo(name)
            row.compress_type = native
            with zipfile.ZipFile(stage / f"libbox-{abi}.aar") as candidate:
                with candidate.open(name) as source, result.open(row, "w") as dst:
                    shutil.copyfileobj(source, dst)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    merge(args.stage, args.output)


if __name__ == "__main__":
    main()
