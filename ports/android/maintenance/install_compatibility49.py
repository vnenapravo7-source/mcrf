"""Install ONLY the exact common P049 compatibility app and three modules.
Fresh ordinary awake/unlocked/idle guards, no user VPN/service stop.
"""
import json,time,sys
from pathlib import Path
import p049_device as d
STAGE=Path(json.loads((d.ROOT/'ports/android/coordination/p0-49-compatibility-stage.json').read_text('utf-8'))['stage'])
def open_main():
    focus='\n'.join(x for x in d.text('shell','dumpsys','window').splitlines() if 'mCurrentFocus=' in x)
    assert any(x in focus for x in ['org.mcrf.client/','com.android.chrome/','com.sec.android.app.launcher/']),'Inspect current foreground before navigation'
    d.adb('shell','am','start','-n','org.mcrf.client/.MainActivity');time.sleep(.7);d.ready(install=True)
def main():
    assert not (d.STAGE/'compatibility-install.json').exists(),'Resume previous install, never duplicate'
    packaging=json.loads((STAGE/'packaging.json').read_text('utf-8'))
    assert packaging['state']=='PASS_SIGNED_R8_MAIN_AND_EXTERNAL_MODULES_NOT_DEVICE_ACCEPTED'
    targets=[next(row for row in packaging['artifacts'] if row['package']==package and row['abi']==abi) for package,abi in [('org.mcrf.client','universal'),('org.mcrf.engines.steer','arm64-v8a'),('org.mcrf.engines.xray','arm64-v8a'),('org.mcrf.engines.tlscore','arm64-v8a')]]
    for row in targets:assert d.sha(Path(row['path']))==row['sha256']
    helper=json.loads((d.STAGE/'helper-native-cleanup/receipt.json').read_text('utf-8'))
    assert helper['state']=='PASS' and d.sha(Path(helper['apk']))==helper['sha256']
    live=json.loads((d.ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    if sys.argv[1:]==['--resume-guard']:
        assert live['jobs']['device']==d.JOB and not any(v for k,v in live['jobs'].items() if k!='device')
        assert not (d.STAGE/'before-install10024.json').exists() and d.installed(d.HELPER) is None
    else:
        assert not sys.argv[1:] and not any(live['jobs'].values())
    live['jobs']['device']=d.JOB;d.save(d.ROOT/'ports/android/coordination/current.json',live)
    # ready() enforces current lease/user0/grant and no foreground/active VPN.
    # Self wake/unlock, if needed, is an ordinary separate human-authorized step.
    open_main()
    assert d.installed(d.HELPER) is None
    power=d.text('shell','settings','get','global','stay_on_while_plugged_in');assert power=='null' or power.isdecimal()
    state=d.state();state.update(candidate=10024,originalPower10024=power,ownsVpn=False,state='COMPATIBILITY_INSTALLING')
    d.save(d.STAGE/'device.json',state)
    d.adb('shell','svc','power','stayon','usb')
    assert 'Success' in d.text('install','--user','0','--no-streaming',helper['apk'])
    state['helperInstalled']=True;d.save(d.STAGE/'device.json',state)
    open_main();before=d.checkpoint('before-install10024');open_main()
    assert before['versionCode']==10023 and not before['prefs']['wantedRunning']
    receipt={'state':'INSTALLING','packagingReceiptSha256':d.sha(STAGE/'packaging.json'),'artifacts':targets,'steps':[],'published':False}
    d.save(d.STAGE/'compatibility-install.json',receipt)
    for row in targets:
        d.ready(install=True)
        assert 'Success' in d.text('install','--user','0','--no-streaming','-r',row['path'],timeout=90)
        assert d.installed(row['package'])['sha256']==row['sha256']
        receipt['steps'].append(row['package']);d.save(d.STAGE/'compatibility-install.json',receipt)
        if row['package']=='org.mcrf.client':open_main()
    open_main();after=d.checkpoint('after-install10024')
    assert after['versionCode']==10024 and after['prefHashes']==before['prefHashes'] and after['files']==before['files']
    changed=[]
    for key,value in before['modules'].items():
        if after['modules'].get(key)!=value:changed.append(key)
    assert set(changed)=={'steer','xray','tlscore'},'Unexpected module change'
    assert d.installed('org.mcrf.client.test')==state['originalTestApk']
    receipt.update(state='INSTALLED_EXACT_PROTECTED_PREFS_FILES_UNCHANGED',updatedModules=changed,allOtherModulesUnchanged=True)
    d.save(d.STAGE/'compatibility-install.json',receipt)
    state.update(compatibilityInstalled=True,state='COMPATIBILITY_INSTALLED_RUNTIME_PENDING');d.save(d.STAGE/'device.json',state)
    open_main();print('INSTALLED10024_STEER7_XRAY5_TLS5_PROTECTED_UNCHANGED')
if __name__=='__main__':main()
