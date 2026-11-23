"""Continue the unfinished authorized P049 phone repair from a NEW live baseline.

No publication, historical preference rollback, foreign VPN stop or unlock
bypass. Ordinary human-authorized wake/unlock is a separate observed step.
The existing exact reader APK is temporary; no new test APK is compiled here.
"""
import argparse
import json
from pathlib import Path
import time

import p049_device as d
from connection49_common import BUILDS, digest
from verify_connection49_expanded_host import verify_inputs


def configure(stage):
    stage=stage.resolve(strict=True)
    if stage.parent!=BUILDS.resolve() or not stage.name.startswith('p049-expanded-'):
        raise ValueError('exact expanded common stage required')
    prep=verify_inputs(stage)
    common=json.loads((stage/'expanded-common.json').read_text('utf-8'))
    if common['state']!='PASS_COMMON_HOST_BUILD_SIGNED_NOT_ANDROID_RUNTIME_ACCEPTED':
        raise ValueError('whole signed common package required')
    if digest(stage/'expanded-packaging.json')!=common['phases']['package']['receiptSha256']:
        raise ValueError('signed package receipt changed')
    d.STAGE=stage/'phone'
    d.JOB='P049_EXPANDED_PHONE:'+str(stage)
    return prep


def open_main():
    # Lease/grant/awake/unlocked/user0/VPN/service guards precede any input.
    # ready also requires a known MCRF/Chrome/launcher foreground.
    d.ready(install=True)
    d.adb('shell','am','start','-n','org.mcrf.client/.MainActivity')
    time.sleep(.7)
    d.ready(install=True)


def install(stage,prep):
    if d.STAGE.exists():raise ValueError('resume existing phone evidence, never duplicate it')
    current=d.ROOT/'ports/android/coordination/current.json'
    live=json.loads(current.read_text('utf-8-sig'))
    if any(live['jobs'].values()):raise ValueError('another live job owns the device/build')
    package=json.loads((stage/'expanded-packaging.json').read_text('utf-8'))
    if package['state']!='PASS_EXPANDED_SIGNED_R8_NOT_DEVICE_ACCEPTED':
        raise ValueError('current signed package not ready')
    targets=[next(r for r in package['artifacts'] if r['package']==name and r['abi']==abi)
        for name,abi in [('org.mcrf.client','universal'),('org.mcrf.engines.steer','arm64-v8a'),
                         ('org.mcrf.engines.tlscore','arm64-v8a')]]
    expected=[prep['mainVersionCode'],prep['steerVersionCode'],prep['tlsVersionCode']]
    for row,version in zip(targets,expected):
        if row['versionCode']!=version or digest(Path(row['path']))!=row['sha256']:
            raise ValueError('current signed artifact differs')
    old_phone=BUILDS/'p049-phone-29ab72fdecd74d03a163fb5cfa7c0349'
    helper=json.loads((old_phone/'helper-native-cleanup/receipt.json').read_text('utf-8'))
    if helper['state']!='PASS' or digest(Path(helper['apk']))!=helper['sha256']:
        raise ValueError('existing authorized reader artifact changed')
    # Claim first, then fresh device gates. On a refused preflight release only
    # our untouched claim. Once preparation starts, preserve it for recovery.
    live['jobs']['device']=d.JOB;d.save(current,live)
    try:
        open_main()
        if d.installed(d.HELPER) is not None:raise ValueError('never replace an existing reader')
        original_test=d.installed('org.mcrf.client.test')
        original_xray=d.installed('org.mcrf.engines.xray')
        power=d.text('shell','settings','get','global','stay_on_while_plugged_in')
        if not power.isdecimal():raise ValueError('unknown original screen-power preference')
    except BaseException:
        live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['device']==d.JOB:live['jobs']['device']=None;d.save(current,live)
        raise
    d.STAGE.mkdir()
    state={'state':'INSTALLING','ownsVpn':False,'helperInstalled':False,
        'originalPower':power,'originalTestApk':original_test,'originalXray':original_xray,
        'candidate':prep['mainVersionCode'],'readerSha256':helper['sha256']}
    d.save(d.STAGE/'device.json',state)
    receipt={'state':'INSTALLING','steps':[],'targets':targets,'published':False,
        'packageReceiptSha256':digest(stage/'expanded-packaging.json')}
    d.save(d.STAGE/'install.json',receipt)
    try:
        d.ready(install=True);d.adb('shell','svc','power','stayon','usb')
        if 'Success' not in d.text('install','--user','0','--no-streaming',helper['apk']):
            raise ValueError('reader install failed')
        if d.installed(d.HELPER)['sha256']!=helper['sha256']:raise ValueError('reader identity differs')
        state['helperInstalled']=True;d.save(d.STAGE/'device.json',state)
        open_main();before=d.checkpoint('before-install');open_main()
        if before['prefs']['wantedRunning'] or before['versionCode']>=prep['mainVersionCode']:
            raise ValueError('not an idle forward update')
        for row in targets:
            d.ready(install=True)
            if 'Success' not in d.text('install','--user','0','--no-streaming','-r',row['path'],timeout=90):
                raise ValueError('authorized update failed')
            if d.installed(row['package'])['sha256']!=row['sha256']:
                raise ValueError('installed artifact differs')
            receipt['steps'].append(row['package']);d.save(d.STAGE/'install.json',receipt)
            if row['package']=='org.mcrf.client':open_main()
        open_main();after=d.checkpoint('after-install')
        if after['prefHashes']!=before['prefHashes'] or after['files']!=before['files']:
            raise ValueError('update changed protected preferences/catalogue')
        changed={key for key,value in before['modules'].items() if after['modules'].get(key)!=value}
        if changed!={'steer','tlscore'}:raise ValueError('unexpected module delta')
        if d.installed('org.mcrf.engines.xray')!=original_xray or d.installed('org.mcrf.client.test')!=original_test:
            raise ValueError('unrequested package changed')
        receipt.update(state='INSTALLED_EXACT_PREFS_CATALOGUE_UNCHANGED_RUNTIME_PENDING',updatedModules=sorted(changed))
        state['state']='INSTALLED_RUNTIME_PENDING';d.save(d.STAGE/'device.json',state)
        open_main();print(receipt['state'])
    except BaseException:
        receipt['state']='FAILED_OWNED_RECONCILE';raise
    finally:d.save(d.STAGE/'install.json',receipt)


def finish():
    """Verify UI-restored NEW baseline; remove only our consent/reader/power."""
    path=d.STAGE/'cleanup.json'
    if path.exists() or (d.STAGE/'before-cleanup.json').exists():
        raise ValueError('reconcile prior cleanup evidence; never overwrite it')
    state=d.state();open_main()
    baseline=json.loads((d.STAGE/'before-install.json').read_text('utf-8'))
    installed=json.loads((d.STAGE/'after-install.json').read_text('utf-8'))
    before=d.checkpoint('before-cleanup');key='steerDnsModelV2Accepted'
    allowed_delta=key not in baseline['prefHashes'] and key in before['prefHashes']
    actual={k:v for k,v in before['prefHashes'].items() if not (allowed_delta and k==key)}
    if actual!=baseline['prefHashes'] or before['prefs']!=baseline['prefs']:
        raise ValueError('restore only our temporary UI changes before cleanup')
    if before['files']!=baseline['files'] or before['modules']!=installed['modules']:
        raise ValueError('protected catalogue/module delta needs reconciliation')
    if d.installed(d.HELPER)['sha256']!=state['readerSha256']:
        raise ValueError('temporary reader identity changed')
    if allowed_delta:
        if not state.get('ownedConsent'):raise ValueError('consent change not recorded as ours')
        d.ready(install=True)
        output=d.text('shell','am','instrument','--user','0','-w','-r','-e','mode','restore-consent',
            '-e','original','absent',d.RUNNER,timeout=60)
        if 'passed=true' not in output or '"restoredOwnedConsent":true' not in output:
            raise ValueError('owned consent cleanup failed')
    open_main();after=d.checkpoint('after-cleanup')
    if after['prefHashes']!=baseline['prefHashes'] or after['files']!=baseline['files'] or after['modules']!=installed['modules']:
        raise ValueError('full current baseline not restored')
    if d.installed('org.mcrf.client.test')!=state['originalTestApk'] or d.installed('org.mcrf.engines.xray')!=state['originalXray']:
        raise ValueError('unrequested package changed')
    d.ready(install=True)
    if 'Success' not in d.text('uninstall','--user','0',d.HELPER) or d.installed(d.HELPER) is not None:
        raise ValueError('owned reader retirement unconfirmed')
    d.adb('shell','rm','-f','/sdcard/mcrf-p049-29ab72fdecd74d03.xml')
    d.adb('shell','settings','put','global','stay_on_while_plugged_in',state['originalPower'])
    if d.text('shell','settings','get','global','stay_on_while_plugged_in')!=state['originalPower']:
        raise ValueError('power setting restoration unconfirmed')
    state.update(state='RESTORED_IDLE',helperInstalled=False,ownsVpn=False);d.save(d.STAGE/'device.json',state)
    current=d.ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'))
    if live['jobs']['device']!=d.JOB:raise ValueError('phone ownership changed')
    d.save(path,{'state':'PASS','allPreferencesFreshBaselineExact':True,'catalogueExact':True,
        'onlyAuthorizedUpdatesRetained':True,'readerRemoved':True,'powerRestored':state['originalPower']})
    live['jobs']['device']=None;d.save(current,live);print('FRESH_BASELINE_RESTORED_OWNED_READER_REMOVED')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('stage',type=Path)
    parser.add_argument('action',choices=['install','inspect','checkpoint','timings','finish'])
    parser.add_argument('--label',default='live')
    args=parser.parse_args();prep=configure(args.stage)
    if args.action=='install':install(args.stage,prep);return
    if args.action=='finish':finish();return
    if not args.label.replace('-','').isalnum():raise ValueError('simple evidence label required')
    if args.action=='inspect':print(json.dumps(d.summary(d.nodes()),ensure_ascii=False));return
    if args.action=='checkpoint':
        if (d.STAGE/(args.label+'.json')).exists():raise ValueError('preserve previous checkpoint')
        result=d.checkpoint(args.label)
        print(json.dumps({'versionCode':result['versionCode'],'prefs':result['prefs']},ensure_ascii=False));return
    d.ready(install=True)
    if (d.STAGE/(args.label+'-timings.json')).exists():raise ValueError('preserve previous timing evidence')
    output=d.text('shell','am','instrument','--user','0','-w','-r','-e','mode','timings',d.RUNNER)
    if 'passed=true' not in output or 'INSTRUMENTATION_CODE: -1' not in output:raise ValueError('timing reader failed')
    body=next(line.partition('INSTRUMENTATION_RESULT: json=')[2] for line in output.splitlines() if line.startswith('INSTRUMENTATION_RESULT: json='))
    d.save(d.STAGE/(args.label+'-timings.json'),json.loads(body))
    print('TIMINGS_SAVED_WITHOUT_RAW_PROFILE_OUTPUT')


if __name__=='__main__':main()
