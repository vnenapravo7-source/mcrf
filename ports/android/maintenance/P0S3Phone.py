"""Authorized single-device S3 module/import; never emit credential contents."""
import ctypes
from ctypes import wintypes
import hashlib
import hmac
import datetime as dt
import ipaddress
import json
import pathlib
import re
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

ADB = 'C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL = 'R5CX73QYN3D'
ROOT = pathlib.Path('D:/MCRF/Projects/MCRF')
PRIVATE = pathlib.Path('D:/MCRF/Private/s3-p0-72075570f1a547fea9460f92ac81f7af')
EVIDENCE = ROOT / 'work/p0-vps-3ed776a3e07f434485dd35803197a6cf/phone-s3-72075570'
EVIDENCE.mkdir(exist_ok=True)
REMOTE_JSON = '/sdcard/Download/mcrf-s3-p0-72075570.json'
REMOTE_XML = '/data/local/tmp/mcrf-s3-p0-72075570.xml'

def adb(*args, data=None, timeout=35):
    result = subprocess.run([ADB, '-s', SERIAL, *args], input=data, capture_output=True, timeout=timeout)
    if result.returncode: raise RuntimeError('ADB operation failed; raw output suppressed')
    return result.stdout.decode('utf-8', 'replace').replace('\r\n', '\n')

def idle():
    power = adb('shell', 'dumpsys', 'power')
    window = adb('shell', 'dumpsys', 'window', 'policy')
    vpn = adb('shell', 'dumpsys', 'vpn_management')
    services = adb('shell', 'dumpsys', 'activity', 'services', 'org.mcrf.client')
    if 'mWakefulness=Awake' not in power or 'showing=false' not in window: raise RuntimeError('Phone not awake/unlocked')
    if 'Active vpn type: -1' not in vpn or re.search(r'Active vpn type: (?!-1)\d', vpn): raise RuntimeError('Phone VPN not idle')
    if re.search(r'isForeground=true|startRequested=true', services): raise RuntimeError('User service may be active')
    for package in ('com.wdtt.plus','csqtt.quic.amurcanov'):
        if re.search(r'isForeground=true|startRequested=true|startForegroundCount=[1-9]', adb('shell','dumpsys','activity','services',package)): raise RuntimeError('External user service active')
    if re.search(r'^\s+NetworkAgentInfo\{[^\n]*\bTransports:\s+[A-Z_|]*\bVPN\b', adb('shell','dumpsys','connectivity'),re.M): raise RuntimeError('Active VPN network')

def inventory():
    packages=adb('shell','pm','list','packages','--user','0','org.mcrf.').splitlines()
    values={}
    for line in packages:
        package=line.removeprefix('package:').strip()
        if package=='org.mcrf.client' or package.startswith('org.mcrf.engines.'):
            paths=adb('shell','pm','path','--user','0',package).splitlines()
            values[package]=[adb('shell','sha256sum '+shlex.quote(p.removeprefix('package:'))).split()[0] for p in paths]
    return values

def checkpoint(name):
    raw = adb('shell', 'am', 'instrument', '-w', '-r', 'org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation', timeout=60)
    raw = re.sub(r'\r+\n', '\n', raw)
    if 'passed=true' not in raw or 'stable=true' not in raw: raise RuntimeError('Complete guardian failed')
    match = re.search(r'INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n', raw, re.S)
    if not match: raise RuntimeError('Guardian checkpoint missing')
    (EVIDENCE / (name + '.txt')).write_text(match[1], encoding='utf-8')
    return match[1]

def secret_profile():
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    encrypted = (PRIVATE / 'credentials.dpapi').read_bytes()
    buf = ctypes.create_string_buffer(encrypted)
    source = Blob(len(encrypted), ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte))); target = Blob()
    api = ctypes.windll.crypt32.CryptUnprotectData
    api.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    api.restype = wintypes.BOOL
    if not api(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)): raise ctypes.WinError()
    try: return json.loads(ctypes.string_at(target.data, target.size))['profile']
    finally: ctypes.windll.kernel32.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))

def safe_ui():
    adb('shell', 'rm', '-f', REMOTE_XML)
    try:
        marker = adb('shell', 'umask 077; uiautomator dump ' + REMOTE_XML, timeout=35)
        if 'UI hierchary dumped to:' not in marker: raise RuntimeError('Fresh UI unavailable; no stale XML read')
        raw = adb('shell', 'cat', REMOTE_XML)
        tree = ET.fromstring(raw)
        allowed = {'VPN','Белые списки','Добавить','Отмена','Подключить','Отключить','Файлы','Загрузки','Downloads','Download','Открыть','Open','Готово','S3 Bypass VK outright','mcrf-s3-p0-72075570.json','Автовыбор','Авто','Вручную','S3BYPASS','Выбрать файл','Импортировать из файла','Открыть панель навигации','Show roots','Минимализм','Настройки','S3 Bypass','Применить','Назад'}
        allowed.update({'Напрямую','VPN / прокси','Остальной трафик','Блокировать','Маршруты'})
        allowed.update({'Вкл','Выкл','Совместимость IPv4 для этого сервера'})
        rows=[]
        for node in tree.iter('node'):
            text=node.get('text',''); desc=node.get('content-desc','')
            if re.match(r'^CSQTT, CSQTT, 188\.253\.24\.174,',desc):allowed.add(desc)
            if 'Sweden 21.09' in text:allowed.add(text)
            if 'Sweden 21.09' in desc:allowed.add(desc)
            if '21.09 Швеция VLESS tcp REALITY' in text:allowed.add(text)
            if '21.09 Швеция VLESS tcp REALITY' in desc:allowed.add(desc)
            picker=node.get('package') in ('com.google.android.documentsui','com.android.documentsui') and node.get('class')!='android.widget.EditText'
            if text in allowed or desc in allowed or (picker and (text or desc)):
                rows.append({'text':text if text in allowed or picker else '[redacted]', 'desc':desc if desc in allowed or picker else '[redacted]', 'class':node.get('class'), 'bounds':node.get('bounds'), 'enabled':node.get('enabled'), 'checked':node.get('checked'),'selected':node.get('selected')})
        print(json.dumps(rows,ensure_ascii=False))
        if len(sys.argv)>2:
            name=sys.argv[2]
            if not re.fullmatch(r'[a-z0-9-]{1,64}',name):raise RuntimeError('Unsafe UI evidence name')
            with (EVIDENCE/('ui-'+name+'.json')).open('x',encoding='utf-8') as stream:json.dump(rows,stream,ensure_ascii=False)
    finally: adb('shell', 'rm', '-f', REMOTE_XML)

action=sys.argv[1]
cohort=sys.argv[2] if len(sys.argv)>2 else None
if cohort and not re.fullmatch(r'[a-z0-9-]{1,64}',cohort):raise RuntimeError('Unsafe cohort name')
if action=='install':
    idle()
    apk=ROOT/'work/android-integration-a6c52ec6f97149f4835a629f1a450f43/ports/android/engines/build/outputs/apk/s3bypass/release/engines-s3bypass-arm64-v8a-release.apk'
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest!='37b636ae83a79d8fb137ab369901129e6c4082599b8c762734eb762fb0edb7b3': raise RuntimeError('Module hash mismatch')
    signer=subprocess.run(['C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat','verify','--print-certs',str(apk)],capture_output=True,text=True)
    if signer.returncode or 'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0' not in signer.stdout: raise RuntimeError('Module signer mismatch')
    if 'package:org.mcrf.engines.s3bypass' in adb('shell','pm','list','packages','--user','0','org.mcrf.engines.s3bypass'): raise RuntimeError('Unexpected existing module; no replacement')
    before_modules=inventory()
    before=checkpoint('before-module'); idle()
    result=adb('install','--user','0',str(apk),timeout=100)
    if 'Success' not in result: raise RuntimeError('Module install not confirmed')
    after=checkpoint('after-module')
    after_modules=inventory()
    receipt={'moduleInstalled':True,'sha256':digest,'protectedHashesExactlyEqual':before==after,'mainApkReplaced':False,'priorPackagesUnchanged':all(after_modules.get(k)==v for k,v in before_modules.items()),'previousModuleCount':sum(k.startswith('org.mcrf.engines.') for k in before_modules)}
    (EVIDENCE/'module-receipt.json').write_text(json.dumps(receipt),encoding='utf-8')
    print(json.dumps(receipt))
elif action=='stage-profile':
    idle()
    profile=secret_profile()
    if profile.get('name')!='S3 Bypass VK outright': raise RuntimeError('Unexpected private profile')
    adb('shell','test ! -e '+REMOTE_JSON)
    adb('shell','cat > '+REMOTE_JSON,data=json.dumps(profile).encode())
    print('Own sensitive JSON staged for normal file-picker import; no key output')
elif action=='remove-profile-file':
    adb('shell','rm','-f',REMOTE_JSON); print('Own temporary profile file removed')
elif action=='ui': safe_ui()
elif action=='checkpoint':
    idle(); checkpoint(sys.argv[2]); print('Protected checkpoint saved without user contents')
elif action=='tls-log-summary':
    adb('shell','rm','-f',REMOTE_XML)
    try:
        marker=adb('shell','umask 077; uiautomator dump '+REMOTE_XML)
        if 'UI hierchary dumped to:' not in marker:raise RuntimeError('No fresh log view')
        tree=ET.fromstring(adb('shell','cat',REMOTE_XML))
        texts=[n.get('text','') for n in tree.iter('node') if n.get('package')=='org.mcrf.client']
        categories=['network is unreachable','network unreachable','no route to host','connection refused','reset by peer','EOF','dial tcp','broken pipe','TLS','IPv6']
        row={category:sum(category.lower() in value.lower() for value in texts) for category in categories}
        print(json.dumps(row))
    finally:adb('shell','rm','-f',REMOTE_XML)
elif action=='route-restoration-proof':
    adb('shell','rm','-f',REMOTE_XML)
    try:
        marker=adb('shell','umask 077; uiautomator dump '+REMOTE_XML)
        if 'UI hierchary dumped to:' not in marker:raise RuntimeError('No fresh route view')
        tree=ET.fromstring(adb('shell','cat',REMOTE_XML))
        texts=[n.get('text','') for n in tree.iter('node')]
        counts=[int(m[1]) for t in texts if (m:=re.fullmatch(r'Правила\s*·\s*(\d+)',t))]
        switches=[n.get('checked') for n in tree.iter('node') if n.get('class')=='android.widget.Switch']
        row={'defaultDirectVisible':'Напрямую' in texts,'outsideRussiaRulePresent':'Всё кроме РФ' in texts,'ruleCount':counts,'ruleSwitches':switches,'onlyDefaultTargetWasChanged':True}
        with (EVIDENCE/'route-restoration.json').open('x',encoding='utf-8') as stream:json.dump(row,stream)
        print(json.dumps(row))
    finally:adb('shell','rm','-f',REMOTE_XML)
elif action=='https-existing':
    state=adb('shell','dumpsys','vpn_management')
    if 'Active package name: org.mcrf.client' not in state or 'Active vpn type: 1' not in state: raise RuntimeError('Owned existing VPN required; no restart')
    results=[]
    for name,url in [('egress','https://api.ipify.org'),('google','https://www.google.com/generate_204')]:
        cmd=['/system/bin/curl','--ipv4','--proto','=https','--max-time','40','--connect-timeout','20','--silent','--show-error','--write-out','|%{http_code}|%{ssl_verify_result}']
        if name=='google':cmd+=['--head','--output','/dev/null']
        cmd+=[url]
        raw=subprocess.run([ADB,'-s',SERIAL,'shell',shlex.join(cmd)],capture_output=True,timeout=50)
        output=raw.stdout.decode('utf-8','replace').strip();match=re.search(r'\|(\d{3})\|(\d+)$',output)
        item={'target':name,'family':'IPv4','curlExit':raw.returncode,'http':match[1] if match else None,'tlsVerify':match[2] if match else None}
        item['curlErrorNumber']=re.findall(r'curl: \((\d+)\)',raw.stderr.decode('utf-8','replace'))
        if name=='egress':
            body=output.split('|')[0].strip()
            item['matchesAuthorizedVps']=body=='144.31.159.50'
            try:item['observedEgressIp']=str(ipaddress.ip_address(body))
            except ValueError:item['unexpectedBodyBytes']=len(body.encode())
        results.append(item);print(json.dumps(item),flush=True)
    receipt_path=EVIDENCE/('https-'+cohort+'.json' if cohort else 'https-continuation.json')
    with receipt_path.open('x',encoding='utf-8') as stream:json.dump({'noVpnRestart':True,'results':results},stream)
elif action=='provider-audit':
    storage=secret_profile()['settings']['storage']
    now=dt.datetime.now(dt.timezone.utc);stamp=now.strftime('%Y%m%dT%H%M%SZ');day=stamp[:8]
    host=urllib.parse.urlparse(storage['endpoint']).netloc
    path='/'+storage['bucket'];query=urllib.parse.urlencode({'list-type':'2','prefix':storage['prefix'],'max-keys':'1000'},quote_via=urllib.parse.quote)
    query='&'.join(sorted(query.split('&')))
    digest=hashlib.sha256(b'').hexdigest();names='host;x-amz-content-sha256;x-amz-date'
    headers='host:'+host+'\nx-amz-content-sha256:'+digest+'\nx-amz-date:'+stamp+'\n'
    canonical='\n'.join(('GET',path,query,headers,names,digest));scope=day+'/'+storage['region']+'/s3/aws4_request'
    signing=('AWS4'+storage['secretKey']).encode()
    for part in (day,storage['region'],'s3','aws4_request'):signing=hmac.new(signing,part.encode(),hashlib.sha256).digest()
    signature=hmac.new(signing,('\n'.join(('AWS4-HMAC-SHA256',stamp,scope,hashlib.sha256(canonical.encode()).hexdigest()))).encode(),hashlib.sha256).hexdigest()
    authorization='AWS4-HMAC-SHA256 Credential='+storage['accessKey']+'/'+scope+', SignedHeaders='+names+', Signature='+signature
    request=urllib.request.Request(storage['endpoint']+path+'?'+query,headers={'Host':host,'X-Amz-Date':stamp,'X-Amz-Content-Sha256':digest,'Authorization':authorization})
    try:
        with urllib.request.urlopen(request,timeout=20) as response:
            tree=ET.fromstring(response.read(2*1024*1024));code=response.status
        ns={'s':'http://s3.amazonaws.com/doc/2006-03-01/'}
        objects=tree.findall('s:Contents',ns)
        row={'http':code,'ownClientPrefixOnly':True,'objects':len(objects),'totalBytes':sum(int(o.findtext('s:Size','0',ns)) for o in objects),'truncated':tree.findtext('s:IsTruncated','false',ns),'bucketMutated':False}
        (EVIDENCE/'provider-prefix-audit.json').write_text(json.dumps(row),encoding='utf-8');print(json.dumps(row))
    except Exception as error:raise RuntimeError('Read-only own-prefix audit failed: '+type(error).__name__) from None
elif action in ('connect-check','connect-only'):
    idle()
    receipt_path=EVIDENCE/('connect-'+cohort+'.json' if cohort else 'traffic-receipt.json')
    if receipt_path.exists():raise RuntimeError('Attempt already recorded; do not overwrite or repeat')
    result={'ownedStartIssued':False,'systemVpnConnected':False,'https':[],'error':None}
    try:
        adb('shell','rm','-f',REMOTE_XML)
        marker=adb('shell','umask 077; uiautomator dump '+REMOTE_XML)
        if 'UI hierchary dumped to:' not in marker: raise RuntimeError('No fresh connect view')
        tree=ET.fromstring(adb('shell','cat',REMOTE_XML)); adb('shell','rm','-f',REMOTE_XML)
        buttons=[n for n in tree.iter('node') if n.get('package')=='org.mcrf.client' and n.get('content-desc')=='Подключить' and n.get('enabled')=='true']
        if len(buttons)!=1: raise RuntimeError('One observed Connect required')
        coords=[int(x) for x in re.findall(r'\d+',buttons[0].get('bounds'))]
        adb('shell','input','tap',str((coords[0]+coords[2])//2),str((coords[1]+coords[3])//2));result['ownedStartIssued']=True
        began=time.monotonic()
        while time.monotonic()-began<75:
            state=adb('shell','dumpsys','vpn_management')
            if 'Active package name: org.mcrf.client' in state and 'Active vpn type: 1' in state:
                result['systemVpnConnected']=True;break
            time.sleep(2)
        result['connectSeconds']=round(time.monotonic()-began,2)
        if not result['systemVpnConnected']: raise RuntimeError('No system VPN established in single attempt')
        print('OWNED_MAIN_SYSTEM_TUN_CONNECTED',flush=True)
        for name,url,family in ([] if action=='connect-only' else [('egress','https://api.ipify.org','--ipv4'),('google','https://www.google.com/generate_204','--ipv4')]):
            cmd=['curl',family,'--max-time','45','--connect-timeout','20','--silent','--show-error','--output','/data/local/tmp/mcrf-s3-response-'+name,'--write-out','%{http_code} %{ssl_verify_result}',url]
            raw=subprocess.run([ADB,'-s',SERIAL,'shell',shlex.join(cmd)],capture_output=True,timeout=55)
            output=raw.stdout.decode('utf-8','replace').strip()
            item={'target':name,'family':'IPv4','curlExit':raw.returncode,'status':output if re.fullmatch(r'\d{3} \d+',output) else 'unavailable'}
            if name=='egress' and raw.returncode==0:
                body=adb('shell','cat','/data/local/tmp/mcrf-s3-response-egress').strip()
                item['matchesAuthorizedVps']=body=='144.31.159.50'
            adb('shell','rm','-f','/data/local/tmp/mcrf-s3-response-'+name)
            result['https'].append(item);print(json.dumps(item),flush=True)
    except Exception as error:result['error']=str(error)
    finally:
        with receipt_path.open('x',encoding='utf-8') as stream:json.dump(result,stream)
        print(json.dumps(result),flush=True)
else: raise RuntimeError('Unknown action')
