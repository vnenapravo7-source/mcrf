"""Fresh authorized read-only UI gallery; never connects, installs or opens secrets."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image

ROOT=Path('D:/MCRF/Projects/MCRF')
spec=importlib.util.spec_from_file_location('tour',ROOT/'ports/android/maintenance/real_android_tour.py')
tour=importlib.util.module_from_spec(spec);spec.loader.exec_module(tour)

def checkpoint():
    files=tour.text('shell','run-as','org.mcrf.client','sha256sum',
        'files/connections.enc','files/routing.enc','files/preferences.enc')
    data=tour.adb('exec-out','run-as','org.mcrf.client','cat','shared_prefs/MainActivity.xml')
    # Never retain actual preference values or credentials, only equality fingerprints.
    prefs={n.get('name'):hashlib.sha256(ET.tostring(n)).hexdigest() for n in ET.fromstring(data)}
    return {'files':files,'preferences':prefs}

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['init','inspect','tap','back','swipe','shot','finish'])
    p.add_argument('--stage',type=Path,required=True);p.add_argument('--label');p.add_argument('--description',action='store_true')
    p.add_argument('--name');p.add_argument('--swipe',nargs=4,type=int)
    a=p.parse_args();stage=a.stage.resolve()
    assert stage.parent==Path('D:/MCRF/Builds/Android').resolve() and re.fullmatch('real-gallery-[a-f0-9]{32}',stage.name)
    currentPath=ROOT/'ports/android/coordination/current.json'
    current=json.loads(currentPath.read_text(encoding='utf-8-sig'));job='REAL_GALLERY:'+str(stage)
    tour.ready()
    if a.action=='init':
        assert not current['jobs']['device'] and not current['jobs']['emulator'] and not stage.exists()
        stage.mkdir();tour.save(stage/'before.json',checkpoint())
        tour.save(stage/'authority.json',{'human':'20Oct four real GIFs/section screenshots plus latest explicit SELF ordinary unlock/navigation',
            'connect':False,'install':False,'secrets':False,'settingsChanges':False})
        current['jobs']['device']=job;tour.save(currentPath,current);print(stage);return
    assert current['jobs']['device']==job and (stage/'authority.json').exists()
    if a.action=='inspect':
        nodes=tour.hierarchy(stage)
        print(json.dumps([{'text':n.get('text'),'desc':n.get('content-desc'),'bounds':n.get('bounds')}
              for n in nodes if n.get('package')=='org.mcrf.client' and (n.get('text') or n.get('content-desc'))],ensure_ascii=False))
    elif a.action=='tap':
        assert a.label and a.label not in ['Подключить','Скачать','Установить','Создать','Сохранить','Начать поиск']
        nodes=tour.hierarchy(stage);key='content-desc' if a.description else 'text'
        found=[n for n in nodes if n.get('package')=='org.mcrf.client' and n.get(key)==a.label]
        assert len(found)==1,'Unique observed control required'
        x,y=tour.bounds(found[0]);tour.ready();tour.text('shell','input','tap',str(x),str(y));print('TAPPED')
    elif a.action=='back':tour.text('shell','input','keyevent','4');print('BACK')
    elif a.action=='swipe':
        assert a.swipe;tour.text('shell','input','swipe',*map(str,a.swipe),'500');print('SCROLLED')
    elif a.action=='shot':
        assert a.name and re.fullmatch('android-[a-z-]+',a.name)
        nodes=tour.hierarchy(stage)
        assert not any(n.get('password')=='true' for n in nodes)
        # Reject credential editors/QR screens rather than redact their actual content.
        assert not any(n.get('class')=='android.widget.EditText' and re.search('парол|токен|ключ|логин',
                    n.get('hint','')+' '+n.get('content-desc',''),re.I) for n in nodes)
        image=Image.open(io.BytesIO(tour.adb('exec-out','screencap','-d',tour.DISPLAY,'-p')))
        assert image.size==(1080,2640)
        image.save(stage/(a.name+'.png'))
        print(stage/(a.name+'.png'))
    elif a.action=='finish':
        after=checkpoint();before=json.loads((stage/'before.json').read_text(encoding='utf-8'))
        changed=[k for k in before['preferences'].keys()|after['preferences'].keys() if before['preferences'].get(k)!=after['preferences'].get(k)]
        assert after['files']==before['files'],'Protected profile data changed'
        assert not changed,'Restore changed controls first: '+str(changed)
        assert 'Active vpn type: 1' not in tour.text('shell','dumpsys','vpn_management')
        tour.save(stage/'receipt.json',{'state':'CAPTURE_FINISHED_UNCHANGED','profileFilesEqual':True,'preferenceKeysChanged':changed,
                    'connected':False,'installed':False,'shots':[f.name for f in stage.glob('*.png')]})
        current['jobs']['device']=None;tour.save(currentPath,current)
        tour.adb('shell','rm','-f','/sdcard/mcrf-real-tour-'+stage.name+'.xml','/sdcard/mcrf-gallery-unlock.xml')
        print('FINISHED_UNCHANGED')

if __name__=='__main__':main()
