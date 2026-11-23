"""Freeze/build only bounded Steer handshake diagnostics; retain all Go modules."""
import json,os,shutil,uuid,zipfile
from pathlib import Path
import build_connection49_android_native as b
ROOT=b.ROOT
PARENT=Path(json.loads((ROOT/'ports/android/coordination/p0-49-followup-stage.json').read_text('utf-8'))['stage'])
VERSION=9
SCOPE='Bounded numeric TLS handshake progress only'
EXTRA=['src/proto/transport/trsec.c','src/proto/tls/tls13.c','src/proto/tls/diag49.h']
WIRE_FIXTURES=False
CLEANUP='followup-owned-cleanup.json'
BIND_FIX=False
POLICY_FIXTURE='hello-record49'
NEW_PEER=False
def main():
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT)
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    assert json.loads((Path('D:/MCRF/Builds/Android/p049-phone-29ab72fdecd74d03a163fb5cfa7c0349')/CLEANUP).read_text('utf-8'))['state']=='PASS'
    parent_native=json.loads((PARENT/'android-native.json').read_text('utf-8'));assert parent_native['state']=='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
    stage=Path('D:/MCRF/Builds/Android')/('p049-steerdiag-'+uuid.uuid4().hex);stage.mkdir()
    rows=json.loads((PARENT/'steer-inputs.json').read_text('utf-8'))
    for row in rows:
        old=PARENT/'steer-source'/row['path'];assert b.sha(old)==row['sha256']
        new=stage/'steer-source'/row['path'];new.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(old,new)
    for rel in EXTRA:
        rel='work/steer-android-port/'+rel;new=stage/'steer-source'/rel;shutil.copyfile(ROOT/rel,new)
    if BIND_FIX:
        # NEVER copy the canonical P2 JNI bridge. Patch only the inherited P1
        # two-line hostname binding inside runNative, plus one local include.
        path=stage/'steer-source/work/steer-android-port/android-vpn/core/src/main/cpp/bridge.c'
        old=path.read_text('utf-8');before=b.sha(path)
        include='#include "hostdial.h"';assert old.count(include)==1
        hunk='            if(!node->sni[0]) snprintf(node->sni,sizeof(node->sni),"%s",node->host);\n            snprintf(node->host,sizeof(node->host),"%s",address);'
        assert old.count(hunk)==1
        path.write_text(old.replace(include,include+'\n#include "node_bind49.h"').replace(hunk,'            steer_bind_node49(node,address);'),encoding='utf-8')
        b.save(stage/'isolated-bridge-overlay.json',{'parentSha256':before,'candidateSha256':b.sha(path),'wholeCanonicalBridgeCopied':False,'hunks':['local header include','P1 runNative two-line binding only']})
    rows=[{'path':p.relative_to(stage/'steer-source').as_posix(),'sha256':b.sha(p)} for p in sorted((stage/'steer-source').rglob('*')) if p.is_file()];b.save(stage/'steer-inputs.json',rows)
    b.save(stage/'preparation.json',{'sourceReady':True,'p2Excluded':True,'parent':str(PARENT),'steerVersionCode':VERSION,'optionalVersionCode':6,'packageFlavors':['steer'],'mainUnchanged':10024,'xrayUnchanged':5,'tlsUnchanged':6,'scope':SCOPE})
    # Unchanged app/Go suites remain exact retained inputs, not new acceptance.
    retained=PARENT
    for depth in range(4):
        if (retained/'app-inputs.json').exists():break
        retained=Path(json.loads((retained/'preparation.json').read_text('utf-8'))['parent'])
    assert (retained/'app-inputs.json').exists()
    for name,sub in [('app','source'),('optional','native')]:
        for row in json.loads((retained/(name+'-inputs.json')).read_text('utf-8')):assert b.sha(retained/sub/row['path'])==row['sha256']
    b.save(stage/'verification.json',{'state':'PASS_COMMON_MODELS_HOST_NATIVE_ANDROID_COMPILE','retainedParentReceiptSha256':b.sha(PARENT/'verification.json'),'appGoSourceExact':True,'scope':'Only diagnostic C compile pending; unchanged64app/3TLS/bridge/Xray retained','runtimeAccepted':False})
    owner='P049_STEER_DIAG:'+str(stage);live['jobs']['build']=owner;b.save(current,live)
    result={'state':'BUILDING','p2Excluded':True,'phoneTouched':False,'published':False,'phases':{'optional':parent_native['phases']['optional']}}
    b.save(ROOT/'ports/android/coordination/p0-49-steerdiag-stage.json',{'stage':str(stage)})
    try:
        short=Path('D:/MCRF/Builds/Android')/('p1s-'+uuid.uuid4().hex[:12]);short.mkdir()
        for row in rows:
            target=short/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(stage/'steer-source'/row['path'],target);assert b.sha(target)==row['sha256']
        project=short/'work/steer-android-port/android-vpn';gate=short/'ports/android/coordination/p1-progress-2026-10-17.json'
        env=os.environ.copy();env.update(JAVA_HOME='C:/Program Files/Java/jdk-17',ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk',GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'))
        env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
        b.call([Path(env['JAVA_HOME'])/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':core:assembleRelease','-PsteerP1Candidate=true','-Pp1SourceReadyReceipt='+str(gate),'-Pp1SourceReadySha256='+b.sha(gate),'-Pp1NativeScratch='+str(short/'cxx'),'--offline','--console=plain','--no-daemon','--max-workers=4'],project,env,stage/'steer-native.log')
        delivery=stage/'primary-stage';shutil.copytree(PARENT/'primary-stage',delivery);runtime=delivery/'steer/runtime.aar'
        with zipfile.ZipFile(runtime) as z:payload={n:z.read(n) for n in z.namelist()}
        native_rows=[]
        with zipfile.ZipFile(project/'core/build/outputs/aar/core-release.aar') as z:
            for abi in ['arm64-v8a','x86_64']:
                name='jni/'+abi+'/libsteer_vpn.so';data=z.read(name);assert data!=payload[name];payload[name]=data;b.check_steer_elf(data,abi)
                target=delivery/'steer/native'/abi/'libsteer_vpn.so';target.write_bytes(data)
                native_rows.append({'abi':abi,'file':str(target.relative_to(delivery)),'sha256':b.sha(target),'bytes':len(data),'elf16KiB':True})
        b.zipwrite(runtime,payload);descriptor=json.loads((delivery/'receipt.json').read_text('utf-8'));module=descriptor['modules']['steer']
        module.update(versionCode=VERSION,moduleVersion=f'0.3.3-p0-compat.{VERSION}',sourceVersion='accepted-p1-p049-native-correction')
        row=next(x for x in module['artifacts'] if x['file']=='runtime.aar');row.update(bytes=runtime.stat().st_size,sha256=b.sha(runtime))
        b.zipwrite(delivery/'source.zip',{r['path']:(short/r['path']).read_bytes() for r in rows});module['sourceSha256']=b.sha(delivery/'source.zip')
        descriptor['sourceArchive'].update(bytes=(delivery/'source.zip').stat().st_size,sha256=b.sha(delivery/'source.zip'));descriptor.update(runtime_verified=False,published=False,nativeLibraries=native_rows,scope=SCOPE+'; no P2 Java/JNI/capability')
        b.save(delivery/'receipt.json',descriptor)
        if WIRE_FIXTURES:
            import build_connection49_steer_wire as wire
            wire.build(stage,short,POLICY_FIXTURE,NEW_PEER)
        result['phases']['steer']={'state':'COMPILED_NOT_EXECUTED','stage':str(delivery),'short':str(short),'receiptSha256':b.sha(delivery/'receipt.json')};result['state']='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
        for row in rows:assert b.sha(stage/'steer-source'/row['path'])==row['sha256']
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:
        b.save(stage/'android-native.json',result);live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['build']==owner:live['jobs']['build']=None;b.save(current,live)
    print(str(stage))
if __name__=='__main__':main()
