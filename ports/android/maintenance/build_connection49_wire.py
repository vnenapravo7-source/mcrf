"""Freeze and build one new owned Reality peer/client diagnostic, not P2."""
import hashlib,json,os,shutil,subprocess,sys,uuid
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path(json.loads((ROOT/'ports/android/coordination/p0-49-followup-stage.json').read_text('utf-8'))['stage'])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2),encoding='utf-8')
def main():
    native=json.loads((PARENT/'android-native.json').read_text('utf-8'));short=Path(native['phases']['steer']['short'])
    resume=sys.argv[1:]==['--resume-response-file'];assert not sys.argv[1:] or resume
    if resume:
        stage=Path(json.loads((ROOT/'ports/android/coordination/p0-49-wire-stage.json').read_text('utf-8'))['stage'])
        assert json.loads((stage/'build.json').read_text('utf-8'))['state']=='FAILED_PRESERVED'
        attempt=len(list(stage.glob('build-attempt*.json')))+1
        shutil.copyfile(stage/'build.json',stage/f'build-attempt{attempt}.json')
        shutil.copyfile(stage/'client-build.log',stage/f'client-build-attempt{attempt}.log')
    else:
        stage=Path('D:/MCRF/Builds/Android')/('p049-wire-'+uuid.uuid4().hex);stage.mkdir()
    for row in json.loads((PARENT/'optional-inputs.json').read_text('utf-8')):
        if '/transports/xray/' in row['path'] or '/optional-cores/bridge/' in row['path']:
            old=PARENT/'native'/row['path'];assert sha(old)==row['sha256'];new=stage/row['path'];new.parent.mkdir(parents=True,exist_ok=True)
            if resume:assert sha(new)==sha(old)
            else:shutil.copyfile(old,new)
    tests=[('ports/android/transports/xray/reality_wire49_test.go','ports/android/transports/xray/reality_wire49_test.go'),('work/steer-android-port/tests/owned-reality49.c','owned-reality49.c')]
    for src,dst in tests:
        if resume:assert sha(ROOT/src)==sha(stage/dst)
        else:shutil.copyfile(ROOT/src,stage/dst)
    build=next(p.parent for p in (short/'cxx').rglob('build.ninja') if p.parent.name=='arm64-v8a' and (p.parent/'compile_commands.json').exists())
    cc=json.loads((build/'compile_commands.json').read_text('utf-8'));row=next(r for r in cc if r['file'].endswith('vldial.c'))
    args=row['command'].split();flags=args[1:args.index('-o')];objects=sorted((build/'CMakeFiles/steer_vpn.dir').rglob('*.o'))
    objects=[p for p in objects if p.name!='bridge.c.o' and not p.as_posix().endswith('/daemon/main.c.o')];assert len(objects)>50
    save(stage/'inputs.json',{'parentNativeReceiptSha256':sha(PARENT/'android-native.json'),'syntheticSources':{b:sha(stage/b) for a,b in tests},'objects':[{'path':str(p),'sha256':sha(p)} for p in objects]})
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'))
    assert live['jobs']['device']=='P049:29ab72fdecd74d03a163fb5cfa7c0349' and not any(v for k,v in live['jobs'].items() if k!='device')
    owner='P049_OWNED_WIRE:'+str(stage);live['jobs']['hostTest']=owner;save(current,live)
    result={'state':'BUILDING_NEW_DIAGNOSTIC','p2Excluded':True,'productChanged':False}
    try:
        # Windows CreateProcess has a 32KiB command bound. LLVM response files
        # preserve exact object paths without a shell or mutable wildcard.
        link=[*flags,'-UNDEBUG','-fPIE',str(stage/'owned-reality49.c'),*map(str,objects),str(build/'libngtcp2_android.a'),'-Wl,--gc-sections','-Wl,-z,max-page-size=16384','-llog','-latomic','-lm','-o',str(stage/'owned-reality49')]
        (stage/'client.rsp').write_text('\n'.join('"'+x.replace('\\','/')+'"' for x in link),encoding='utf-8')
        with (stage/'client-build.log').open('wb') as log:
            done=subprocess.run([args[0],'@'+str(stage/'client.rsp')],cwd=build,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        assert done.returncode==0,'inspect client-build.log'
        env=os.environ.copy();env.update(GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'),GOOS='windows',GOARCH='amd64',CGO_ENABLED='0')
        with (stage/'peer-build.log').open('wb') as log:
            done=subprocess.run([str(ROOT/'work/toolchains/go1.26.8/go/bin/go.exe'),'test','-mod=readonly','-c','-ldflags=-checklinkname=0','-o',str(stage/'owned-peer.exe'),'.'],cwd=stage/'ports/android/transports/xray',env=env,stdout=log,stderr=subprocess.STDOUT,timeout=300)
        assert done.returncode==0,'inspect peer-build.log'
        result.update(state='COMPILED_NOT_EXECUTED',clientSha256=sha(stage/'owned-reality49'),peerSha256=sha(stage/'owned-peer.exe'))
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:
        save(stage/'build.json',result);save(ROOT/'ports/android/coordination/p0-49-wire-stage.json',{'stage':str(stage)})
        live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['hostTest']==owner:live['jobs']['hostTest']=None;save(current,live)
    print(str(stage))
if __name__=='__main__':main()
