"""Continue the same P0 batch AFTER common host checks. No device/publication.
External native modules only; retain accepted Steer Java/assets, no P2 JNI.
"""
import hashlib,io,json,os,shutil,struct,subprocess,uuid,zipfile
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
STAGE=Path(json.loads((ROOT/'ports/android/coordination/p0-49-compatibility-stage.json').read_text('utf-8'))['stage'])
P1=Path('D:/MCRF/Builds/Android/p1-steer-candidate-f84fa510d8874bd7b89a09f4e1b06245')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2),encoding='utf-8')
def call(cmd,cwd,env,log):
    with log.open('wb') as stream:
        result=subprocess.run(list(map(str,cmd)),cwd=cwd,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=3600)
    if result.returncode:raise RuntimeError('Native phase failed; inspect '+str(log))
def zipwrite(path,files):
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(files.items()):
            item=zipfile.ZipInfo(name,(2026,10,20,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;z.writestr(item,data)
def check_steer_elf(data,abi):
    assert data[:6]==b'\x7fELF\x02\x01'
    kind,machine=struct.unpack_from('<HH',data,16)
    assert kind==3 and machine=={'arm64-v8a':183,'x86_64':62}[abi]
    start=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
    loads=0
    for i in range(count):
        ptype,flags,offset,vaddr,paddr,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,start+i*size)
        if ptype==1:assert align>=16384 and offset%align==vaddr%align;loads+=1
    assert loads>0
def main():
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT)
    result_file=STAGE/'android-native.json';assert not result_file.exists()
    host=json.loads((STAGE/'verification.json').read_text('utf-8-sig'))
    assert host['state']=='PASS_COMMON_MODELS_HOST_NATIVE_ANDROID_COMPILE'
    prep=json.loads((STAGE/'preparation.json').read_text('utf-8'))
    for name,base in [('app',STAGE/'source'),('optional',STAGE/'native'),('steer',STAGE/'steer-source')]:
        assert sha(STAGE/(name+'-inputs.json'))==prep['manifestHashes'][name]
        for row in json.loads((STAGE/(name+'-inputs.json')).read_text('utf-8')):assert sha(base/row['path'])==row['sha256']
    for row in json.loads((STAGE/'overlay-assets.json').read_text('utf-8')):assert sha(STAGE/'native'/row['path'])==row['sha256']
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    owner='P049_COMPAT_NATIVE:'+str(STAGE);live['jobs']['build']=owner;save(current,live)
    result={'state':'BUILDING','p2Excluded':True,'phoneTouched':False,'published':False,'phases':{}}
    save(result_file,result)
    env=os.environ.copy();env.update(JAVA_HOME='C:/Program Files/Java/jdk-17',ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk',GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'),GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'))
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
    go=ROOT/'work/toolchains/go1.26.8/go/bin/go.exe';native=STAGE/'native/ports/android'
    optional=native/'optional-cores/build'/('android-p049-'+uuid.uuid4().hex);optional.mkdir(parents=True)
    try:
        descriptors={};files=[]
        clang=Path(env['ANDROID_HOME'])/'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
        env['PATH']=str(clang)+os.pathsep+env['PATH'];env['GOOS']='android';env['CGO_ENABLED']='1'
        for kind in ['xray','tls-core']:
            descriptors[kind]=json.loads(subprocess.check_output([str(native/'optional-cores/build'/f'{kind}.exe'),'--mcrf-describe']))
            assert 'server-address-dns-v1' in descriptors[kind]['capabilities']
            extra=[];ld='-s -w -checklinkname=0'
            if kind=='tls-core':
                extra=['-modfile='+str(STAGE/'tls-overlay.mod'),'-tags=with_utls,with_purego']
                fragment=native/'optional-cores/build/tls-overlay/common/dialer/fragment.go'
                ld+=' -X main.fragmentPatch='+sha(fragment)
            for abi,arch,triple in [('arm64-v8a','arm64','aarch64-linux-android29'),('armeabi-v7a','arm','armv7a-linux-androideabi29'),('x86_64','amd64','x86_64-linux-android29')]:
                env['GOARCH']=arch;env['CC']='clang --target='+triple
                if arch=='arm':env['GOARM']='7'
                else:env.pop('GOARM',None)
                output=optional/'candidates'/kind/abi/('lib'+kind.replace('-','_')+'.so');output.parent.mkdir(parents=True)
                call([go,'build','-mod=readonly',*extra,'-trimpath','-buildmode=pie','-ldflags='+ld+' -linkmode=external -extldflags=-Wl,-z,max-page-size=16384','-o',output,'.'],native/'transports'/kind,env,STAGE/f'{kind}-{abi}.log')
                files.append({'kind':kind,'abi':abi,'file':str(output),'bytes':output.stat().st_size,'sha256':sha(output)})
        optional_code=prep.get('optionalVersionCode',5)
        save(optional/'receipt.json',{'api':1,'stage':'host-tested-candidates-only','descriptors':descriptors,'files':files,'androidDeviceTested':False,'signed':False,'packageVersionCode':optional_code,'packageVersionName':f'0.1.0-p0-compat.{optional_code}'})
        call(['C:/Users/dieonfire/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',native/'optional-cores/verify_candidates.py',optional/'receipt.json'],native,env,STAGE/'optional-elf.log')
        result['phases']['optional']={'state':'PASS_COMPILED_6_ELFS','stage':str(optional),'receiptSha256':sha(optional/'receipt.json')};save(result_file,result)
        # A fresh short checkout is required by the retained Android Gradle
        # recipe. Its P1 gate is retained INPUT, not this run's authority: the
        # P0 readiness/manifests and live owner above authorize this correction.
        short=Path('D:/MCRF/Builds/Android')/('p1s-'+uuid.uuid4().hex[:12]);short.mkdir()
        for row in json.loads((STAGE/'steer-inputs.json').read_text('utf-8')):
            target=short/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(STAGE/'steer-source'/row['path'],target);assert sha(target)==row['sha256']
        project=short/'work/steer-android-port/android-vpn'
        gate=short/'ports/android/coordination/p1-progress-2026-10-17.json'
        for name in ['GOOS','GOARCH','GOARM','CC','CGO_ENABLED']:env.pop(name,None)
        call([Path(env['JAVA_HOME'])/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':core:assembleRelease','-PsteerP1Candidate=true','-Pp1SourceReadyReceipt='+str(gate),'-Pp1SourceReadySha256='+sha(gate),'-Pp1NativeScratch='+str(short/'cxx'),'--offline','--console=plain','--no-daemon','--max-workers=4'],project,env,STAGE/'steer-native.log')
        delivery=STAGE/'primary-stage';shutil.copytree(P1/'primary-stage',delivery)
        runtime=delivery/'steer/runtime.aar'
        with zipfile.ZipFile(runtime) as z:payload={n:z.read(n) for n in z.namelist()}
        native_rows=[]
        with zipfile.ZipFile(project/'core/build/outputs/aar/core-release.aar') as z:
            for abi in ['arm64-v8a','x86_64']:
                name='jni/'+abi+'/libsteer_vpn.so';data=z.read(name);assert data!=payload[name];payload[name]=data
                check_steer_elf(data,abi)
                binary=delivery/'steer/native'/abi/'libsteer_vpn.so';binary.parent.mkdir(parents=True);binary.write_bytes(data)
                call([clang/'llvm-readelf.exe','--file-header','--program-headers',binary],delivery,env,STAGE/('steer-elf-'+abi+'.log'))
                native_rows.append({'abi':abi,'file':str(binary.relative_to(delivery)),'sha256':sha(binary),'bytes':len(data),'elf16KiB':True})
        zipwrite(runtime,payload)
        descriptor=json.loads((delivery/'receipt.json').read_text('utf-8'));module=descriptor['modules']['steer']
        steer_code=prep.get('steerVersionCode',7)
        module.update(versionCode=steer_code,moduleVersion=f'0.3.3-p0-compat.{steer_code}',sourceVersion='accepted-p1-plus-p049-stream-parser')
        row=next(x for x in module['artifacts'] if x['file']=='runtime.aar');row.update(bytes=runtime.stat().st_size,sha256=sha(runtime))
        source_files={row['path']:(short/row['path']).read_bytes() for row in json.loads((STAGE/'steer-inputs.json').read_text('utf-8'))}
        zipwrite(delivery/'source.zip',source_files)
        module['sourceSha256']=sha(delivery/'source.zip');descriptor['sourceArchive'].update(bytes=(delivery/'source.zip').stat().st_size,sha256=sha(delivery/'source.zip'))
        descriptor.update(runtime_verified=False,published=False,nativeLibraries=native_rows,scope='P049 external parser correction only; no P2 Java/JNI/capability additions')
        save(delivery/'receipt.json',descriptor)
        # Standalone parser fixture: actual ARM64 execution is a later phone gate.
        croot=short/'work/steer-android-port';fixture=STAGE/'vless-response-stream-arm64'
        call([clang/'clang.exe','--target=aarch64-linux-android29','-fPIE','-pie','-UNDEBUG','-I'+str(croot/'src/proto/vless'),croot/'tests/vless-response-stream.c',croot/'src/proto/vless/vless_proto.c','-o',fixture],croot,env,STAGE/'stream-fixture-build.log')
        result['phases']['steer']={'state':'COMPILED_NOT_EXECUTED','short':str(short),'stage':str(delivery),'receiptSha256':sha(delivery/'receipt.json'),'fixtureSha256':sha(fixture)}
        result['state']='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
    except BaseException:
        result['state']='FAILED_PRESERVED';raise
    finally:
        save(result_file,result)
        live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['build']==owner:live['jobs']['build']=None;save(current,live)
    print('P049_EXTERNAL_ANDROID='+result['state'])
if __name__=='__main__':main()
