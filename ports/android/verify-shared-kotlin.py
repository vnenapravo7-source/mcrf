"""Check the Kotlin class ABI across a minified host and installed module APKs.

Use actual release APKs: source assertions and debug builds cannot detect R8
renaming/removal of a runtime consumed by separately compiled adapters.
This class-closure gate complements device loading and traffic acceptance.
"""
import argparse
import re
import struct
import zipfile
from pathlib import Path


def dex_types(apk):
    references, definitions = set(), set()
    with zipfile.ZipFile(apk) as archive:
        members = [n for n in archive.namelist() if re.fullmatch(r"classes(?:[1-9][0-9]*)?\.dex", n)]
        if not members:
            raise ValueError(f"No DEX in {apk}")
        for name in members:
            data = archive.read(name)
            if len(data) < 112 or data[:4] != b"dex\n":
                raise ValueError(f"Invalid DEX: {apk}:{name}")

            def uint(offset):
                return struct.unpack_from("<I", data, offset)[0]

            strings = []
            for index in range(uint(56)):
                offset = uint(uint(60) + index * 4)
                # Skip ULEB128 UTF-16 length. Descriptors use ASCII, so decoding
                # unrelated modified UTF-8 strings permissively is sufficient.
                for _ in range(5):
                    byte = data[offset]
                    offset += 1
                    if byte < 128:
                        break
                else:
                    raise ValueError("Invalid DEX string length")
                strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
            types = [strings[uint(uint(68) + index * 4)] for index in range(uint(64))]
            references.update(t.lstrip("[") for t in types)
            definitions.update(types[uint(uint(100) + index * 32)] for index in range(uint(96)))
    return references, definitions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, type=Path)
    parser.add_argument("--module", required=True, action="append", type=Path)
    args = parser.parse_args()
    _, host = dex_types(args.host)
    failed = False
    for module in args.module:
        references, definitions = dex_types(module)
        # Do not merge unrelated module definitions: each has its own child
        # loader and can resolve only itself and the host parent.
        missing = sorted(t for t in references - definitions - host if t.startswith("Lkotlin/"))
        print(f"{module.name}: {len(missing)} missing Kotlin classes")
        for descriptor in missing:
            print(f"  {descriptor}")
        failed |= bool(missing)
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
