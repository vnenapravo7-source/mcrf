"""Read-only comparison of owned sources and JUnit XML in an existing main snapshot."""
from pathlib import Path
import hashlib, json, re, sys, uuid, xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent.parent
work=root.parent.parent/"work"
snapshot=Path(sys.argv[1]).resolve()
retention=json.loads((root/"coordination/build-retention.json").read_text(encoding="utf-8-sig"))
parents={work.resolve(),Path(retention["generatedScratchRoot"]).resolve()}
assert snapshot.parent in parents and re.fullmatch(r"android-integration-[0-9a-f]{32}",snapshot.name)
archived=not snapshot.exists()
evidence=root/"coordination/build-history"/snapshot.name if archived else snapshot
assert evidence.is_dir(),"No retained snapshot or evidence archive"
source=evidence/"ports/android"
manifest=json.loads((evidence/"source-manifest.json").read_text(encoding="utf-8-sig"))
hashes={item["path"].replace("\\","/"):item["sha256"] for item in manifest}
checks=[]
for directory in ["app/src/main/kotlin/org/mcrf/client","app/src/test/kotlin/org/mcrf/client","app/src/androidTest/kotlin/org/mcrf/client"]:
    for file in sorted((root/directory).glob("*.kt")):
        if not file.name.startswith(("OptionalCore","Xray")):continue
        relative=file.relative_to(root);other=source/relative
        live_hash=hashlib.sha256(file.read_bytes()).hexdigest()
        snapshot_hash=hashes.get("ports/android/"+relative.as_posix()) if archived else hashlib.sha256(other.read_bytes()).hexdigest() if other.exists() else None
        checks.append({"path":relative.as_posix(),"live_sha256":live_hash,"snapshot_sha256":snapshot_hash,"matches":snapshot_hash==live_hash})
suites=[]
for file in sorted((source/"app/build/test-results/testDebugUnitTest").glob("*OptionalCore*.xml")):
    suite=ET.parse(file).getroot()
    suites.append({key:suite.attrib[key] for key in ["name","tests","failures","errors"]})
result={"snapshot":snapshot.name,"evidenceMode":"archived-manifest-xml" if archived else "retained-source-xml","sources":checks,"suites":suites,"owned_tests":sum(int(x["tests"]) for x in suites),"all_owned_sources_match":all(x["matches"] for x in checks),"all_owned_tests_pass":len(suites)==4 and all(int(x["failures"])==0 and int(x["errors"])==0 for x in suites),"device_acceptance":False}
output=root/"optional-cores/build"/("integration-audit-"+uuid.uuid4().hex+".json")
output.write_text(json.dumps(result,indent=2),encoding="utf-8")
if "--historical" not in sys.argv:
    (root/"optional-cores/current-integration-audit.json").write_text(json.dumps({"receipt":str(output)},indent=2),encoding="utf-8")
print(json.dumps({key:result[key] for key in ["snapshot","owned_tests","all_owned_sources_match","all_owned_tests_pass","device_acceptance"]}))
