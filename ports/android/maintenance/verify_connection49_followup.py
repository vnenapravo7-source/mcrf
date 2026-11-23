"""Common affected followup checks; retain byte-exact closed app/Xray/bridge."""
import hashlib,json,os,subprocess
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
STAGE=Path(json.loads((ROOT/'ports/android/coordination/p0-49-followup-stage.json').read_text('utf-8'))['stage'])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2),encoding='utf-8')
def run(cmd,cwd,env,log):
    with log.open('wb') as f:r=subprocess.run(list(map(str,cmd)),cwd=cwd,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=900)
    if r.returncode:raise RuntimeError('Affected followup failure: '+str(log))
def main():
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT)
    resultfile=STAGE/'verification.json';assert not resultfile.exists()
    prep=json.loads((STAGE/'preparation.json').read_text('utf-8'));assert prep['sourceReady'] and prep['p2Excluded']
    parent=Path(prep['parent']);assert sha(parent/'verification.json')==prep['parentHostReceiptSha256']
    for name,sub in [('app','source'),('optional','native'),('steer','steer-source')]:
        assert sha(STAGE/(name+'-inputs.json'))==prep['manifestHashes'][name]
        for row in json.loads((STAGE/(name+'-inputs.json')).read_text('utf-8')):assert sha(STAGE/sub/row['path'])==row['sha256']
    for row in json.loads((STAGE/'tls-overlay-inputs.json').read_text('utf-8')):assert sha(STAGE/'native/ports/android/optional-cores/build/tls-overlay'/row['path'])==row['sha256']
    # Explicitly prove the retained components, not just a passing old receipt.
    old=json.loads((parent/'optional-inputs.json').read_text('utf-8'))
    for row in old:
        if '/transports/xray/' in row['path'] or '/optional-cores/bridge/' in row['path']:
            assert sha(STAGE/'native'/row['path'])==row['sha256']
    assert (STAGE/'app-inputs.json').read_bytes()==(parent/'app-inputs.json').read_bytes()
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    owner='P049_FOLLOWUP_HOST:'+str(STAGE);live['jobs']['hostTest']=owner;save(current,live)
    result={'state':'RUNNING_AFFECTED_NATIVE_SYSTEM_DNS','p2Excluded':True,'phoneTouched':False,'phases':{'application':'RETAINED_64_AND_RELEASE_COMPILE_EXACT','xray':'RETAINED_SOURCE_EXACT','bridge':'RETAINED_SOURCE_EXACT'}}
    save(resultfile,result)
    try:
        go=ROOT/'work/toolchains/go1.26.8/go/bin/go.exe';env=os.environ.copy()
        env.update(GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'),GOOS='windows',GOARCH='amd64',CGO_ENABLED='0')
        native=STAGE/'native/ports/android';cwd=native/'transports/tls-core'
        extra=['-modfile='+str(STAGE/'tls-overlay.mod'),'-tags=with_utls,with_purego']
        fragment=sha(native/'optional-cores/build/tls-overlay/common/dialer/fragment.go')
        run([go,'build','-mod=readonly',*extra,'-trimpath','-ldflags=-s -w -checklinkname=0 -X main.fragmentPatch='+fragment,'-o',native/'optional-cores/build/tls-core.exe','.'],cwd,env,STAGE/'tls-host-build.log')
        run([go,'test','-mod=readonly',*extra,'-ldflags=-checklinkname=0','-count=1','-v','-run','^Test(SystemServerDNS|RealSystemServerDNS)', '.'],cwd,env,STAGE/'tls-system-tests.log')
        result['phases']['tls-system-dns']='PASS_NEW_FAMILY_FAILURE_CLOSE_AND_REAL_FRAGMENTED_TLS'
        result['state']='PASS_COMMON_MODELS_HOST_NATIVE_ANDROID_COMPILE'
        result['nativeRuntimeAccepted']=False
    except BaseException:
        result['state']='FAILED_PRESERVED';raise
    finally:
        save(resultfile,result)
        live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['hostTest']==owner:live['jobs']['hostTest']=None;save(current,live)
    print('P049_FOLLOWUP_HOST='+result['state'])
if __name__=='__main__':main()
