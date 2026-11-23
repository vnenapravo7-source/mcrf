"""Package only the frozen Steer8/TLS6 modules; main10024/Xray5 stay exact."""
import hashlib,json,os,shutil,subprocess,zipfile
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
STAGE=Path(json.loads((ROOT/'ports/android/coordination/p0-49-followup-stage.json').read_text('utf-8'))['stage'])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2),encoding='utf-8')
def main():
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT)
    output=STAGE/'packaging.json';assert not output.exists(),'Inspect existing result, never duplicate'
    native=json.loads((STAGE/'android-native.json').read_text('utf-8'))
    assert native['state']=='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
    assert json.loads((STAGE/'verification.json').read_text('utf-8-sig'))['state']=='PASS_COMMON_MODELS_HOST_NATIVE_ANDROID_COMPILE'
    prep=json.loads((STAGE/'preparation.json').read_text('utf-8'));parent=Path(prep['parent'])
    primary=Path(native['phases']['steer']['stage']);optional=Path(native['phases']['optional']['stage'])
    for kind,path in [('steer',primary),('optional',optional)]:assert sha(path/'receipt.json')==native['phases'][kind]['receiptSha256']
    source=STAGE/'delivery-source';assert not source.exists();source.mkdir()
    rows=json.loads((parent/'delivery-inputs.json').read_text('utf-8'))
    for row in rows:
        old=parent/'delivery-source'/row['path'];assert sha(old)==row['sha256']
        target=source/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(old,target)
    prior=parent/'primary-stage/steer/runtime.aar';current=primary/'steer/runtime.aar'
    with zipfile.ZipFile(prior) as a,zipfile.ZipFile(current) as b:
        assert set(a.namelist())==set(b.namelist())
        for name in a.namelist():
            if not name.startswith('jni/'):assert a.read(name)==b.read(name)
    gradle=source/'ports/android/engines/build.gradle.kts';text=gradle.read_text('utf-8')
    marker='val reviewedFatSteerRuntimePins=setOf(';assert text.count(marker)==1
    gradle.write_text(text.replace(marker,marker+'\n    // P049 followup: byte-exact inherited Java/resources; bounded native diagnostics.\n    "'+sha(current)+'",'),encoding='utf-8')
    save(STAGE/'fat-runtime-equivalence.json',{'acceptedParentSha256':sha(prior),'candidateSha256':sha(current),'allNonJniEntriesByteExact':True,'duplicateClassCheckStillEnabled':True})
    manifest=[{'path':r['path'],'sha256':sha(source/r['path'])} for r in rows];save(STAGE/'delivery-inputs.json',manifest)
    current_state=ROOT/'ports/android/coordination/current.json';live=json.loads(current_state.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    owner='P049_FOLLOWUP_PACKAGE:'+str(STAGE);live['jobs']['build']=owner;save(current_state,live)
    result={'state':'PACKAGING','mainUnchanged':10024,'xrayUnchanged':5,'p2Excluded':True,'published':False,'phoneTouched':False,'deliveryManifestSha256':sha(STAGE/'delivery-inputs.json'),'recipeSha256':sha(Path(__file__)),'artifacts':[]};save(output,result)
    env=os.environ.copy();env.update(JAVA_HOME='C:/Program Files/Java/jdk-17',ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk',GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'))
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
    try:
        android=source/'ports/android'
        flavors=prep.get('packageFlavors',['steer','tlscore']);assert flavors in [['steer'],['steer','tlscore']]
        tasks=[':engines:assemble'+('Steer' if f=='steer' else 'Tlscore')+'Debug' for f in flavors]
        cmd=[Path(env['JAVA_HOME'])/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',*tasks,'-PtestRelease=true','-PprimaryCoreStage='+str(primary),'-PprimaryCoreNativeGate=true','-PoptionalCoreStage='+str(optional),'-PoptionalCoreNativeGate=true','--offline','--console=plain','--max-workers=4']
        with (STAGE/'packaging.log').open('wb') as log:done=subprocess.run(list(map(str,cmd)),cwd=android,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=1800)
        assert done.returncode==0,'Module packaging failed, inspect retained log'
        tools=Path(env['ANDROID_HOME'])/'build-tools/36.0.0'
        for flavor,version,abis in [('steer',prep['steerVersionCode'],['arm64-v8a','x86_64']),('tlscore',prep['optionalVersionCode'],['universal','arm64-v8a','armeabi-v7a','x86_64'])]:
            if flavor not in flavors:continue
            for abi in abis:
                file=android/'engines/build/outputs/apk'/flavor/'debug'/f'engines-{flavor}-{abi}-debug.apk'
                cert=subprocess.check_output([str(tools/'apksigner.bat'),'verify','--print-certs',str(file)],text=True)
                assert 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0' in cert
                identity=subprocess.check_output([str(tools/'aapt2.exe'),'dump','badging',str(file)],text=True,encoding='utf-8')
                assert f"package: name='org.mcrf.engines.{flavor}' versionCode='{version}'" in identity
                with zipfile.ZipFile(file) as z:
                    libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')];assert libs
                    actual_abis=set()
                    for name in libs:
                        item_abi=Path(name).parts[1];actual_abis.add(item_abi)
                        expected=primary/'steer/native'/item_abi/'libsteer_vpn.so' if flavor=='steer' else optional/'candidates/tls-core'/item_abi/'libtls_core.so'
                        assert hashlib.sha256(z.read(name)).hexdigest()==sha(expected)
                    assert actual_abis==(set(abis)-{'universal'} if abi=='universal' else {abi})
                result['artifacts'].append({'package':'org.mcrf.engines.'+flavor,'versionCode':version,'abi':abi,'path':str(file),'bytes':file.stat().st_size,'sha256':sha(file)})
        for row in manifest:assert sha(source/row['path'])==row['sha256']
        result['state']='PASS_SIGNED_MODULES_ONLY_NOT_DEVICE_ACCEPTED'
    except BaseException:
        result['state']='FAILED_PRESERVED';raise
    finally:
        save(output,result);live=json.loads(current_state.read_text('utf-8-sig'))
        if live['jobs']['build']==owner:live['jobs']['build']=None;save(current_state,live)
    print(result['state'])
if __name__=='__main__':main()
