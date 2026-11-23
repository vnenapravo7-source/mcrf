"""Only a phase of the NEW expanded common P049 batch, never a diagnostic build.

Supported Android c-shared only: never fake Linux GOOS or patch the toolchain.
Inputs are frozen. Both libraries stay inside the same external Steer module.
"""
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess

ROOT=Path('D:/MCRF/Projects/MCRF')
BUILD_ROOT=Path('D:/MCRF/Builds/Android')
GO=ROOT/'work/toolchains/go1.26.8/go/bin/go.exe'
NDK=Path('C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin')


def digest(path):return sha256(path.read_bytes()).hexdigest()


def build(stage:Path,previous:Path|None=None)->Path:
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT,expanded_common=True)
    stage=stage.resolve(strict=True)
    if stage.parent!=BUILD_ROOT.resolve(strict=True) or not stage.name.startswith('p049-expanded-'):
        raise ValueError('exact expanded common stage required')
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    if live['jobs']['build']!='P049_EXPANDED_COMMON:'+str(stage):
        raise ValueError('common producer does not own live build slot')
    inputs=stage/'tls-inputs.json';receipt=stage/'tls-library.json'
    if receipt.exists():raise ValueError('do not duplicate completed archive phase')
    rows=json.loads(inputs.read_text('utf-8'))
    source=stage/'tls-source'
    for row in rows:
        p=(source/row['path']).resolve(strict=True)
        if not p.is_relative_to(source.resolve()) or p.is_symlink() or digest(p)!=row['sha256']:
            raise ValueError('frozen TLS source differs')
    module=source/'steer-tls49'
    # The freezer MUST have installed the hash-pinned PRIVATE local replacement.
    if 'replace github.com/refraction-networking/utls =>' not in (module/'go.mod').read_text('utf-8'):
        raise ValueError('private bounded uTLS source replacement missing')
    if previous is not None and (previous/'tls-library.json').is_file():
        from connection49_retained_tls import retain
        old_path=previous/'tls-library.json';old=json.loads(old_path.read_text('utf-8'))
        native=json.loads((previous/'expanded-native.json').read_text('utf-8'))
        if digest(old_path)!=native['phases']['tlsLibrary']['sha256'] or old['state']!='COMPILED_ANDROID_SHARED_NOT_RUNTIME_ACCEPTED':
            raise ValueError('prior TLS build phase not closed')
        if old['inputsSha256']!=digest(previous/'tls-inputs.json'):raise ValueError('prior TLS inputs changed')
        host=json.loads((previous/'expanded-host.json').read_text('utf-8'))
        proof=retain(stage,previous,'steerTls',host['phases']['steerTls']['mandatoryCases'])
        from connection49_common import elf16
        if set(old['abis'])!={'arm64-v8a','x86_64'}:raise ValueError('prior TLS ABI set differs')
        for abi,row in old['abis'].items():
            binary=Path(row['path'])
            if digest(binary)!=row['sha256']:raise ValueError('prior TLS binary changed')
            elf16(binary.read_bytes(),abi)
        old.update(retainedFrom=str(previous),retainedReceiptSha256=digest(old_path),
            equivalentCurrentInputsSha256=digest(inputs),sourceProof=proof,newExecution=False)
        receipt.write_text(json.dumps(old,indent=2),encoding='utf-8');return receipt
    output=stage/'tls-libraries';output.mkdir(exist_ok=False)
    env=os.environ.copy()
    temporary=output/'gotmp';temporary.mkdir()
    env.update(GOWORK='off',GOOS='android',CGO_ENABLED='1',GOPROXY='off',GOTOOLCHAIN='local',
               GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'),
               GOFLAGS='',GOTMPDIR=str(temporary),PATH=str(NDK)+os.pathsep+env['PATH'])
    result={'state':'COMPILED_ANDROID_SHARED_NOT_RUNTIME_ACCEPTED','inputsSha256':digest(inputs),
            'mainNativeChanged':False,'engineSubstitution':False,'abis':{}}
    for abi,arch,triple in [('arm64-v8a','arm64','aarch64-linux-android26'),('x86_64','amd64','x86_64-linux-android26')]:
        if not (NDK/'clang.exe').is_file():raise ValueError('pinned NDK clang missing')
        env.update(GOARCH=arch,CC='clang --target='+triple)
        library=output/abi/'libmcrf_utls49.so';library.parent.mkdir()
        with (stage/('tls-shared-'+abi+'.log')).open('xb') as log:
            done=subprocess.run([str(GO),'build','-mod=readonly','-trimpath','-buildvcs=false',
                '-buildmode=c-shared',
                '-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-soname,libmcrf_utls49.so',
                '-o',str(library),'.'],cwd=module,env=env,
                stdout=log,stderr=subprocess.STDOUT,timeout=1800)
        if done.returncode:raise RuntimeError('common Android TLS helper compile failed: '+abi)
        from connection49_common import elf16
        elf16(library.read_bytes(),abi)
        dynamic=subprocess.check_output([str(NDK/'llvm-readelf.exe'),'-d',str(library)],text=True,timeout=20)
        if '(SONAME)' not in dynamic or '[libmcrf_utls49.so]' not in dynamic:
            raise ValueError('TLS helper SONAME differs')
        result['abis'][abi]={'path':str(library).replace('\\','/'),'sha256':digest(library),'bytes':library.stat().st_size}
    # Source immutability after BOTH architectures is mandatory, even when Go
    # itself reported success. No automatic tidy or "fix the cache" fallback.
    for row in rows:
        if digest(source/row['path'])!=row['sha256']:raise ValueError('compiler changed frozen input')
    receipt.write_text(json.dumps(result,indent=2),encoding='utf-8')
    return receipt
