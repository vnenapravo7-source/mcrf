"""Test-only manifest identity change; every non-manifest payload stays identical.

No production DEX/resource recompilation and no edits to the installed main APK.
The caller must align/sign the output with the existing development signer.
"""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

SOURCE_SHA = "0e28d3338902a83b29b3401fd7ca55751a5fb061d3878d8506343bcff8a053a4"
CHANGES = {"org.mcrf.client": "org.mcrf.p3test",
           "org.mcrf.client.engine-package": "org.mcrf.p3test.engine-package",
           "MCRF": "MCRF · тест П3"}

def length(data, pos, utf8):
    fmt, step, flag = ("<B", 1, 0x80) if utf8 else ("<H", 2, 0x8000)
    n = struct.unpack_from(fmt, data, pos)[0]
    if n & flag:
        return ((n & (flag - 1)) << (8 * step)) | struct.unpack_from(fmt, data, pos + step)[0], pos + 2 * step
    return n, pos + step

def encode_length(n, utf8):
    if utf8:
        return bytes([n]) if n < 128 else bytes([(n >> 8) | 128, n & 255])
    return struct.pack("<H", n) if n < 32768 else struct.pack("<HH", (n >> 16) | 32768, n & 65535)

def manifest(data):
    assert struct.unpack_from("<HH", data) == (3, 8)
    assert struct.unpack_from("<H", data, 8)[0] == 1
    typ, head, size, count, styles, flags, start, style_start = struct.unpack_from("<HH6I", data, 8)
    assert head == 28 and styles == 0 and style_start == 0
    utf8 = bool(flags & 256)
    offsets = struct.unpack_from(f"<{count}I", data, 8 + head)
    pool = bytearray(); new_offsets = []; changed = []
    for off in offsets:
        pos = 8 + start + off
        units, pos = length(data, pos, utf8)
        if utf8:
            size_bytes, pos = length(data, pos, True)
            value = data[pos:pos + size_bytes].decode("utf-8")
        else:
            value = data[pos:pos + units * 2].decode("utf-16le")
        if value in CHANGES:
            changed.append(value); value = CHANGES[value]
        new_offsets.append(len(pool))
        encoded = value.encode("utf-8" if utf8 else "utf-16le")
        pool += encode_length(len(value.encode("utf-16le")) // 2, utf8)
        if utf8: pool += encode_length(len(encoded), True)
        pool += encoded + (b"\0" if utf8 else b"\0\0")
    assert sorted(changed) == sorted(CHANGES), changed
    pool += b"\0" * (-len(pool) % 4)
    new_start = head + 4 * count
    chunk = struct.pack("<HH6I", typ, head, new_start + len(pool), count, 0, flags & ~1, new_start, 0)
    chunk += struct.pack(f"<{count}I", *new_offsets) + pool
    result = bytearray(data[:8] + chunk + data[8 + size:])
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result)

source, output = map(Path, sys.argv[1:3])
if len(sys.argv) == 4:
    assert len(sys.argv[3]) == 64 and all(c in "0123456789abcdef" for c in sys.argv[3])
    SOURCE_SHA = sys.argv[3]  # Explicit successful correction APK pin, never inferred from the file.
assert hashlib.sha256(source.read_bytes()).hexdigest() == SOURCE_SHA
assert not output.exists()
payload = {}
with zipfile.ZipFile(source) as src, zipfile.ZipFile(output, "w") as dst:
    for entry in src.infolist():
        if entry.filename.startswith("META-INF/"): continue
        value = src.read(entry.filename)
        if entry.filename == "AndroidManifest.xml": value = manifest(value)
        else: payload[entry.filename] = hashlib.sha256(value).hexdigest()
        dst.writestr(entry, value)
with zipfile.ZipFile(output) as dst:
    assert all(hashlib.sha256(dst.read(name)).hexdigest() == sha for name, sha in payload.items())
output.with_suffix(".isolation.json").write_text(json.dumps({"sourceSha256": SOURCE_SHA,
    "isolatedPackage": "org.mcrf.p3test", "changedOnly": ["AndroidManifest.xml", "signatures"],
    "unchangedPayloadSha256": payload, "productionRecompiled": False}, indent=2), encoding="utf-8")
print("ISOLATED_MANIFEST_AND_UNCHANGED_PAYLOAD_VERIFIED")
