"""Affected record-boundary + owned authenticated Reality fixtures, same batch."""
import json,shutil,subprocess
from pathlib import Path
import build_connection49_android_native as b
def build(stage,short,policy='hello-record49',new_peer=False,legacy_peer=False):
    from connection49_source_gate import require_complete_source
    require_complete_source(b.ROOT)
    assert not (new_peer and legacy_peer)
    build=next(p.parent for p in (short/'cxx').rglob('build.ninja') if p.parent.name=='arm64-v8a' and (p.parent/'compile_commands.json').exists())
    cc=json.loads((build/'compile_commands.json').read_text('utf-8'));row=next(r for r in cc if r['file'].endswith('vldial.c'));args=row['command'].split();flags=args[1:args.index('-o')]
    objects=[p for p in sorted((build/'CMakeFiles/steer_vpn.dir').rglob('*.o')) if p.name!='bridge.c.o' and not p.as_posix().endswith('/daemon/main.c.o')]
    assert len(objects)>50
    codecs=[]
    if any('MCRF_CERT_COMPRESSION49' in flag for flag in flags):
        codecs=[build/'libmcrf_brotli_dec49.a',build/'libmcrf_zstd_dec49.a']
        assert all(file.is_file() for file in codecs)
    tls_archive=None
    if any('MCRF_UTLS49' in flag for flag in flags):
        receipt=json.loads((stage/'tls-carchive.json').read_text('utf-8'))
        entry=receipt['abis']['arm64-v8a'];tls_archive=Path(entry['path'])
        assert b.sha(tls_archive)==entry['sha256']
    for name in [policy,'owned-reality49']:
        src=short/'work/steer-android-port/tests'/(name+'.c');assert src.exists()
        link=[*flags,'-UNDEBUG','-fPIE',str(src)]
        if name in ['owned-reality49','edge-hello49','fingerprint-shape49','p25649','cert-compress49','link-options49']:
            link.extend([*map(str,objects)])
            if tls_archive:link.append(str(tls_archive))
            link.extend([*map(str,codecs),str(build/'libngtcp2_android.a'),'-lz','-llog','-latomic','-lm'])
        link.extend(['-Wl,--gc-sections','-Wl,-z,max-page-size=16384','-llog','-o',str(stage/name)])
        rsp=stage/(name+'.rsp');rsp.write_text('\n'.join('"'+x.replace('\\','/')+'"' for x in link),encoding='utf-8')
        b.call([args[0],'@'+str(rsp)],build,dict(__import__('os').environ),stage/(name+'-build.log'))
    old=Path('D:/MCRF/Builds/Android/p049-wire-f4d44030819d499889b24ec5eb902658');proof=json.loads((old/'build.json').read_text('utf-8'))
    assert b.sha(old/'owned-peer.exe')==proof['peerSha256']
    if legacy_peer:
        source=short/'ports/android/maintenance/p049-edge-peer'
        for name in ['main.go','go.mod','go.sum']:
            file=source/name;assert file.is_file() and not file.is_symlink()
            target=stage/'peer-source/ports/android/maintenance/p049-edge-peer'/name
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,target)
            assert b.sha(target)==b.sha(file)
        b.save(stage/'peer-inputs.json',[{'path':p.relative_to(stage/'peer-source').as_posix(),'sha256':b.sha(p)} for p in sorted((stage/'peer-source').rglob('*')) if p.is_file()])
        env=dict(__import__('os').environ);env.update(GOPATH=str(b.ROOT/'work/toolchains/go-home'),GOCACHE=str(b.ROOT/'work/toolchains/go-build-cache'),GOOS='windows',GOARCH='amd64',CGO_ENABLED='0')
        b.call([b.ROOT/'work/toolchains/go1.26.8/go/bin/go.exe','build','-mod=readonly','-trimpath','-o',stage/'owned-peer.exe','.'],stage/'peer-source/ports/android/maintenance/p049-edge-peer',env,stage/'peer-build.log')
    elif not new_peer:shutil.copyfile(old/'owned-peer.exe',stage/'owned-peer.exe')
    else:
        original=Path('D:/MCRF/Builds/Android/p049-compat-69de28f4809c44a6bffe137a8eaf1624')
        for row in json.loads((original/'optional-inputs.json').read_text('utf-8')):
            if '/transports/xray/' in row['path'] or '/optional-cores/bridge/' in row['path']:
                source=old/row['path'];assert b.sha(source)==row['sha256'];target=stage/'peer-source'/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
        rel='ports/android/transports/xray/reality_wire49_test.go';shutil.copyfile(short/rel,stage/'peer-source'/rel)
        b.save(stage/'peer-inputs.json',[{'path':p.relative_to(stage/'peer-source').as_posix(),'sha256':b.sha(p)} for p in sorted((stage/'peer-source').rglob('*')) if p.is_file()])
        env=dict(__import__('os').environ);env.update(GOPATH=str(b.ROOT/'work/toolchains/go-home'),GOCACHE=str(b.ROOT/'work/toolchains/go-build-cache'),GOOS='windows',GOARCH='amd64',CGO_ENABLED='0')
        b.call([b.ROOT/'work/toolchains/go1.26.8/go/bin/go.exe','test','-mod=readonly','-c','-ldflags=-checklinkname=0','-o',stage/'owned-peer.exe','.'],stage/'peer-source/ports/android/transports/xray',env,stage/'peer-build.log')
    result={'state':'COMPILED_NOT_EXECUTED','clientSha256':b.sha(stage/'owned-reality49'),'policyTestSha256':b.sha(stage/policy),'policyName':policy,'peerSha256':b.sha(stage/'owned-peer.exe'),'goPeerRetainedExact':not new_peer and not legacy_peer,'peerKind':'legacy-reality-main' if legacy_peer else 'pinned-xray-test','objectsSha256':[b.sha(p) for p in objects],'decoderLibrariesSha256':{file.name:b.sha(file) for file in codecs}}
    result['tlsLayer']='same-Steer-pinned-uTLS' if tls_archive else 'native-diagnostic'
    result['tlsArchiveSha256']=b.sha(tls_archive) if tls_archive else None
    if policy=='hello-record49':result['recordTestSha256']=result['policyTestSha256']
    b.save(stage/'wire-build.json',result)
