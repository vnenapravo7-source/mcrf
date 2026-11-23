"""Fresh P049 phone grant. Read-only helper; GUI actions require observed controls.
No credentials/profile decryption/export and no arbitrary private-file reads."""
import argparse,hashlib,io,json,re,subprocess,sys,time,xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path('D:/MCRF/Projects/MCRF')
STAGE=Path('D:/MCRF/Builds/Android/p049-phone-29ab72fdecd74d03a163fb5cfa7c0349')
CANDIDATE=Path('D:/MCRF/Builds/Android/p049-candidate-701f35cbd75f41dab40a97dafdca7cb0')
ADB='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL='R5CX73QYN3D';OWNER='01a13a9b-6754-7b13-af52-422a6eb5579c'
JOB='P049:29ab72fdecd74d03a163fb5cfa7c0349'
HELPER='org.mcrf.client.timing49';RUNNER=HELPER+'/org.mcrf.timing.TimingInstrumentation'
DISPLAY='4630946564756762243'
def adb(*args,timeout=45):return subprocess.run([ADB,'-s',SERIAL,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout).stdout
def text(*args,timeout=45):return adb(*args,timeout=timeout).decode('utf-8','replace').strip()
def save(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def state():return json.loads((STAGE/'device.json').read_text('utf-8'))
def ready(own=False,install=False):
    lease=json.loads((ROOT/'ports/android/coordination/device-lease.json').read_text('utf-8-sig'))
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    grant=json.loads((ROOT/'ports/android/coordination/p0-49-phone-authority-2026-10-20.json').read_text('utf-8-sig'))
    assert lease['owner_thread']==OWNER and lease['device']==SERIAL and live['jobs']['device']==JOB
    assert grant['allowed'] and not grant['completed'] and not live['jobs']['emulator']
    assert text('shell','am','get-current-user')=='0'
    assert 'mWakefulness=Awake' in text('shell','dumpsys','power'),'Fresh ordinary unlock required'
    assert re.search(r'(?m)^\s+showing=false\s*$',text('shell','dumpsys','window','policy')),'Phone locked'
    vpn=text('shell','dumpsys','vpn_management').split('User 150')[0]
    active=re.search(r'Active package name: (\S+)\s+Active vpn type: ([01])\b',vpn)
    assert not active or own and active[1]=='org.mcrf.client','Active user/foreign VPN: never stop or replace'
    if install:
        for package in ['org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov']:
            assert not re.search('isForeground=true|startRequested=true|startForegroundCount=[1-9]',text('shell','dumpsys','activity','services',package)),'Active service: no install/instrumentation'
    focus='\n'.join(s for s in text('shell','dumpsys','window').splitlines() if 'mCurrentFocus=' in s)
    owned_popup=bool(re.search(r'u0 PopupWindow:[0-9a-f]+',focus)) and any('org.mcrf.client/.MainActivity' in line for line in text('shell','dumpsys','activity','activities').splitlines() if 'topResumedActivity=' in line)
    assert owned_popup or re.search(r'(org\.mcrf\.client|com\.sec\.android\.app\.launcher)/',focus),'Unexpected foreground; inspect before input'
def installed(package):
    result=subprocess.run([ADB,'-s',SERIAL,'shell','pm','path','--user','0',package],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    if result.returncode==1 and not result.stdout.strip() and not result.stderr.strip():return None
    result.check_returncode();raw=result.stdout.decode('utf-8','replace').strip()
    match=re.fullmatch(r'package:(/data/app/[^\r\n]+/base.apk)',raw)
    if not raw:return None
    assert match,'Unexpected APK path'
    digest=text('shell','sha256sum',match[1]).split()[0];assert re.fullmatch('[a-f0-9]{64}',digest)
    return {'path':match[1],'sha256':digest}
def checkpoint(label):
    ready(install=True)
    output=text('shell','am','instrument','--user','0','-w','-r','-e','mode','checkpoint',RUNNER,timeout=60)
    assert 'INSTRUMENTATION_RESULT: passed=true' in output and 'INSTRUMENTATION_CODE: -1' in output
    line=next(x[len('INSTRUMENTATION_RESULT: json='):] for x in output.splitlines() if x.startswith('INSTRUMENTATION_RESULT: json='))
    data=json.loads(line);save(STAGE/(label+'.json'),data);return data
def nodes():
    ready(own=state().get('ownsVpn',False))
    remote='/sdcard/mcrf-p049-29ab72fdecd74d03.xml'
    adb('shell','rm','-f',remote)
    result=text('shell','uiautomator','dump','--compressed',remote,timeout=30)
    assert 'UI hierchary dumped to:' in result,'No fresh UI: do not read stale dump'
    values=list(ET.fromstring(adb('exec-out','cat',remote)).iter('node'))
    assert not any(n.get('password')=='true' for n in values),'Do not capture secret editors'
    return [n for n in values if n.get('package')=='org.mcrf.client']
def summary(values):return [{'text':n.get('text'),'desc':n.get('content-desc'),'bounds':n.get('bounds'),'clickable':n.get('clickable')} for n in values if n.get('text') or n.get('content-desc')]

def tap(label,desc=True,claim=False):
    values=nodes();key='content-desc' if desc else 'text';found=[n for n in values if n.get(key)==label]
    assert len(found)==1,'Expected one freshly observed control: '+label
    rect=list(map(int,re.findall(r'\d+',found[0].get('bounds',''))));assert len(rect)==4 and rect[2]>rect[0] and rect[3]>rect[1]
    if claim:
        data=state();data['ownsVpn']=True;data['ownStartMono']=time.monotonic();save(STAGE/'device.json',data)
    adb('shell','input','tap',str((rect[0]+rect[2])//2),str((rect[1]+rect[3])//2))
def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['init','install','inspect','tap','back','swipe','shot','timings','checkpoint'])
    parser.add_argument('--label');parser.add_argument('--desc',action='store_true');parser.add_argument('--name',default='screen')
    parser.add_argument('--swipe',nargs=4,type=int);parser.add_argument('--own-vpn',action='store_true');args=parser.parse_args()
    assert re.fullmatch('[a-z0-9-]+',args.name)
    if args.action=='init':
        ready(install=True);assert not (STAGE/'device.json').exists()
        build=json.loads((CANDIDATE/'build.json').read_text('utf-8-sig'))
        assert build['state']=='PASS_R8_CANDIDATE_AND_151_JVM'
        helper=json.loads((STAGE/'helper/receipt.json').read_text('utf-8-sig'));assert helper['state']=='PASS' and sha(Path(helper['apk']))==helper['sha256']
        assert installed(HELPER) is None,'Never replace an existing helper'
        oldTest=installed('org.mcrf.client.test')
        if oldTest:
            adb('pull',oldTest['path'],str(STAGE/'original-test.apk'))
            assert sha(STAGE/'original-test.apk')==oldTest['sha256']
        originalPower=text('shell','settings','get','global','stay_on_while_plugged_in');assert originalPower=='null' or re.fullmatch('[0-9]+',originalPower)
        data={'state':'PREPARING','grant':'810c95b','candidate':10022,'ownsVpn':False,'originalPower':originalPower,'originalTestApk':oldTest,'originalMainApk':installed('org.mcrf.client'),'helperInstalled':False,'installedCandidate':False}
        save(STAGE/'device.json',data)
        adb('shell','svc','power','stayon','usb')
        assert 'Success' in text('install','--user','0','--no-streaming',helper['apk'])
        data['helperInstalled']=True;save(STAGE/'device.json',data)
        before=checkpoint('before-install');data['state']='PREPARED';save(STAGE/'device.json',data)
        print(json.dumps({'state':'PREPARED','beforeVersion':before['versionCode'],'prefs':before['prefs'],'modules':{k:v.get('versionCode') if isinstance(v,dict) else v for k,v in before['modules'].items()}},ensure_ascii=False));return
    data=state()
    if args.action=='install':
        assert not data['installedCandidate'];ready(install=True)
        build=json.loads((CANDIDATE/'build.json').read_text('utf-8-sig'));apk=build['artifacts']['universal'];assert sha(Path(apk['path']))==apk['sha256']
        before=json.loads((STAGE/'before-install.json').read_text('utf-8'))
        assert 'Success' in text('install','--user','0','--no-streaming','-r',apk['path'],timeout=90)
        assert installed('org.mcrf.client')['sha256']==apk['sha256']
        data['installedCandidate']=True;data['state']='INSTALLED_NOT_RUNTIME_ACCEPTED';save(STAGE/'device.json',data)
        after=checkpoint('after-install')
        assert after['versionCode']==10022 and after['files']==before['files'] and after['prefHashes']==before['prefHashes'] and after['modules']==before['modules'],'Install changed protected state'
        data['installProtectedUnchanged']=True;save(STAGE/'device.json',data)
        adb('shell','am','start','-n','org.mcrf.client/.MainActivity');print('INSTALLED_10022_PROTECTED_UNCHANGED');return
    if args.action=='inspect':print(json.dumps(summary(nodes()),ensure_ascii=False));return
    if args.action=='tap':
        assert args.label;tap(args.label,args.desc,args.own_vpn);print('TAPPED');return
    if args.action=='back':ready(own=data.get('ownsVpn',False));adb('shell','input','keyevent','4');print('BACK');return
    if args.action=='swipe':
        assert args.swipe;ready(own=data.get('ownsVpn',False));adb('shell','input','swipe',*map(str,args.swipe),'400');print('SWIPED');return
    if args.action=='shot':
        ready(own=data.get('ownsVpn',False));raw=adb('exec-out','screencap','-d',DISPLAY,'-p');assert raw.startswith(b'\x89PNG\r\n\x1a\n')
        Image.open(io.BytesIO(raw)).verify();(STAGE/(args.name+'.png')).write_bytes(raw);print(str(STAGE/(args.name+'.png')));return
    if args.action=='timings':
        ready(install=True);output=text('shell','am','instrument','--user','0','-w','-r','-e','mode','timings',RUNNER)
        assert 'passed=true' in output and 'INSTRUMENTATION_CODE: -1' in output
        body=next(x[len('INSTRUMENTATION_RESULT: json='):] for x in output.splitlines() if x.startswith('INSTRUMENTATION_RESULT: json='))
        timings=json.loads(body);save(STAGE/(args.name+'-timings.json'),timings);data['ownsVpn']=False;save(STAGE/'device.json',data)
        body=timings['timings'];starts=[m.start() for m in re.finditer(r'(?m)^.*\[Тайминги\] ui_queue=',body)]
        print(body[starts[-1]:] if starts else body[-1200:]);return
    if args.action=='checkpoint':
        result=checkpoint(args.name);print(json.dumps({'version':result['versionCode'],'prefs':result['prefs']},ensure_ascii=False));return
if __name__=='__main__':main()
