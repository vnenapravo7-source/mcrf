"""Freeze the WHOLE new P049 source package; no compilation/test/device work.

This is not the old per-fingerprint diagnostic producer. The current expanded
source gate and empty live job slots are mandatory even to prepare a candidate.
"""
from hashlib import sha256
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import subprocess
import os
import re
import uuid

ROOT=Path('D:/MCRF/Projects/MCRF')
BUILDS=Path('D:/MCRF/Builds/Android')
STEER11=BUILDS/'p049-steerdiag-d467d4f9097147b195cd066d3afd869a'
OPTIONAL6=BUILDS/'p049-followup-4f3f833bd3094a1891c5f35701fdb439'
N='work/steer-android-port/'


def digest(path):return sha256(path.read_bytes()).hexdigest()
def save(path,value):path.write_text(json.dumps(value,indent=2),encoding='utf-8')


def inherited(parent:Path,manifest:str,sub:str,destination:Path):
    rows=json.loads((parent/manifest).read_text('utf-8-sig'))
    base=(parent/sub).resolve(strict=True)
    for row in rows:
        p=(base/row['path']).resolve(strict=True)
        if not p.is_relative_to(base) or p.is_symlink() or digest(p)!=row['sha256']:
            raise ValueError('accepted parent source no longer exact')
        q=destination/row['path'];q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)


def manifest(root:Path):
    result=[]
    for path in sorted(root.rglob('*')):
        if path.is_symlink():raise ValueError('source snapshot contains link')
        if path.is_file():result.append({'path':path.relative_to(root).as_posix(),'sha256':digest(path)})
    return result


def main():
    from connection49_source_gate import require_complete_source
    from connection49_expanded_overlay import apply,source_copy,replace_once,change
    require_complete_source(ROOT,expanded_common=True)
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    if any(live['jobs'].values()):raise ValueError('resume current job; do not duplicate it')
    cleanup=BUILDS/'p049-phone-29ab72fdecd74d03a163fb5cfa7c0349/steer11-owned-cleanup.json'
    if json.loads(cleanup.read_text('utf-8'))['state']!='PASS':raise ValueError('previous owned device cleanup incomplete')
    stage=BUILDS/('p049-expanded-'+uuid.uuid4().hex);stage.mkdir()
    app=stage/'app-source';native=stage/'steer-source';optional=stage/'optional-source'
    inherited(STEER11,'delivery-inputs.json','delivery-source',app)
    inherited(STEER11,'steer-inputs.json','steer-source',native)
    inherited(OPTIONAL6,'optional-inputs.json','native',optional)
    proof=apply(ROOT,app,native)
    # All optional production sources stay at the accepted TLS6/Xray5 baseline.
    # Only the new source overlay and its scoped new fixtures are promoted.
    for rel in [
        'ports/android/optional-cores/prepare_overlay.py',
        'ports/android/optional-cores/patches/utls-alpn49.go.txt',
        'ports/android/optional-cores/patches/utls-handshake49.go.txt',
        'ports/android/optional-cores/patches/tls-hybrid-hrr49_test.go.txt',
        'ports/android/transports/tls-core/fingerprint_matrix49_test.go',
        'ports/android/transports/tls-core/reality_variations49_test.go',
        'ports/android/transports/tls-core/tls_variations49_test.go',
        'ports/android/transports/xray/fingerprint_inventory49_test.go',
        'ports/android/transports/steer-tls49/utls-hrr49.go.txt',
    ]:source_copy(ROOT,optional,rel,proof)
    # Source preparation only: generate the private OPTIONAL TLS-core overlay.
    # The ACTUAL primary Leadaxe lock is a different fork and stays unchanged.
    # before freezing its manifest. Later Go builds may not mutate these inputs.
    optional_root=optional/'ports/android/optional-cores'
    tls_overlay=optional_root/'build/tls-overlay49'
    tls_overlay.parent.mkdir(parents=True,exist_ok=True)
    upstream=ROOT/'work/toolchains/go-home/pkg/mod/github.com/throneproj/sing-box@v1.11.16-0.20261003011458-ebe0747b4182'
    python=Path('C:/Users/dieonfire/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe')
    with (stage/'optional-source-overlay.log').open('xb') as log:
        meta=ROOT/'work/toolchains/go-home/pkg/mod/github.com/metacubex/utls@v1.8.7'
        done=subprocess.run([str(python),str(optional_root/'prepare_overlay.py'),str(upstream),str(tls_overlay),str(meta)],
            cwd=optional_root,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    if done.returncode:raise ValueError('private optional source overlay preparation failed')
    tls_module=optional/'ports/android/transports/tls-core'
    mod=(tls_module/'go.mod').read_text('utf-8')
    old='replace github.com/sagernet/sing-box => github.com/throneproj/sing-box v1.11.16-0.20261003011458-ebe0747b4182'
    mod=replace_once(mod,old,'replace github.com/sagernet/sing-box => '+tls_overlay.as_posix())
    mod+='\nreplace github.com/metacubex/utls => '+(tls_overlay.parent/'metautls49').as_posix()+'\n'
    (tls_module/'tls49.mod').write_text(mod,encoding='utf-8')
    shutil.copyfile(tls_module/'go.sum',tls_module/'tls49.sum')
    gradle=app/'ports/android/app/build.gradle.kts'
    change(gradle,lambda s:replace_once(replace_once(s,'versionCode = 10024','versionCode = 10025'),
        'versionName = "0.1.0-preview.20261020.4"','versionName = "0.1.0-preview.20261020.5"'),
        proof,'private candidate identity only; no public prerelease')
    # Private Go source is kept separate from both live and old frozen caches.
    tls=stage/'tls-source';module=tls/'steer-tls49';assets=ROOT/'ports/android/transports/steer-tls49'
    module.mkdir(parents=True)
    for file in assets.iterdir():
        if file.is_file() and file.suffix in ('.go','.mod','.sum','.txt','.py','.c','.h'):
            shutil.copyfile(file,module/file.name)
    spec=importlib.util.spec_from_file_location('frozen_tls49_overlay',module/'prepare_overlay.py')
    overlay=importlib.util.module_from_spec(spec)
    previous_bytecode=sys.dont_write_bytecode;sys.dont_write_bytecode=True
    try:spec.loader.exec_module(overlay)
    finally:sys.dont_write_bytecode=previous_bytecode
    cache=ROOT/'work/toolchains/go-home/pkg/mod'
    overlay.verify_registry(cache/'github.com/throneproj/xray-core@v1.251015.1-0.20260926005000-e662c22ab109',module)
    clone=tls/'utls'
    overlay_proof=overlay.prepare(cache/'github.com/refraction-networking/utls@v1.8.3-0.20260301010127-aa6edf4b11af',clone,module)
    with (module/'go.mod').open('a',encoding='utf-8') as out:
        out.write('\nreplace github.com/refraction-networking/utls => '+clone.as_posix()+'\n')
    # Dependency/source resolution is completed BEFORE freezing. This does not
    # compile or execute tests, never edits canonical/modcache source, and may
    # only use already cached checksum-pinned modules. Subsequent builds readonly.
    go=ROOT/'work/toolchains/go1.26.8/go/bin/go.exe'
    env=os.environ.copy();env.update(GOWORK='off',GOPROXY='off',GOTOOLCHAIN='local',
        GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'),
        GOOS='windows',GOARCH='amd64',CGO_ENABLED='0',GOFLAGS='')
    with (stage/'tls-source-dependency-closure.log').open('xb') as log:
        done=subprocess.run([str(go),'mod','tidy'],cwd=module,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    if done.returncode:raise ValueError('pinned TLS dependency source closure incomplete')
    # Scoped upstream-buffer fixture tests run with this exact same pinned
    # dependency graph, not an implicit download/older indirect dependency set.
    test_mod=(module/'go.mod').read_text('utf-8').replace('module mcrf.local/steertls49','module github.com/refraction-networking/utls',1)
    test_mod,count=re.subn(r'(?m)^\s*github\.com/refraction-networking/utls v1\.8\.3-0\.20260301010127-aa6edf4b11af[^\n]*\n','',test_mod)
    if count!=1:raise ValueError('unexpected uTLS dependency source layout')
    test_mod=test_mod.replace('\nreplace github.com/refraction-networking/utls => '+clone.as_posix()+'\n','\n')
    (tls/'utls-tests.mod').write_text(test_mod,encoding='utf-8')
    shutil.copyfile(module/'go.sum',tls/'utls-tests.sum')
    # Freeze the exact legacy peer source separately; never read it from live
    # source during a later build, and never reuse a different diagnostic peer.
    peer=stage/'peer-source'
    inherited(STEER11,'peer-inputs.json','peer-source',peer)
    source_copy(ROOT,peer,'ports/android/transports/xray/reality_wire49_test.go',proof)
    for name in ('main.go','go.mod','go.sum'):
        source_copy(ROOT,peer,'ports/android/maintenance/p049-edge-peer/'+name,proof)
    compression=stage/'compression-source';compression.mkdir()
    lock=json.loads((ROOT/'ports/android/coordination/p0-49-compression-source-lock.json').read_text('utf-8'))
    for row in lock['archives']:
        source=Path(row['local']).resolve(strict=True)
        if source.is_symlink() or digest(source)!=row['sha256']:raise ValueError('pinned decoder source archive changed')
        shutil.copyfile(source,compression/source.name)
    # Source-exact archives already used by accepted Steer11. Preserve CMake's
    # original SHA256 checks; do not redownload through its failing TLS client.
    native_pins={'v5.9.4-stable.tar.gz':'7256bfc89b183a75183806c7debfa203443873b0b4a562e1b80d68e01b45ac57',
                 'ngtcp2-1.25.0.tar.xz':'2a34d2484ba17847a5d11965704e9dd0fac4c6d8efc75ffe1ec7de66d8c6b6fb'}
    prior=json.loads((STEER11/'verified-offline-archives.json').read_text('utf-8'))
    for name,pin in native_pins.items():
        row=next(r for r in prior['files'] if Path(r['file']).name==name and r['sha256']==pin)
        source=Path(row['file']).resolve(strict=True)
        if source.is_symlink() or digest(source)!=pin:raise ValueError('native source archive changed')
        if 'SHA256='+pin not in (native/'work/steer-android-port/android-vpn/core/src/main/cpp/CMakeLists.txt').read_text('utf-8'):
            raise ValueError('native CMake hash pin changed')
        shutil.copyfile(source,compression/name)
    recipes=stage/'recipes';recipes.mkdir()
    for name in ('prepare_connection49_expanded.py','connection49_source_gate.py',
        'connection49_expanded_overlay.py','connection49_common.py','connection49_aar_delta.py',
        'connection49_retained_tls.py',
        'verify_connection49_expanded_host.py','build_connection49_tls_shared.py',
        'build_connection49_expanded_native.py','build_connection49_expanded_wire.py',
        'run_connection49_expanded_host_wire.py','package_connection49_expanded.py',
        'run_connection49_expanded_device_wire.py',
        'run_connection49_expanded.py'):
        source=ROOT/'ports/android/maintenance'/name
        if not source.is_file():raise ValueError('whole common recipe closure missing: '+name)
        shutil.copyfile(source,recipes/name)
    folders=[('app',app),('steer',native),('optional',optional),('tls',tls),('peer',peer),('compression',compression),('recipes',recipes)]
    for name,path in folders:
        save(stage/(name+'-inputs.json'),manifest(path))
    save(stage/'p0-hunks.json',proof);save(stage/'utls-overlay.json',overlay_proof)
    save(stage/'preparation.json',{'state':'EXPANDED_SOURCE_FROZEN_NOT_COMPILED','sourceReady':True,
        'parents':{'mainAndSteer':str(STEER11),'optional':str(OPTIONAL6)},
        'manifestHashes':{n:digest(stage/(n+'-inputs.json')) for n,p in folders},
        'mainVersionCode':10025,'steerVersionCode':12,'tlsVersionCode':7,'xrayRetainedVersionCode':5,
        'p2Included':False,'compiled':False,'runtimeAccepted':False,'phoneTouched':False,'published':False})
    # The common caller explicitly claims its job AFTER this fresh immutable
    # receipt. No live stage pointer is overwritten by source preparation.
    return stage


if __name__=='__main__':print(main())
