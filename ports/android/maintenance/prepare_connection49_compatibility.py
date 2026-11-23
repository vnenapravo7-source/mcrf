"""Freeze accepted10023 plus ONLY the reviewed P0 compatibility overlay.
No compiler, phone, publication or unfinished mixed-server P2 inputs.
"""
import hashlib,json,shutil,uuid
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path('D:/MCRF/Builds/Android/p049-recovery-4b4fc542e5234a6b966455622d52a30e')
STEER=Path('D:/MCRF/Builds/Android/p1-steer-candidate-f84fa510d8874bd7b89a09f4e1b06245')
K='ports/android/app/src/main/kotlin/org/mcrf/client/'
T='ports/android/app/src/test/kotlin/org/mcrf/client/'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,value):p.write_text(json.dumps(value,indent=2),encoding='utf-8')
def copy(source,dest):
    assert source.is_file() and not source.is_symlink()
    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
    assert sha(source)==sha(dest)
def main():
    current=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    assert not any(current['jobs'].values()),'Resume existing job'
    prior=json.loads((PARENT/'build.json').read_text('utf-8-sig'))
    assert prior['state']=='PASS_R8_RECOVERY_AND_156_JVM' and prior['sourceStable']
    assert sha(PARENT/'SOURCE_MANIFEST.json')==prior['sourceManifestSha256']
    stage=Path('D:/MCRF/Builds/Android')/('p049-compat-'+uuid.uuid4().hex)
    stage.mkdir();source=stage/'source';source.mkdir()
    rows=json.loads((PARENT/'SOURCE_MANIFEST.json').read_text('utf-8'))
    for row in rows:
        old=PARENT/'source'/row['path'];assert sha(old)==row['sha256'];copy(old,source/row['path'])
    overlays=[K+x for x in ['OptionalCoreConfig.kt','OptionalCorePlan.kt','OptionalCoreTuning.kt','OptionalCoreProcess.kt','OptionalServerDns.kt']]+[T+'OptionalServerDnsTest.kt']
    for rel in overlays:copy(ROOT/rel,source/rel)
    assert 'NativeRoutePreparation' not in (source/K/'McrfVpnService.kt').read_text('utf-8')
    assert 'RouteServerPlan' not in (source/K/'McrfVpnService.kt').read_text('utf-8')
    build=source/'ports/android/app/build.gradle.kts';text=build.read_text('utf-8')
    assert text.count('versionCode = 10023')==1 and text.count('0.1.0-preview.20261020.3')==1
    build.write_text(text.replace('versionCode = 10023','versionCode = 10024').replace('0.1.0-preview.20261020.3','0.1.0-preview.20261020.4'),encoding='utf-8')
    # Native optional sources are a narrow independent directory, not P2's
    # dispatcher, application, provider, account or process-group implementations.
    native=stage/'native'
    for rel in ['ports/android/transports/xray','ports/android/transports/tls-core','ports/android/optional-cores/bridge']:
        for file in (ROOT/rel).iterdir():
            if file.is_file() and (file.suffix=='.go' or file.name in ['go.mod','go.sum']):copy(file,native/file.relative_to(ROOT))
    for name in ['build.ps1','prepare_overlay.py','verify_candidates.py','verify_cli.py']:
        rel='ports/android/optional-cores/'+name;copy(ROOT/rel,native/rel)
    # Retain the precise P1 Steer sources/Java/Gradle/capabilities. Overlay four
    # protocol parser files only, never canonical P2 JNI/probe/route code.
    old=json.loads((STEER/'producer-receipt.json').read_text('utf-8'))
    assert old['sourceStable'] and old['state']=='compiled_candidate_not_runtime_accepted'
    frozen=Path(old['frozenSourceDirectory']);steer=stage/'steer-source'
    for rel,row in old['inputs'].items():
        file=frozen/rel;assert sha(file)==row['sha256'];copy(file,steer/rel)
    croot='work/steer-android-port/'
    for rel in ['src/proto/vless/vless_proto.c','src/proto/vless/vless_proto.h','src/proto/vless/vldial.c','src/proto/vless/vldial.h','tests/vless-response-stream.c']:
        copy(ROOT/croot/rel,steer/croot/rel)
    manifests={}
    for name,base in [('app',source),('optional',native),('steer',steer)]:
        inventory=[{'path':p.relative_to(base).as_posix(),'sha256':sha(p)} for p in sorted(base.rglob('*')) if p.is_file()]
        save(stage/(name+'-inputs.json'),inventory);manifests[name]=sha(stage/(name+'-inputs.json'))
    save(stage/'preparation.json',{'state':'SOURCE_FROZEN_NOT_VERIFIED','sourceReady':True,'parent':str(PARENT),'parentReceiptSha256':sha(PARENT/'build.json'),'retainedTests':156,'manifestHashes':manifests,'p2Excluded':True,'candidateVersionCode':10024,'phoneChanged':False,'published':False,'nativeRuntimeAccepted':False,'steerRealityRootCauseConfirmed':False})
    save(ROOT/'ports/android/coordination/p0-49-compatibility-stage.json',{'stage':str(stage),'state':'SOURCE_FROZEN_NOT_VERIFIED','readiness':False})
    print(json.dumps({'stage':str(stage),'p2Excluded':True,'inputs':manifests}))
if __name__=='__main__':main()
