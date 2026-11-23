"""Freeze the related native-only TLS system-DNS / Steer diagnostics followup."""
import hashlib,json,shutil,uuid
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path('D:/MCRF/Builds/Android/p049-compat-69de28f4809c44a6bffe137a8eaf1624')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2),encoding='utf-8')
def copy(p,q):q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q);assert sha(p)==sha(q)
def main():
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'));assert not any(live['jobs'].values())
    assert json.loads((PARENT/'packaging.json').read_text('utf-8'))['state']=='PASS_SIGNED_R8_MAIN_AND_EXTERNAL_MODULES_NOT_DEVICE_ACCEPTED'
    cleanup=Path('D:/MCRF/Builds/Android/p049-phone-29ab72fdecd74d03a163fb5cfa7c0349/compat-owned-cleanup10024.json')
    assert json.loads(cleanup.read_text('utf-8'))['state']=='PASS'
    stage=Path('D:/MCRF/Builds/Android')/('p049-followup-'+uuid.uuid4().hex);stage.mkdir()
    for name,sub in [('app','source'),('optional','native'),('steer','steer-source')]:
        for row in json.loads((PARENT/(name+'-inputs.json')).read_text('utf-8')):
            p=PARENT/sub/row['path'];assert sha(p)==row['sha256'];copy(p,stage/sub/row['path'])
    for row in json.loads((PARENT/'overlay-assets.json').read_text('utf-8')):copy(PARENT/'native'/row['path'],stage/'native'/row['path'])
    overlay='native/ports/android/optional-cores/build/tls-overlay'
    shutil.copytree(PARENT/overlay,stage/overlay)
    assert sha(stage/overlay/'common/dialer/fragment.go')=='5db03afcd6f3f0838fd6be5dbf0ea942e1a03be082fe34d49c8d3841d2162a4c'
    copy(PARENT/'tls-overlay.sum',stage/'tls-overlay.sum')
    text=(PARENT/'tls-overlay.mod').read_text('utf-8')
    assert str(PARENT/overlay).replace('\\','/') in text
    (stage/'tls-overlay.mod').write_text(text.replace(str(PARENT/overlay).replace('\\','/'),str(stage/overlay).replace('\\','/')),encoding='utf-8')
    tls='ports/android/transports/tls-core/'
    for name in ['main.go','system_server_dns.go','system_server_dns_test.go','server_dns_test.go']:copy(ROOT/tls/name,stage/'native'/tls/name)
    rel='work/steer-android-port/src/proto/vless/vldial.c';copy(ROOT/rel,stage/'steer-source'/rel)
    # Retain the exact passing Xray host executable; its source/bridge is unchanged.
    exe='native/ports/android/optional-cores/build/xray.exe';copy(PARENT/exe,stage/exe)
    manifests={}
    for name,sub in [('app','source'),('optional','native'),('steer','steer-source')]:
        base=stage/sub
        files=[p for p in base.rglob('*') if p.is_file() and not (sub=='native' and 'build' in p.relative_to(base).parts)]
        rows=[{'path':p.relative_to(base).as_posix(),'sha256':sha(p)} for p in sorted(files)]
        save(stage/(name+'-inputs.json'),rows);manifests[name]=sha(stage/(name+'-inputs.json'))
    save(stage/'overlay-assets.json',json.loads((PARENT/'overlay-assets.json').read_text('utf-8')))
    save(stage/'tls-overlay-inputs.json',[{'path':p.relative_to(stage/overlay).as_posix(),'sha256':sha(p)} for p in sorted((stage/overlay).rglob('*')) if p.is_file()])
    save(stage/'preparation.json',{'state':'NATIVE_SOURCE_FROZEN_NOT_VERIFIED','sourceReady':True,'parent':str(PARENT),'parentHostReceiptSha256':sha(PARENT/'verification.json'),'manifestHashes':manifests,'p2Excluded':True,'steerVersionCode':8,'optionalVersionCode':6,'mainUnchanged':10024,'xrayUnchanged':5,'runtimeAccepted':False,'retainedXrayHostSha256':sha(stage/exe)})
    save(ROOT/'ports/android/coordination/p0-49-followup-stage.json',{'stage':str(stage),'state':'NATIVE_SOURCE_FROZEN_NOT_VERIFIED'})
    print('P049_FOLLOWUP='+str(stage))
if __name__=='__main__':main()
