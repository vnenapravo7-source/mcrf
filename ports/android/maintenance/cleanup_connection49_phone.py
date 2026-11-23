"""Restore only the owned P049 temporary consent/helper/power deltas."""
import json,time,sys
import p049_device as d

def return_main():
    focus='\n'.join(x for x in d.text('shell','dumpsys','window').splitlines() if 'mCurrentFocus=' in x)
    assert any(x in focus for x in ['org.mcrf.client/','com.android.chrome/','com.sec.android.app.launcher/'])
    d.adb('shell','am','start','-n','org.mcrf.client/.MainActivity')
    time.sleep(.7)

return_main()

compat=sys.argv[1:]==['--compatibility10024']
followup=sys.argv[1:] in [['--followup'],['--steer9'],['--steer10'],['--steer11']]
prefix=sys.argv[1][2:] if followup else 'followup'
assert not sys.argv[1:] or compat or followup
baseline=json.loads((d.STAGE/('before-'+prefix+'-install.json' if followup else 'before-install10024.json' if compat else 'before-install.json')).read_text('utf-8'))
expected_modules=json.loads((d.STAGE/('after-'+prefix+'-install.json' if followup else 'after-install10024.json')).read_text('utf-8'))['modules'] if compat or followup else baseline['modules']
d.ready(install=True)
before=d.checkpoint(prefix+'-before-owned-cleanup' if followup else 'compat-before-owned-cleanup' if compat else 'before-owned-cleanup')
key='steerDnsModelV2Accepted'
assert key not in baseline['prefHashes']
assert {k:v for k,v in before['prefHashes'].items() if k!=key}==baseline['prefHashes']
assert key in before['prefHashes'] and before['modules']==expected_modules
assert before['prefs']==baseline['prefs']
for name,value in baseline['files'].items():
    if name.startswith('connections.'):
        assert before['files'][name]==value,'Catalogue changed: stop cleanup'
helper=json.loads((d.STAGE/'helper-native-cleanup/receipt.json').read_text('utf-8'))
assert helper['state']=='PASS' and d.sha(d.Path(helper['apk']))==helper['sha256']
assert d.installed(d.HELPER) is not None
return_main()
d.ready(install=True)
if d.installed(d.HELPER)['sha256']!=helper['sha256']:
    assert 'Success' in d.text('install','--user','0','--no-streaming','-r',helper['apk'])
d.ready(install=True)
result=d.text('shell','am','instrument','--user','0','-w','-r','-e','mode','restore-consent','-e','original','absent',d.RUNNER,timeout=60)
assert 'INSTRUMENTATION_RESULT: passed=true' in result and '"restoredOwnedConsent":true' in result
return_main()
after=d.checkpoint(prefix+'-after-owned-cleanup' if followup else 'compat-after-owned-cleanup' if compat else 'after-owned-cleanup')
assert after['prefHashes']==baseline['prefHashes'] and after['modules']==expected_modules
assert after['files']==before['files'],'Cleanup changed protected files'
original=d.state()['originalTestApk']
assert d.installed('org.mcrf.client.test')==original
return_main()
d.ready(install=True)
assert 'Success' in d.text('uninstall','--user','0',d.HELPER)
assert d.installed(d.HELPER) is None
d.adb('shell','rm','-f','/sdcard/mcrf-p049-29ab72fdecd74d03.xml')
assert d.state()['originalPowerFollowup' if followup else 'originalPower10024' if compat else 'originalPower']=='0'
d.adb('shell','svc','power','stayon','false')
assert d.text('shell','settings','get','global','stay_on_while_plugged_in')=='0'
state=d.state();state.update(ownsVpn=False,helperInstalled=False,state='RESTORED_IDLE_COMPATIBILITY_SOURCE_NEXT')
d.save(d.STAGE/'device.json',state)
current=d.ROOT/'ports/android/coordination/current.json'
live=json.loads(current.read_text('utf-8-sig'));assert live['jobs']['device']==d.JOB
live['jobs']['device']=None;d.save(current,live)
d.save(d.STAGE/(prefix+'-owned-cleanup.json' if followup else 'compat-owned-cleanup10024.json' if compat else 'owned-cleanup.json'),{'state':'PASS','allPreferencesBaselineExact':True,'modulesUnchangedSinceAuthorizedInstall':True,'catalogueUnchanged':True,'originalTestUntouched':True,'helperRemoved':True,'powerRestored':'0','p2Untested':True})
print('P049_OWNED_DELTAS_RESTORED_HELPER_REMOVED_POWER0_JOB_CLEAR')
