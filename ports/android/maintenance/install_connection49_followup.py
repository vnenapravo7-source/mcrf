"""Fresh-guard install ONLY Steer8/TLS6; preserve main10024/Xray5 and all prefs."""
import json,time
from pathlib import Path
import p049_device as d
from install_compatibility49 import open_main
STAGE=Path(json.loads((d.ROOT/'ports/android/coordination/p0-49-followup-stage.json').read_text('utf-8'))['stage'])
LABEL='followup';ORIGINAL='compat-after-owned-cleanup';KINDS=['steer','tlscore']
RESUME_HELPER_DIALOG=False
def main():
    assert LABEL in ['followup','steer9','steer10','steer11'] and KINDS in [['steer','tlscore'],['steer']]
    output=d.STAGE/(LABEL+'-install.json');assert not output.exists()
    package=json.loads((STAGE/'packaging.json').read_text('utf-8'))
    assert package['state']=='PASS_SIGNED_MODULES_ONLY_NOT_DEVICE_ACCEPTED'
    targets=[next(r for r in package['artifacts'] if r['package']=='org.mcrf.engines.'+kind and r['abi']=='arm64-v8a') for kind in KINDS]
    for r in targets:assert d.sha(Path(r['path']))==r['sha256']
    helper=json.loads((d.STAGE/'helper-native-cleanup/receipt.json').read_text('utf-8'));assert helper['state']=='PASS' and d.sha(Path(helper['apk']))==helper['sha256']
    current=d.ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'))
    if RESUME_HELPER_DIALOG:
        assert LABEL=='steer11' and live['jobs']['device']==d.JOB and not any(v for k,v in live['jobs'].items() if k!='device')
        assert not (d.STAGE/('before-'+LABEL+'-install.json')).exists()
        open_main();assert d.installed(d.HELPER)['sha256']==helper['sha256']
        state=d.state();assert state['originalPowerFollowup']=='0' and not state['ownsVpn']
    else:
        assert not any(live['jobs'].values());live['jobs']['device']=d.JOB;d.save(current,live)
        open_main();assert d.installed(d.HELPER) is None
        power=d.text('shell','settings','get','global','stay_on_while_plugged_in');assert power=='0'
        state=d.state();state.update(originalPowerFollowup=power,ownsVpn=False,state='FOLLOWUP_INSTALLING');d.save(d.STAGE/'device.json',state)
        d.adb('shell','svc','power','stayon','usb')
        assert 'Success' in d.text('install','--user','0','--no-streaming',helper['apk'])
    state['helperInstalled']=True;d.save(d.STAGE/'device.json',state)
    open_main();before=d.checkpoint('before-'+LABEL+'-install');open_main()
    assert before['versionCode']==10024 and not before['prefs']['wantedRunning']
    original=json.loads((d.STAGE/(ORIGINAL+'.json')).read_text('utf-8'))
    assert before['prefHashes']==original['prefHashes'] and before['files']==original['files'] and before['modules']==original['modules']
    main_before=d.installed('org.mcrf.client');oldtest=d.installed('org.mcrf.client.test')
    receipt={'state':'INSTALLING','targets':targets,'steps':[],'mainBefore':main_before,'packageReceiptSha256':d.sha(STAGE/'packaging.json')};d.save(output,receipt)
    for r in targets:
        d.ready(install=True)
        assert 'Success' in d.text('install','--user','0','--no-streaming','-r',r['path'],timeout=90)
        assert d.installed(r['package'])['sha256']==r['sha256']
        receipt['steps'].append(r['package']);d.save(output,receipt)
    open_main();after=d.checkpoint('after-'+LABEL+'-install')
    assert after['versionCode']==10024 and after['prefHashes']==before['prefHashes'] and after['files']==before['files']
    changed={k for k,v in before['modules'].items() if after['modules'].get(k)!=v};assert changed==set(KINDS)
    assert d.installed('org.mcrf.client')==main_before and d.installed('org.mcrf.client.test')==oldtest==state['originalTestApk']
    receipt.update(state='INSTALLED_ONLY_REQUESTED_MODULES_PROTECTED_UNCHANGED',mainUnchanged=True,xrayUnchanged=True,unchangedModules=14-len(KINDS));d.save(output,receipt)
    open_main();print(receipt['state'])
if __name__=='__main__':main()
