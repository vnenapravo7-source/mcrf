"""Common10024/main + exact external-module packaging, no install/publication."""
import hashlib,json,os,re,shutil,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
STAGE=Path(json.loads((ROOT/'ports/android/coordination/p0-49-compatibility-stage.json').read_text('utf-8'))['stage'])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2),encoding='utf-8')
def main():
    output=STAGE/'packaging.json';resume=sys.argv[1:]==['--resume-fat-runtime']
    assert not sys.argv[1:] or resume
    if resume:
        previous=json.loads(output.read_text('utf-8'));assert previous['state']=='FAILED_PRESERVED'
        assert 'Duplicate class org.jetbrains.annotations.' in (STAGE/'packaging.log').read_text('utf-8')
        assert not (STAGE/'packaging-attempt1.json').exists()
        shutil.copyfile(output,STAGE/'packaging-attempt1.json')
    else:assert not output.exists()
    native=json.loads((STAGE/'android-native.json').read_text('utf-8'))
    assert native['state']=='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
    host=json.loads((STAGE/'verification.json').read_text('utf-8-sig'))
    assert host['state']=='PASS_COMMON_MODELS_HOST_NATIVE_ANDROID_COMPILE'
    source=STAGE/'delivery-source'
    if not resume:assert not source.exists();source.mkdir()
    rows=json.loads((STAGE/'app-inputs.json').read_text('utf-8'))
    for row in rows:
        old=STAGE/'source'/row['path'];assert sha(old)==row['sha256']
        if not resume:
            target=source/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(old,target)
    # One isolated packaging hunk only. Never copy canonical engines Gradle,
    # which also contains unrelated, unfinished P2 process-group changes.
    file=source/'ports/android/engines/build.gradle.kts';text=file.read_text('utf-8')
    old='val packageStage=when(kind){"csqtt"->csqttLegacyStage;"csqtt25"->csqtt25Stage;else->swgStages[kind]}'
    new='val packageStage=when(kind){"csqtt"->csqttLegacyStage;"csqtt25"->csqtt25Stage;in optionalKinds->optionalStage;else->swgStages[kind]}'
    if not resume:
        assert text.count(old)==1;file.write_text(text.replace(old,new),encoding='utf-8')
    else:
        before=json.loads((STAGE/'delivery-inputs.json').read_text('utf-8'))
        for row in before:assert sha(source/row['path'])==row['sha256']
        # The changed AAR contains the SAME accepted fat Java runtime; only JNI
        # bytes differ. Prove that boundary before adding its exact SHA pin.
        prior=Path('D:/MCRF/Builds/Android/p1-steer-candidate-f84fa510d8874bd7b89a09f4e1b06245/primary-stage/steer/runtime.aar')
        current=STAGE/'primary-stage/steer/runtime.aar'
        with zipfile.ZipFile(prior) as a,zipfile.ZipFile(current) as b:
            assert set(a.namelist())==set(b.namelist())
            for name in a.namelist():
                if not name.startswith('jni/'):assert a.read(name)==b.read(name)
        marker='val reviewedFatSteerRuntimePins=setOf('
        assert text.count(marker)==1
        file.write_text(text.replace(marker,marker+'\n    // P049: exact inherited P1 classes/resources, parser-only native correction.\n    "'+sha(current)+'",'),encoding='utf-8')
        save(STAGE/'fat-runtime-equivalence.json',{'acceptedParentSha256':sha(prior),'candidateSha256':sha(current),'allNonJniEntriesByteExact':True,'duplicateClassCheckStillEnabled':True})
    manifest=[{'path':r['path'],'sha256':sha(source/r['path'])} for r in rows];save(STAGE/'delivery-inputs.json',manifest)
    primary=Path(native['phases']['steer']['stage']);optional=Path(native['phases']['optional']['stage'])
    assert sha(primary/'receipt.json')==native['phases']['steer']['receiptSha256']
    assert sha(optional/'receipt.json')==native['phases']['optional']['receiptSha256']
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    owner='P049_COMPAT_PACKAGE:'+str(STAGE);live['jobs']['build']=owner;save(current,live)
    result={'state':'PACKAGING','p2Excluded':True,'phoneTouched':False,'published':False,'versionCode':10024,'deliveryManifestSha256':sha(STAGE/'delivery-inputs.json'),'executedRecipeSha256':sha(Path(__file__)),'artifacts':[]}
    save(output,result)
    env=os.environ.copy();env.update(JAVA_HOME='C:/Program Files/Java/jdk-17',ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk',GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'))
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
    try:
        android=source/'ports/android'
        cmd=[Path(env['JAVA_HOME'])/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':app:assembleRelease',':engines:assembleSteerDebug',':engines:assembleXrayDebug',':engines:assembleTlscoreDebug','-PtestRelease=true','-PprimaryCoreStage='+str(primary),'-PprimaryCoreNativeGate=true','-PoptionalCoreStage='+str(optional),'-PoptionalCoreNativeGate=true','--offline','--console=plain','--max-workers=4']
        with (STAGE/('packaging-attempt2.log' if resume else 'packaging.log')).open('wb') as log:
            done=subprocess.run(list(map(str,cmd)),cwd=android,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=3600)
        if done.returncode:raise RuntimeError('Common packaging failed; retain log')
        sdk=Path(env['ANDROID_HOME']);tools=sdk/'build-tools/36.0.0'
        artifact_specs=[]
        for abi in ['universal','arm64-v8a','armeabi-v7a','x86_64']:
            artifact_specs.append(('org.mcrf.client',10024,abi,android/'app/build/outputs/apk/release'/f'app-{abi}-release.apk'))
        for flavor,version,abis in [('steer',7,['arm64-v8a','x86_64']),('xray',5,['universal','arm64-v8a','armeabi-v7a','x86_64']),('tlscore',5,['universal','arm64-v8a','armeabi-v7a','x86_64'])]:
            for abi in abis:artifact_specs.append(('org.mcrf.engines.'+flavor,version,abi,android/'engines/build/outputs/apk'/flavor/'debug'/f'engines-{flavor}-{abi}-debug.apk'))
        for package,version,abi,file in artifact_specs:
            assert file.is_file()
            cert=subprocess.check_output([str(tools/'apksigner.bat'),'verify','--print-certs',str(file)],text=True)
            assert 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0' in cert
            identity=subprocess.check_output([str(tools/'aapt2.exe'),'dump','badging',str(file)],text=True,encoding='utf-8')
            assert f"package: name='{package}' versionCode='{version}'" in identity
            with zipfile.ZipFile(file) as z:
                libraries=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
                if package=='org.mcrf.client':assert not libraries
                for name in libraries:
                    item_abi=Path(name).parts[1]
                    flavor=package.rsplit('.',1)[1]
                    if flavor=='steer':expected=primary/'steer/native'/item_abi/'libsteer_vpn.so'
                    else:expected=optional/'candidates'/('tls-core' if flavor=='tlscore' else 'xray')/item_abi/('libtls_core.so' if flavor=='tlscore' else 'libxray.so')
                    assert hashlib.sha256(z.read(name)).hexdigest()==sha(expected)
            result['artifacts'].append({'package':package,'versionCode':version,'abi':abi,'path':str(file),'bytes':file.stat().st_size,'sha256':sha(file)})
        for row in manifest:assert sha(source/row['path'])==row['sha256']
        result['state']='PASS_SIGNED_R8_MAIN_AND_EXTERNAL_MODULES_NOT_DEVICE_ACCEPTED'
    except BaseException:
        result['state']='FAILED_PRESERVED';raise
    finally:
        save(output,result)
        live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['build']==owner:live['jobs']['build']=None;save(current,live)
    print('P049_COMPAT_PACKAGE='+result['state'])
if __name__=='__main__':main()
