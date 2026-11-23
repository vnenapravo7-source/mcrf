"""Human-authorized real Samsung UI recording. No fixtures, installs or secret editors."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path('D:/MCRF/Projects/MCRF')
ADB = 'C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL = 'R5CX73QYN3D'
DISPLAY = '4630946564756762243'
OWNER = '01a13a9b-6754-7b13-af52-422a6eb5579c'
sys.stdout.reconfigure(encoding='utf-8')

def adb(*args, timeout=30):
    return subprocess.run([ADB, '-s', SERIAL, *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout).stdout

def text(*args):
    return adb(*args).decode('utf-8', 'replace').strip()

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def ready():
    lease = json.loads((ROOT/'ports/android/coordination/device-lease.json').read_text(encoding='utf-8-sig'))
    assert lease['device'] == SERIAL and lease['owner_thread'] == OWNER
    assert text('shell', 'am', 'get-current-user') == '0'
    assert 'mWakefulness=Awake' in text('shell', 'dumpsys', 'power'), 'Phone asleep; no automatic wake'
    assert re.search(r'(?m)^\s+showing=false\s*$', text('shell', 'dumpsys', 'window', 'policy')), 'Phone locked'
    focus = '\n'.join(s for s in text('shell', 'dumpsys', 'window').splitlines() if 'mCurrentFocus=' in s)
    assert 'org.mcrf.client/' in focus, 'Human foreground changed; no input outside MCRF'
    vpn = text('shell', 'dumpsys', 'vpn_management')
    # Reject another owner if a VPN is active; this request permits only MCRF connect/disconnect.
    first_user = vpn.split('User 150')[0]
    active = re.search(r'Active package name: (\S+)\s+Active vpn type: ([01])\b', first_user)
    assert not active or active[1] == 'org.mcrf.client', 'Foreign VPN is active'

def hierarchy(stage):
    ready()
    remote = '/sdcard/mcrf-real-tour-' + stage.name + '.xml'
    adb('shell','rm','-f',remote)
    result=text('shell', 'uiautomator', 'dump', remote)
    assert 'UI hierchary dumped to:' in result, 'No fresh hierarchy; never read old XML after idle timeout'
    data = adb('exec-out', 'cat', remote)
    xml = ET.fromstring(data)
    nodes = list(xml.iter('node'))
    assert all(n.get('password') != 'true' for n in nodes), 'Secret editor must not be recorded'
    return nodes

def bounds(node):
    vals = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(vals) == 4 and vals[2] > vals[0] and vals[3] > vals[1]
    return (vals[0]+vals[2])//2, (vals[1]+vals[3])//2

def main():
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=['init', 'inspect', 'tap', 'swipe', 'back', 'shot', 'record', 'finish'])
    p.add_argument('--stage', type=Path, required=True)
    p.add_argument('--name', default='screen')
    p.add_argument('--label')
    p.add_argument('--description', action='store_true')
    p.add_argument('--prefix', action='store_true')
    p.add_argument('--point', type=int, nargs=2)
    p.add_argument('--swipe', type=int, nargs=4)
    p.add_argument('--seconds', type=int, default=25)
    p.add_argument('--flow', choices=['sections','connect-disconnect','disconnect-only','whitelists'])
    a = p.parse_args()
    stage = a.stage.resolve()
    assert stage.parent == Path('D:/MCRF/Builds/Android').resolve() and re.fullmatch(r'real-tour-[a-f0-9]{32}', stage.name)
    assert re.fullmatch(r'[a-z0-9-]+', a.name)
    current_path = ROOT/'ports/android/coordination/current.json'
    job = 'REAL_TOUR:' + str(stage)
    if a.action == 'init':
        ready()
        assert not stage.exists()
        current = json.loads(current_path.read_text(encoding='utf-8-sig'))
        assert not current['jobs']['device'] and not current['jobs']['emulator']
        stage.mkdir()
        checkpoint = text('shell', 'run-as', 'org.mcrf.client', 'sha256sum',
                          'files/connections.enc', 'files/routing.enc', 'files/preferences.enc',
                          'shared_prefs/MainActivity.xml')
        (stage/'protected-before.txt').write_text(checkpoint, encoding='utf-8')
        # Retain original preferences locally for semantic restoration only. Never publish them.
        (stage/'private-original-preferences.xml').write_bytes(adb('exec-out', 'run-as', 'org.mcrf.client', 'cat', 'shared_prefs/MainActivity.xml'))
        save(stage/'authority.json', {'human':'20Oct real own servers; connected USB; explicitly permits connect/disconnect for GIF',
                                    'device':SERIAL, 'physicalPhone':True, 'demo':False,
                                    'installs':False, 'foreignVpnStop':False, 'publishSecrets':False})
        current['jobs']['device'] = job
        save(current_path, current)
        print('REAL_TOUR_STARTED=' + str(stage))
        return
    current = json.loads(current_path.read_text(encoding='utf-8-sig'))
    assert current['jobs']['device'] == job and (stage/'authority.json').is_file()
    if a.action == 'record':
        ready()
        assert 3 <= a.seconds <= 60
        remote = '/sdcard/mcrf-' + stage.name + '-' + a.name + '.mp4'
        out = stage/(a.name+'.mp4')
        assert not out.exists()
        recorder = subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--display-id',DISPLAY,
                        '--time-limit',str(a.seconds),'--bit-rate','6000000',remote], stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        events=[]
        started=time.monotonic()
        def tap(label, description=False, prefix=False):
            nodes=hierarchy(stage);key='content-desc' if description else 'text'
            found=[n for n in nodes if n.get('package')=='org.mcrf.client' and
                   (n.get(key,'').startswith(label) if prefix else n.get(key)==label)]
            assert len(found)==1, 'Expected visible unique control: '+label
            x,y=bounds(found[0]);ready();text('shell','input','tap',str(x),str(y))
            events.append({'second':round(time.monotonic()-started,2),'action':label})
        try:
            if a.flow=='whitelists':
                time.sleep(2);tap('Подключения · VPN.',True,True);time.sleep(3)
                ready();text('shell','input','swipe','540','2170','540','1350','650');time.sleep(2)
                tap('Подключения · БС.',True,True);time.sleep(2)
                ready();text('shell','input','swipe','540','600','540','2300','650')
            elif a.flow=='disconnect-only':
                time.sleep(2);ready()
                vpn=text('shell','dumpsys','vpn_management').split('User 150')[0]
                assert re.search(r'Active package name: org\.mcrf\.client\s+Active vpn type: 1\b',vpn), 'Only this recording-owned MCRF VPN may be stopped'
                # The fresh screenshot was visually inspected: hero [84,364][996,1036].
                # Accessibility idle may time out during animation; never substitute stale XML.
                (stage/(a.name+'-tap-proof.png')).write_bytes(adb('exec-out','screencap','-d',DISPLAY,'-p'))
                text('shell','input','tap','540','700')
                events.append({'second':round(time.monotonic()-started,2),'action':'Normal STOP on visually verified connection hero'})
                time.sleep(7)
            elif a.flow=='sections':
                time.sleep(2)
                ready();text('shell','input','swipe','540','2170','540','1220','650');time.sleep(2)
                tap('Маршрутизация',True);time.sleep(3)
                tap('Настройки',True);time.sleep(2)
                tap('DNS и домены');time.sleep(3)
                ready();text('shell','input','keyevent','4');time.sleep(1)
                tap('Оформление');time.sleep(3)
                tap('Светлая тема',True);time.sleep(3)
                ready();text('shell','input','keyevent','4');time.sleep(1)
                ready();text('shell','input','keyevent','4');time.sleep(3)
                tap('Тёмная тема',True);time.sleep(2)
            elif a.flow=='connect-disconnect':
                # Navigation preserves scroll; reveal the actual hero before looking for its button.
                ready();text('shell','input','swipe','540','600','540','2300','650');time.sleep(1)
                time.sleep(2);tap('Подключить · Отключено',True)
                # Capture enough real connected motion, never substitute a fake READY frame.
                time.sleep(12)
                state=hierarchy(stage)
                assert any(n.get('text')=='VPN подключён' for n in state), 'Real VPN did not become ready'
                events.append({'second':round(time.monotonic()-started,2),'state':'REAL_VPN_CONNECTED'})
                tap('Отключить · Подключ',True,True);time.sleep(6)
            recorder.communicate(timeout=max(1,a.seconds+20-(time.monotonic()-started)))
            assert recorder.returncode==0
        finally:
            save(stage/(a.name+'-events.json'),events)
            if recorder.poll() is None:
                # Finite recorder owns its own time limit. Do not kill an unknown device process.
                recorder.communicate(timeout=a.seconds+20)
        adb('pull', remote, str(out), timeout=60)
        assert out.stat().st_size > 50000
        adb('shell', 'rm', remote)
        save(stage/(a.name+'.json'), {'file':out.name, 'bytes':out.stat().st_size,
             'sha256':hashlib.sha256(out.read_bytes()).hexdigest(), 'realScreenrecord':True})
        print('RECORDED=' + str(out))
    elif a.action == 'inspect':
        nodes = hierarchy(stage)
        print(json.dumps([{'text':n.get('text'), 'description':n.get('content-desc'),
                           'class':n.get('class'), 'clickable':n.get('clickable'),
                           'checked':n.get('checked'), 'bounds':n.get('bounds')}
                          for n in nodes if n.get('text') or n.get('content-desc')], ensure_ascii=False))
    elif a.action == 'tap':
        nodes = hierarchy(stage)
        if a.point:
            x,y = a.point
            assert any(n.get('package') == 'org.mcrf.client' and n.get('clickable') == 'true'
                       and (lambda v:v[0]<=x<v[2] and v[1]<=y<v[3])(list(map(int,re.findall(r'\d+',n.get('bounds',''))))) for n in nodes)
        else:
            assert a.label
            key = 'content-desc' if a.description else 'text'
            matches = [n for n in nodes if (n.get(key, '').startswith(a.label) if a.prefix else n.get(key) == a.label) and n.get('package') == 'org.mcrf.client']
            assert len(matches) == 1, 'Control not unique or not visible'
            x,y = bounds(matches[0])
        ready()
        text('shell','input','tap',str(x),str(y))
        print('TAPPED')
    elif a.action == 'swipe':
        ready(); assert a.swipe
        text('shell','input','swipe',*map(str,a.swipe),'550')
        print('SCROLLED')
    elif a.action == 'back':
        ready();text('shell','input','keyevent','4');print('BACK')
    elif a.action == 'shot':
        ready(); (stage/(a.name+'.png')).write_bytes(adb('exec-out','screencap','-d',DISPLAY,'-p'));print(str(stage/(a.name+'.png')))
    elif a.action == 'finish':
        ready()
        after = text('shell', 'run-as', 'org.mcrf.client', 'sha256sum', 'files/connections.enc',
                     'files/routing.enc', 'files/preferences.enc', 'shared_prefs/MainActivity.xml')
        (stage/'protected-after.txt').write_text(after, encoding='utf-8')
        def preferences(data):
            return {n.get('name'):ET.tostring(n,encoding='unicode') for n in ET.fromstring(data)}
        before=preferences((stage/'private-original-preferences.xml').read_bytes())
        now=preferences(adb('exec-out','run-as','org.mcrf.client','cat','shared_prefs/MainActivity.xml'))
        changed=[key for key in before.keys()|now.keys() if before.get(key)!=now.get(key)]
        assert not any(key in changed for key in ['selected','auto','autoDetectBs','theme','connectionWindowStyle','connectionExitStyle']), 'Restore original user controls before closing'
        assert 'Active vpn type: 1' not in text('shell','dumpsys','vpn_management'), 'Recording-owned VPN must be stopped'
        save(stage/'recording-complete.json', {'state':'RECORDING_FINISHED_CONTROLS_RESTORED_VPN_OFF',
              'humanProfilesReset':False, 'installed':False, 'published':False,'changedPreferenceKeys':changed})
        current['jobs']['device'] = None
        save(current_path,current)
        print('RECORDING_FINISHED')

if __name__ == '__main__':
    main()
