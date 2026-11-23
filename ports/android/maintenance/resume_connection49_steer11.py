"""Resume SAME frozen failed Steer11 build using exact hash-pinned local archives."""
import json,os,shutil,zipfile
from pathlib import Path
import build_connection49_android_native as b
ROOT=b.ROOT
STAGE=Path('D:/MCRF/Builds/Android/p049-steerdiag-d467d4f9097147b195cd066d3afd869a')
SHORT=Path('D:/MCRF/Builds/Android/p1s-975a298afc71')
def main():
    output=STAGE/'android-native.json';old=json.loads(output.read_text('utf-8'));assert old['state']=='FAILED_PRESERVED'
    assert 'status_string: "SSL connect error"' in (STAGE/'steer-native.log').read_text('utf-8')
    assert not (STAGE/'android-native-attempt1.json').exists();shutil.copyfile(output,STAGE/'android-native-attempt1.json')
    prep=json.loads((STAGE/'preparation.json').read_text('utf-8'));assert prep['sourceReady'] and prep['p2Excluded'] and prep['steerVersionCode']==11
    parent=Path(prep['parent']);rows=json.loads((STAGE/'steer-inputs.json').read_text('utf-8'))
    for row in rows:assert b.sha(STAGE/'steer-source'/row['path'])==row['sha256']==b.sha(SHORT/row['path'])
    # Use the exact archives that produced the previous passing two-ABI module.
    # CMake still checks its original URL_HASH; no TLS verification is disabled.
    cache=Path(json.loads((parent/'android-native.json').read_text('utf-8'))['phases']['steer']['short'])/'cxx'
    specs=[('wolfssl','v5.9.4-stable.tar.gz','7256bfc89b183a75183806c7debfa203443873b0b4a562e1b80d68e01b45ac57'),('ngtcp2','ngtcp2-1.25.0.tar.xz','2a34d2484ba17847a5d11965704e9dd0fac4c6d8efc75ffe1ec7de66d8c6b6fb')]
    cache_rows=[]
    cmake=(SHORT/'work/steer-android-port/android-vpn/core/src/main/cpp/CMakeLists.txt').read_text('utf-8')
    for kind,name,digest in specs:
        assert 'SHA256='+digest in cmake
        source=next(p for p in cache.rglob(name) if p.is_file() and b.sha(p)==digest)
        for abi in ['arm64-v8a','x86_64']:
            target=SHORT/'cxx/RelWithDebInfo/4y27582b'/abi/'_deps'/(kind+'-subbuild')/(kind+'-populate-prefix/src')/name
            assert target.resolve().is_relative_to(SHORT.resolve());target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(source,target);assert b.sha(target)==digest
            cache_rows.append({'file':str(target),'sha256':digest})
    b.save(STAGE/'verified-offline-archives.json',{'hashPinsUnchanged':True,'tlsVerificationUnchanged':True,'files':cache_rows})
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    owner='P049_STEER11_RESUME:'+str(STAGE);live['jobs']['build']=owner;b.save(current,live)
    result={'state':'BUILDING','p2Excluded':True,'phoneTouched':False,'published':False,'phases':{'optional':old['phases']['optional']}}
    try:
        env=os.environ.copy();env.update(JAVA_HOME='C:/Program Files/Java/jdk-17',ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk',GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'))
        env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
        project=SHORT/'work/steer-android-port/android-vpn';gate=SHORT/'ports/android/coordination/p1-progress-2026-10-17.json'
        b.call([Path(env['JAVA_HOME'])/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':core:assembleRelease','-PsteerP1Candidate=true','-Pp1SourceReadyReceipt='+str(gate),'-Pp1SourceReadySha256='+b.sha(gate),'-Pp1NativeScratch='+str(SHORT/'cxx'),'--offline','--console=plain','--no-daemon','--max-workers=4'],project,env,STAGE/'steer-native-resume.log')
        delivery=STAGE/'primary-stage';assert not delivery.exists();shutil.copytree(parent/'primary-stage',delivery);runtime=delivery/'steer/runtime.aar'
        with zipfile.ZipFile(runtime) as z:payload={n:z.read(n) for n in z.namelist()}
        native_rows=[]
        with zipfile.ZipFile(project/'core/build/outputs/aar/core-release.aar') as z:
            for abi in ['arm64-v8a','x86_64']:
                name='jni/'+abi+'/libsteer_vpn.so';data=z.read(name);assert data!=payload[name];payload[name]=data;b.check_steer_elf(data,abi)
                target=delivery/'steer/native'/abi/'libsteer_vpn.so';target.write_bytes(data);native_rows.append({'abi':abi,'file':str(target.relative_to(delivery)),'sha256':b.sha(target),'bytes':len(data),'elf16KiB':True})
        b.zipwrite(runtime,payload);descriptor=json.loads((delivery/'receipt.json').read_text('utf-8'));module=descriptor['modules']['steer']
        module.update(versionCode=11,moduleVersion='0.3.3-p0-compat.11',sourceVersion='accepted-p1-p049-empty-reality-sni')
        row=next(x for x in module['artifacts'] if x['file']=='runtime.aar');row.update(bytes=runtime.stat().st_size,sha256=b.sha(runtime))
        b.zipwrite(delivery/'source.zip',{r['path']:(SHORT/r['path']).read_bytes() for r in rows});module['sourceSha256']=b.sha(delivery/'source.zip')
        descriptor['sourceArchive'].update(bytes=(delivery/'source.zip').stat().st_size,sha256=b.sha(delivery/'source.zip'));descriptor.update(runtime_verified=False,published=False,nativeLibraries=native_rows,scope=prep['scope']+'; exact P1 JNI hunk only, P2 excluded');b.save(delivery/'receipt.json',descriptor)
        import build_connection49_steer_wire as wire
        wire.build(STAGE,SHORT,'node-bind49',True)
        result['phases']['steer']={'state':'COMPILED_NOT_EXECUTED','stage':str(delivery),'short':str(SHORT),'receiptSha256':b.sha(delivery/'receipt.json')};result['state']='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
        for row in rows:assert b.sha(STAGE/'steer-source'/row['path'])==row['sha256']
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:
        b.save(output,result);live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['build']==owner:live['jobs']['build']=None;b.save(current,live)
    print(result['state'])
if __name__=='__main__':main()
