"""Common expanded P049 package: main + Steer12 + TLS7, never publication."""
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from connection49_common import ROOT,JAVA,SDK,digest,save,guard,environment,execute
from connection49_expanded_overlay import replace_once

CERT='fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'

def run(stage:Path)->dict:
    prep=guard(stage);out=stage/'expanded-packaging.json'
    if out.exists():raise ValueError('inspect existing package phase; never overwrite/duplicate')
    native=json.loads((stage/'expanded-native.json').read_text('utf-8'))
    wire=json.loads((stage/'expanded-wire-build.json').read_text('utf-8'))
    host_wire=json.loads((stage/'expanded-host-wire.json').read_text('utf-8'))
    if native['state']!='COMPILED_EXPANDED_EXTERNAL_NOT_RUNTIME_ACCEPTED' or \
       wire['state']!='COMPILED_PRODUCTION_WIRE_FIXTURES_NOT_EXECUTED' or \
       host_wire['state']!='PASS_OWNED_HOST_REALITY_MATRIX_NOT_ANDROID_ACCEPTANCE':
        raise ValueError('common source-bound host/native/wire phases required')
    for name in ('runtime','adapter'):
        proof=json.loads((stage/('expanded-'+name+'-delta.json')).read_text('utf-8'))
        if not proof['allOtherPayloadExact'] or proof['p2Included'] is not False:raise ValueError('exact allowed AAR delta missing')
    primary=Path(native['phases']['steer']['stage']);optional=Path(native['phases']['optional']['stage'])
    for name,path in [('steer',primary),('optional',optional)]:
        if digest(path/'receipt.json')!=native['phases'][name]['receiptSha256']:raise ValueError('external receipt changed')
    # Packaging-only copy, never change immutable app-source after its tests.
    delivery=stage/'delivery-source';delivery.mkdir(exist_ok=False)
    rows=json.loads((stage/'app-inputs.json').read_text('utf-8'))
    for row in rows:
        target=delivery/row['path'];target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(stage/'app-source'/row['path'],target)
        if digest(target)!=row['sha256']:raise ValueError('delivery source copy differs')
    gradle=delivery/'ports/android/engines/build.gradle.kts'
    text=gradle.read_text('utf-8');marker='val reviewedFatSteerRuntimePins=setOf('
    # Pin added ONLY after both precise delta proofs. Keep duplicate-class,
    # signer, module descriptor and all other packaging checks enabled.
    gradle.write_text(replace_once(text,marker,marker+'\n    // P049 exact TLS/cancel/licenses delta; all other payload proven identical.\n    "'+
        digest(primary/'steer/runtime.aar')+'",'),encoding='utf-8')
    manifests=[{'path':row['path'],'sha256':digest(delivery/row['path'])} for row in rows]
    save(stage/'delivery-inputs.json',manifests)
    result={'state':'PACKAGING','p2Included':False,'runtimeAccepted':False,'phoneTouched':False,
        'published':False,'xrayRetainedVersionCode':prep['xrayRetainedVersionCode'],
        'deliveryInputsSha256':digest(stage/'delivery-inputs.json'),'artifacts':[]};save(out,result)
    try:
        android=delivery/'ports/android';env=environment()
        result['compile']=execute(stage,'expanded-packaging',[
            JAVA/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar',
            'org.gradle.wrapper.GradleWrapperMain',':app:assembleRelease',
            ':engines:assembleSteerDebug',':engines:assembleTlscoreDebug',
            '-PtestRelease=true','-PprimaryCoreStage='+str(primary),'-PprimaryCoreNativeGate=true',
            '-PoptionalCoreStage='+str(optional),'-PoptionalCoreNativeGate=true',
            '--offline','--console=plain','--no-daemon','--max-workers=4'],android,env,3600)
        specs=[]
        for abi in ('universal','arm64-v8a','armeabi-v7a','x86_64'):
            specs.append(('org.mcrf.client',prep['mainVersionCode'],abi,
                android/'app/build/outputs/apk/release'/('app-'+abi+'-release.apk')))
        for flavor,version,abis in [('steer',prep['steerVersionCode'],['arm64-v8a','x86_64']),
                                  ('tlscore',prep['tlsVersionCode'],['universal','arm64-v8a','armeabi-v7a','x86_64'])]:
            for abi in abis:specs.append(('org.mcrf.engines.'+flavor,version,abi,
                android/'engines/build/outputs/apk'/flavor/'debug'/('engines-'+flavor+'-'+abi+'-debug.apk')))
        tools=SDK/'build-tools/36.0.0'
        for package,version,abi,file in specs:
            cert=subprocess.check_output([str(tools/'apksigner.bat'),'verify','--print-certs',str(file)],text=True,timeout=45)
            if 'certificate SHA-256 digest: '+CERT not in cert:raise ValueError('unexpected APK signer')
            identity=subprocess.check_output([str(tools/'aapt2.exe'),'dump','badging',str(file)],text=True,encoding='utf-8',timeout=45)
            if f"package: name='{package}' versionCode='{version}'" not in identity:raise ValueError('APK identity mismatch')
            with zipfile.ZipFile(file) as z:
                if len(z.namelist())!=len(set(z.namelist())):raise ValueError('APK duplicate entries')
                libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
                if package=='org.mcrf.client':
                    if libs:raise ValueError('native payload leaked into main APK')
                else:
                    abis={'arm64-v8a','armeabi-v7a','x86_64'} if abi=='universal' else {abi}
                    expected_libs={'libsteer_vpn.so','libmcrf_utls49.so'} if package.endswith('.steer') else {'libtls_core.so'}
                    if set(libs)!={'lib/'+a+'/'+library for a in abis for library in expected_libs}:raise ValueError('module APK native inventory')
                    for name in libs:
                        item_abi=Path(name).parts[1]
                        expected_lib=Path(name).name
                        expected=primary/'steer/native'/item_abi/expected_lib if package.endswith('.steer') else optional/'candidates/tls-core'/item_abi/expected_lib
                        if sha256(z.read(name)).hexdigest()!=digest(expected):raise ValueError('module native bytes changed during packaging')
            result['artifacts'].append({'package':package,'versionCode':version,'abi':abi,
                'path':str(file),'sha256':digest(file),'bytes':file.stat().st_size})
        for row in manifests:
            if digest(delivery/row['path'])!=row['sha256']:raise ValueError('packager changed source')
        guard(stage);result['state']='PASS_EXPANDED_SIGNED_R8_NOT_DEVICE_ACCEPTED'
        return result
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:save(out,result)
