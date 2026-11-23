"""Read-only staged ELF/receipt checks; no Android or production filesystem operations."""
from pathlib import Path
import hashlib, json, struct, sys

root=Path(__file__).resolve().parent
receipt=Path(sys.argv[1]).resolve()
if root not in receipt.parents:
    raise SystemExit("Receipt outside optional cores")
data=json.loads(receipt.read_text(encoding="utf-8-sig"))
checks=[]
for item in data["files"]:
    path=Path(item["file"]).resolve()
    if receipt.parent not in path.parents:
        raise SystemExit("Candidate outside snapshot")
    raw=path.read_bytes()
    assert hashlib.sha256(raw).hexdigest()==item["sha256"] and len(raw)==item["bytes"]
    assert raw[:4]==b"\x7fELF" and raw[5]==1
    bits=raw[4]
    etype,machine=struct.unpack_from("<HH",raw,16)
    assert etype==3 and machine=={"arm64-v8a":183,"armeabi-v7a":40,"x86_64":62}[item["abi"]]
    if bits==2:
        phoff=struct.unpack_from("<Q",raw,32)[0];entrysize,count=struct.unpack_from("<HH",raw,54)
    else:
        phoff=struct.unpack_from("<I",raw,28)[0];entrysize,count=struct.unpack_from("<HH",raw,42)
    alignments=[];interpreter=None
    for i in range(count):
        entry=phoff+i*entrysize
        if bits==2:
            kind,flags,offset,vaddr,paddr,filesz,memsz,align=struct.unpack_from("<IIQQQQQQ",raw,entry)
        else:
            kind,offset,vaddr,paddr,filesz,memsz,flags,align=struct.unpack_from("<IIIIIIII",raw,entry)
        if kind==1:
            assert align>=16384 and offset%align==vaddr%align
            alignments.append(align)
        if kind==3:
            interpreter=raw[offset:offset+filesz].rstrip(b"\0").decode()
    assert alignments and interpreter==("/system/bin/linker" if bits==1 else "/system/bin/linker64")
    checks.append({"kind":item["kind"],"abi":item["abi"],"sha256":item["sha256"],"elf_type":"PIE","interpreter":interpreter,"load_alignments":alignments})
output={"result":"passed","device_runtime_verified":False,"checks":checks}
(receipt.parent/"elf-verification.json").write_text(json.dumps(output,indent=2),encoding="utf-8")
print(json.dumps({"passed":len(checks),"receipt":str(receipt)}))
