"""Whole P049 native phase: exact new TLS layer + bounded Java delta; no phone."""
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

from connection49_common import ROOT,JAVA,NDK,GO,digest,save,guard,environment,execute,copy_inputs,elf16,zip_bytes
from connection49_aar_delta import entries,verify,verify_adapter,ASSETS,CLASS,ADAPTER_CLASS

def record(path):return {'file':path.name,'sha256':digest(path),'bytes':path.stat().st_size}

def run(stage:Path,previous:Path|None=None,compiled:Path|None=None)->dict:
    prep=guard(stage);out=stage/'expanded-native.json'
    if out.exists():raise ValueError('inspect/resume existing native phase; never duplicate')
    host=json.loads((stage/'expanded-host.json').read_text('utf-8'))
    if host['state']!='PASS_EXPANDED_AFFECTED_HOST_NOT_NATIVE_OR_DEVICE_ACCEPTANCE':
        raise ValueError('whole affected host phase must pass first')
    result={'state':'BUILDING','p2Included':False,'runtimeAccepted':False,'phoneTouched':False,'phases':{}}
    save(out,result);env=environment()
    try:
        from build_connection49_tls_shared import build
        archive=build(stage,previous)
        result['phases']['tlsLibrary']={'path':str(archive),'sha256':digest(archive)};save(out,result)
        if compiled is not None:
            compiled=compiled.resolve(strict=True)
            if compiled.parent!=stage.parent or not compiled.name.startswith('p049-expanded-'):
                raise ValueError('exact prior common native compilation required')
            old=json.loads((compiled/'expanded-native.json').read_text('utf-8'))
            old_prep=json.loads((compiled/'preparation.json').read_text('utf-8'))
            manifest=compiled/'steer-inputs.json'
            if digest(manifest)!=old_prep['manifestHashes']['steer'] or digest(manifest)!=digest(stage/'steer-inputs.json'):
                raise ValueError('native consumers changed; cannot retain compilation')
            short=Path(old['shortCheckout']).resolve(strict=True)
            if short.parent!=stage.parent or not short.name.startswith('p1s-'):
                raise ValueError('unexpected prior native checkout')
            for row in json.loads(manifest.read_text('utf-8')):
                for base in (compiled/'steer-source',short):
                    file=(base/row['path']).resolve(strict=True)
                    if not file.is_relative_to(base.resolve()) or digest(file)!=row['sha256']:
                        raise ValueError('prior compiled native source differs')
            prior=old['phases']['steerCompile'];log=Path(prior['log'])
            if digest(log)!=prior['logSha256'] or 'BUILD SUCCESSFUL' not in log.read_text('utf-8',errors='replace'):
                raise ValueError('prior native compilation did not succeed')
            old_tls=json.loads((compiled/'tls-library.json').read_text('utf-8'))
            if digest(compiled/'tls-library.json')!=old['phases']['tlsLibrary']['sha256']:
                raise ValueError('prior linked helper receipt changed')
            new_tls=json.loads(archive.read_text('utf-8'))
            if {a:r['sha256'] for a,r in old_tls['abis'].items()}!={a:r['sha256'] for a,r in new_tls['abis'].items()}:
                raise ValueError('prior linked TLS helper differs')
            result['phases']['steerCompile']=dict(prior,retainedFrom=str(compiled),newExecution=False)
        else:
            short=stage.parent/('p1s-'+uuid.uuid4().hex[:12]);copy_inputs(stage,'steer',short)
        result['shortCheckout']=str(short);save(out,result)
        project=short/'work/steer-android-port/android-vpn'
        gate=short/'ports/android/coordination/p1-progress-2026-10-17.json'
        # Accepted P1 gate is inherited input, NOT authority to compile P2.
        if compiled is None:result['phases']['steerCompile']=execute(stage,'expanded-steer-native',[
            JAVA/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar',
            'org.gradle.wrapper.GradleWrapperMain',':core:assembleRelease',':primary-adapter:assembleRelease',
            '-PsteerP1Candidate=true','-Pp1SourceReadyReceipt='+str(gate),'-Pp1SourceReadySha256='+digest(gate),
            '-Pp1NativeScratch='+str(short/'cxx'),'-Pp049TlsManifest='+str(archive),
            '-Pp049CodecArchives='+str(stage/'compression-source'),
            '--offline','--console=plain','--no-daemon','--max-workers=4'],project,env,3600)
        parent=Path(prep['parents']['mainAndSteer'])/'primary-stage'
        descriptor=json.loads((parent/'receipt.json').read_text('utf-8'))
        before=descriptor['modules']['steer']
        for row in before['artifacts']:
            if digest(parent/'steer'/row['file'])!=row['sha256']:raise ValueError('accepted module parent changed')
        delivery=stage/'primary-stage';shutil.copytree(parent,delivery)
        runtime=delivery/'steer/runtime.aar';adapter=delivery/'steer/adapter.aar'
        old_runtime=runtime.read_bytes();old_adapter=adapter.read_bytes()
        payload=entries(old_runtime);adapter_payload=entries(old_adapter)
        built=entries((project/'core/build/outputs/aar/core-release.aar').read_bytes())
        built_adapter=entries((project/'primary-adapter/build/outputs/aar/primary-adapter-release.aar').read_bytes())
        old_classes=entries(payload['classes.jar']);new_classes=entries(built['classes.jar'])
        if CLASS not in new_classes:raise ValueError('compiled TLS cancel class missing')
        old_classes[CLASS]=new_classes[CLASS];payload['classes.jar']=zip_bytes(old_classes)
        old_adapter_classes=entries(adapter_payload['classes.jar']);new_adapter_classes=entries(built_adapter['classes.jar'])
        # Only SteerEngine changes: no new capability, P2 class or method family.
        old_adapter_classes[ADAPTER_CLASS]=new_adapter_classes[ADAPTER_CLASS]
        adapter_payload['classes.jar']=zip_bytes(old_adapter_classes)
        jni_hashes={};assets={};native=[]
        for abi in ('arm64-v8a','x86_64'):
            name='jni/'+abi+'/libsteer_vpn.so';data=built[name];elf16(data,abi)
            payload[name]=data;jni_hashes[name]=sha256(data).hexdigest()
            binary=delivery/'steer/native'/abi/'libsteer_vpn.so';binary.parent.mkdir(parents=True,exist_ok=True)
            binary.write_bytes(data);native.append({'abi':abi,'file':binary.relative_to(delivery).as_posix(),
                'sha256':digest(binary),'bytes':len(data),'elf16KiB':True})
            helper_name='jni/'+abi+'/libmcrf_utls49.so'
            helper=json.loads(archive.read_text('utf-8'))['abis'][abi]
            helper_data=built[helper_name];elf16(helper_data,abi)
            # AGP deterministically strips even Go's already stripped ELF.
            # Prove the exact NDK transformation; never accept arbitrary drift.
            normalized=stage/'normalized-tls'/abi/'libmcrf_utls49.so'
            normalized.parent.mkdir(parents=True)
            original=Path(helper['path'])
            if digest(original)!=helper['sha256']:raise ValueError('linked TLS helper changed')
            subprocess.run([str(NDK/'llvm-strip.exe'),'--strip-unneeded','-o',str(normalized),str(original)],check=True)
            if normalized.read_bytes()!=helper_data:raise ValueError('AGP TLS bytes differ from exact pinned strip')
            helper_sha=sha256(helper_data).hexdigest()
            result.setdefault('tlsPackaging',{})[abi]={'linkedSha256':helper['sha256'],
                'packagedSha256':helper_sha,'transform':'pinned NDK llvm-strip --strip-unneeded'}
            payload[helper_name]=helper_data;jni_hashes[helper_name]=helper_sha
            helper_file=delivery/'steer/native'/abi/'libmcrf_utls49.so';helper_file.write_bytes(helper_data)
            native.append({'abi':abi,'file':helper_file.relative_to(delivery).as_posix(),
                'sha256':digest(helper_file),'bytes':len(helper_data),'elf16KiB':True})
        for name in ASSETS:
            source=short/'work/steer-android-port/android-vpn/core/src/main'/name
            if built[name]!=source.read_bytes():raise ValueError('compiled notice differs from source')
            payload[name]=built[name];assets[name]=digest(source)
            target=delivery/'steer/licenses'/Path(name).name;target.write_bytes(built[name])
        runtime.write_bytes(zip_bytes(payload));adapter.write_bytes(zip_bytes(adapter_payload))
        delta=verify(old_runtime,runtime.read_bytes(),assets,sha256(new_classes[CLASS]).hexdigest(),jni_hashes)
        adapter_delta=verify_adapter(old_adapter,adapter.read_bytes(),sha256(new_adapter_classes[ADAPTER_CLASS]).hexdigest())
        save(stage/'expanded-runtime-delta.json',delta);save(stage/'expanded-adapter-delta.json',adapter_delta)
        # Include the linked Go shim AND its exact private patched dependency,
        # not just the C callers, in the corresponding module source archive.
        source_files={}
        tls_evidence=json.loads(archive.read_text('utf-8'))
        tls_origin=Path(tls_evidence.get('retainedFrom',str(stage)))
        for origin,group,folder,prefix in [(stage,'steer','steer-source',''),(tls_origin,'tls','tls-source','p049-tls/')]:
            for row in json.loads((origin/(group+'-inputs.json')).read_text('utf-8')):
                if digest(origin/folder/row['path'])!=row['sha256']:raise ValueError('exact compiled source archive input differs')
                source_files[prefix+row['path']]=(origin/folder/row['path']).read_bytes()
        source_files['p049-tls/compiled-helper-receipt.json']=archive.read_bytes()
        (delivery/'source.zip').write_bytes(zip_bytes(source_files))
        module=descriptor['modules']['steer']
        module.update(versionCode=prep['steerVersionCode'],moduleVersion='0.3.3-p0-compat.'+str(prep['steerVersionCode']),
            sourceVersion='accepted-p1-p049-pinned-utls',sourceSha256=digest(delivery/'source.zip'))
        module['tls49']={'library':'libmcrf_utls49.so','abiSha256':{
            abi:jni_hashes['jni/'+abi+'/libmcrf_utls49.so'] for abi in ('arm64-v8a','x86_64')}}
        module['artifacts']=[record(runtime),record(adapter)]
        module['licenses']=[record(p) for p in sorted((delivery/'steer/licenses').iterdir()) if p.is_file()]
        descriptor.update(sourceArchive=record(delivery/'source.zip'),nativeLibraries=native,
            runtime_verified=False,published=False,nativeFixturesExecuted=False,
            scope='Expanded P049 same-Steer TLS+cancel API+three exact license assets; unfinished P2 excluded')
        # Old fixture rows are retained historical inputs, never new execution.
        descriptor['retainedParentReceiptSha256']=digest(parent/'receipt.json')
        save(delivery/'receipt.json',descriptor)
        result['phases']['steer']={'stage':str(delivery),'receiptSha256':digest(delivery/'receipt.json'),
            'runtimeDeltaSha256':digest(stage/'expanded-runtime-delta.json'),
            'adapterDeltaSha256':digest(stage/'expanded-adapter-delta.json')};save(out,result)
        result['phases']['optional']=build_optional(stage,prep,env,compiled);save(out,result)
        guard(stage);result['state']='COMPILED_EXPANDED_EXTERNAL_NOT_RUNTIME_ACCEPTED'
        return result
    except BaseException:
        result['state']='FAILED_PRESERVED';raise
    finally:save(out,result)

def build_optional(stage,prep,env,previous=None):
    if previous is not None:
        old=json.loads((previous/'expanded-native.json').read_text('utf-8'))
        common=json.loads((previous/'expanded-common.json').read_text('utf-8'))
        if old['state']=='COMPILED_EXPANDED_EXTERNAL_NOT_RUNTIME_ACCEPTED':
            if digest(previous/'expanded-native.json')!=common['phases']['native']['receiptSha256']:
                raise ValueError('completed optional native phase changed')
            from connection49_retained_tls import retain_optional_tls
            host=json.loads((previous/'expanded-host.json').read_text('utf-8'))
            proof=retain_optional_tls(stage,previous,'tlsCoreALPN',host['phases']['tlsCoreALPN']['mandatoryCases'])
            row=old['phases']['optional'];folder=Path(row['stage'])
            if digest(folder/'receipt.json')!=row['receiptSha256']:raise ValueError('retained optional receipt differs')
            for item in json.loads((folder/'receipt.json').read_text('utf-8'))['files']:
                binary=Path(item['file'])
                if digest(binary)!=item['sha256']:raise ValueError('retained optional binary differs')
                elf16(binary.read_bytes(),item['abi'])
            return dict(row,newExecution=False,sourceProof=proof,retainedFrom=str(previous))
    parent=Path(prep['parents']['optional'])
    prior=json.loads((parent/'android-native.json').read_text('utf-8'))['phases']['optional']
    inherited=Path(prior['stage'])
    if digest(inherited/'receipt.json')!=prior['receiptSha256']:raise ValueError('optional parent receipt changed')
    old=json.loads((inherited/'receipt.json').read_text('utf-8'))
    target=stage/'optional-stage';target.mkdir(exist_ok=False);files=[]
    # Retain every exact Xray5 ABI. This package changes only the TLS-core ELF.
    for row in old['files']:
        if row['kind']!='xray':continue
        source=Path(row['file'])
        if digest(source)!=row['sha256']:raise ValueError('Xray5 parent bytes changed')
        dest=target/'candidates/xray'/row['abi']/'libxray.so';dest.parent.mkdir(parents=True)
        shutil.copyfile(source,dest);files.append(dict(row,file=str(dest)))
    if {r['abi'] for r in files}!={'arm64-v8a','armeabi-v7a','x86_64'}:raise ValueError('Xray retained ABI set')
    module=stage/'optional-source/ports/android/transports/tls-core'
    fragment=stage/'optional-source/ports/android/optional-cores/build/tls-overlay49/common/dialer/fragment.go'
    env=dict(env);env.update(GOOS='android',CGO_ENABLED='1',PATH=str(NDK)+';'+env['PATH'])
    for abi,arch,triple in [('arm64-v8a','arm64','aarch64-linux-android29'),
                           ('armeabi-v7a','arm','armv7a-linux-androideabi29'),('x86_64','amd64','x86_64-linux-android29')]:
        env.update(GOARCH=arch,CC='clang --target='+triple)
        if arch=='arm':env['GOARM']='7'
        else:env.pop('GOARM',None)
        binary=target/'candidates/tls-core'/abi/'libtls_core.so';binary.parent.mkdir(parents=True)
        execute(stage,'expanded-tls-core-'+abi,[GO,'build','-mod=readonly','-modfile='+str(module/'tls49.mod'),
            '-tags=with_utls,with_purego','-trimpath','-buildmode=pie',
            '-ldflags=-s -w -checklinkname=0 -X main.fragmentPatch='+digest(fragment)+
            ' -linkmode=external -extldflags=-Wl,-z,max-page-size=16384','-o',binary,'.'],module,env)
        elf16(binary.read_bytes(),abi)
        files.append({'kind':'tls-core','abi':abi,'file':str(binary),'bytes':binary.stat().st_size,'sha256':digest(binary)})
    receipt=dict(old,files=files,stage='host-tested-candidates-only',androidDeviceTested=False,signed=False,
        packageVersionCode=prep['tlsVersionCode'],packageVersionName='0.1.0-p0-compat.'+str(prep['tlsVersionCode']),
        retainedXrayVersionCode=prep['xrayRetainedVersionCode'])
    save(target/'receipt.json',receipt)
    return {'stage':str(target),'receiptSha256':digest(target/'receipt.json'),'xrayRetainedExact':True}
