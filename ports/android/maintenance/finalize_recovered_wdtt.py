"""Finalize a freshly host-tested and native-built WDTT source stage."""
import argparse, hashlib, json, pathlib, shutil

def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def load(p): return json.loads(pathlib.Path(p).read_text(encoding="utf-8-sig"))

parser = argparse.ArgumentParser()
parser.add_argument("--root", required=True)
parser.add_argument("--retained", required=True)
args = parser.parse_args()
root, retained = pathlib.Path(args.root), pathlib.Path(args.retained)
stage = root / "native-stage-wdtt"
if stage.exists(): raise ValueError("Fresh WDTT stage required")
stage.mkdir()
shutil.copytree(root / "wdtt-source", stage / "module")
rows = [{"path": p.relative_to(stage / "module").as_posix(), "sha256": sha(p)}
        for p in sorted((stage / "module").rglob("*")) if p.is_file()]
(stage / "source-manifest.json").write_text(json.dumps(rows, indent=2)+"\n", encoding="utf-8")
log = root / "wdtt-host-tests.jsonl"
events = [json.loads(line) for line in log.read_text().splitlines() if line.strip()]
required = {"TestMcrfRawMTUCeilingPreservesSmallerServerValue", "TestMcrfIPRelayBuffersOnOwnedSocket",
            "TestMcrfSOCKSAdmissionQueuesAndResumes", "TestMcrfWdtt512ClientsAndOverflowBackpressure"}
passed = {e.get("Test") for e in events if e.get("Action") == "pass"}
if any(e.get("Action") in ("fail", "skip") for e in events) or not required <= passed:
    raise ValueError("WDTT host evidence incomplete")
shutil.copy2(log, stage / "host-tests.jsonl")
receipt = load(pathlib.Path(args.retained) / "receipt.json")
artifacts=[]
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    src=root / "wdtt-build" / abi / "libwdtt.so"; out=stage / abi
    out.mkdir(); shutil.copy2(src, out / src.name)
    artifacts.append({"abi":abi,"path":f"{abi}/libwdtt.so","bytes":src.stat().st_size,"sha256":sha(src)})
receipt.update({"state":"COMPILED_NOT_EXECUTED","sourceStable":True,"source":str(stage/"module"),
                "sourceManifestSha256":sha(stage/"source-manifest.json"),"artifacts":artifacts,
                "hostEvidenceSha256":sha(stage/"host-tests.jsonl"),"nativeExecuted":False,
                "sourceRecovery":{"schema":1,"canonicalRevision":"f706ccd373685bbde20cae33b2b7f400e8335ecc",
                                  "unavailableRetainedFile":"go.mod","rebuilt":True}})
(stage / "receipt.json").write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8")
print(stage)
