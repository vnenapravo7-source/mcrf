"""Bounded ordinary UI checks for the approved 10058 update. Never save settings or log web form values."""
import json,re,subprocess,sys,uuid,xml.etree.ElementTree as ET
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')

ADB='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL='R5CX73QYN3D'
STAGE=Path('D:/MCRF/Builds/Android/bs-sites-ui-20261026-001/phone-ui')

def adb(*args):
    p=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,timeout=35)
    if p.returncode:raise RuntimeError('ADB failed: '+args[0])
    return p.stdout
def text(*args):return adb(*args).decode('utf-8','replace')
def ready():
    assert text('shell','am','get-current-user').strip()=='0'
    assert 'versionCode=10058 ' in text('shell','dumpsys','package','org.mcrf.client')
    assert 'mWakefulness=Awake' in text('shell','dumpsys','power'),'No wake/unlock permitted'
    assert not re.search(r'Active vpn type: [01]\b',text('shell','dumpsys','vpn_management')),'Do not interrupt VPN'
    focus='\n'.join(x for x in text('shell','dumpsys','window').splitlines() if 'mCurrentFocus=' in x)
    assert 'org.mcrf.client/' in focus,'No input into another foreground app'
def nodes():
    ready();remote='/sdcard/mcrf-bs58-'+uuid.uuid4().hex+'.xml'
    result=text('shell','uiautomator','dump',remote)
    assert 'UI hierchary dumped to:' in result
    xml=adb('exec-out','cat',remote)
    adb('shell','rm',remote) # exact file just created by this invocation
    return list(ET.fromstring(xml).iter('node'))
def bounds(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))
def visible(node):
    b=bounds(node);return len(b)==4 and b[2]>b[0] and b[3]>b[1]
def ime():
    out=text('shell','dumpsys','input_method')
    match=re.search(r'\bmInputShown=(true|false)',out)
    assert match,'IME state unavailable'
    return match.group(1)=='true'

action=sys.argv[1]
if action=='inspect':
    safe={'Настройки','VPN и прокси','Vk Turn','Аккаунт и звонки VK','Сайты автоопределения БС','Напрямую · сайт 1','Напрямую · сайт 2','Через VPN · сайт 3','Совместимость IPv4','Сохранить сайты','Готово','Отмена','Аккаунт привязан','Гостевой режим · без аккаунта','Привязать VK','Отвязать VK','Привязать','Другой вход','Обновить','Зачем нужны три сайта','Ввести свой сайт 1','Ввести свой сайт 2','Ввести свой сайт 3','Выбрать Напрямую · сайт 1','Выбрать Напрямую · сайт 2','Выбрать Через VPN · сайт 3','Как определяется БС','ОК','OK','Свой сайт · 1'}
    out=[]
    for node in nodes():
        label=node.get('content-desc') or node.get('text','')
        if visible(node) and (label in safe or label.startswith('Вернуться:') or node.get('class')=='android.widget.Spinner'):
            out.append({'label':label,'class':node.get('class'),'bounds':bounds(node)})
    print(json.dumps({'controls':out,'imeShown':ime()},ensure_ascii=False))
elif action=='tap':
    label=sys.argv[2];ns=[n for n in nodes() if visible(n) and label in (n.get('text'),n.get('content-desc'))]
    assert len(ns)==1,'Ambiguous/missing target: '+label
    node=ns[0];assert node.get('password')!='true' and node.get('class') not in ('android.widget.Switch','android.widget.CheckBox')
    x1,y1,x2,y2=bounds(node);adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));print('Tapped named control')
elif action=='back':ready();adb('shell','input','keyevent','4');print('Android Back')
elif action=='shot':
    ns=nodes();assert any(n.get('text')=='Сайты автоопределения БС' and visible(n) for n in ns),'Screenshot only of scoped settings'
    assert not any(n.get('password')=='true' for n in ns)
    name=sys.argv[2];assert re.fullmatch('[a-z0-9-]+',name)
    STAGE.mkdir(parents=True,exist_ok=True);path=STAGE/(name+'.png');assert not path.exists()
    path.write_bytes(adb('exec-out','screencap','-p'));print(str(path))
elif action=='popup':
    ns=nodes();labels=[n.get('text') for n in ns if n.get('text') in ('google.com','gitlab.com','github.com','youtube.com','discord.gg','pinterest.com','wikipedia.org','netflix.com','livew.com','microsoft.com','twitch.tv','zoom.us') and visible(n)]
    print(json.dumps({'visiblePresetRows':len(labels),'imeShown':ime(),'labels':labels},ensure_ascii=False));assert labels and not ime()
else:raise ValueError('Unknown scoped action')
