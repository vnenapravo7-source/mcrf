"""Explicit human-authorized replacement of Android 0.6/0.6.1 assets, with backups."""
import hashlib,json,shutil,subprocess,sys,urllib.request,zipfile
from pathlib import Path
S=Path(sys.argv[1]);P=Path('D:/MCRF/Builds/Android/engine-delivery-docs-public-20261023')
MAIN_DOWNLOAD='--main-download' in sys.argv
GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf'
BASE=Path('D:/MCRF/Builds/Android/release06-publication-20261023/assets/mcrf-android-0.6-source.zip')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def gh(*a):return subprocess.check_output([GH,*a])
def api(p,*a):return json.loads(gh('api','repos/'+REPO+'/'+p,*a))
def git(*a):return subprocess.check_output(['git','-C',str(P),*a]).decode().strip()
def verify(a,p):
    assert a['digest']=='sha256:'+sha(p) and a['size']==p.stat().st_size
    with urllib.request.urlopen(urllib.request.Request(a['browser_download_url'],headers={'User-Agent':'MCRF-independent-intervals','Cache-Control':'no-cache'}),timeout=120) as r:
        h=hashlib.sha256();size=0
        while chunk:=r.read(1048576):h.update(chunk);size+=len(chunk)
    assert h.hexdigest()==sha(p) and size==p.stat().st_size
if '--finalize' in sys.argv:
    final=read(S/'ui-final-receipt.json');assert final['state']=='PASS_INAPP_UPDATE_INSTALLER_UI' and final['noPhoneMutation']
    path=S/'publication/receipt.json';r=read(path);assert r['state']=='PUBLISHED_AWAITING_INAPP_RUNTIME'
    r.update(state='PUBLISHED_INAPP_UPDATE_VERIFIED',actualUi=final);save(path,r);print('INAPP_UPDATE_RUNTIME_FINALIZED');sys.exit()
proof=read(S/'receipt.json');ui=read(S/'ui-receipt.json')
if MAIN_DOWNLOAD:
    assert proof['state']=='PASS_INAPP_UPDATE_PACKAGING' and proof['versionCode']==10047 and proof['newTests']==4
    assert ui['state']=='PASS_INAPP_UPDATE_PREPUBLICATION' and ui['checks']==4 and ui['noPhoneMutation']
else:
    assert proof['state']=='PASS_INDEPENDENT_INTERVALS_PACKAGING' and proof['versionCode']==10046 and proof['retainedModelChecks']==4 and proof['newTests']==0
    assert ui['state']=='PASS_INTERVAL_POPUP_UI' and ui['checks']==4 and ui['noPhoneMutation']
assert sha(Path(proof['apk']))==proof['sha256']
d=S/'publication';d.mkdir(exist_ok=True)
docs=['README.md','ports/android/README.md','docs/ANDROID-GUIDE.md','docs/screenshots/android-independent-intervals-061.png']
sr=d/'source-receipt.json'
if sr.exists():commit=read(sr)['commit']
else:
    parent=git('rev-parse','HEAD');assert api('git/ref/heads/main')['object']['sha']==parent
    assert set(git('diff','--name-only').splitlines()).issubset(set(docs))
    for n in proof['overlay']:
        (P/n).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(Path(proof['source'])/n,P/n)
    for n in proof['overlay']+docs:subprocess.run(['git','-C',str(P),'add','--',n],check=True)
    staged=set(git('diff','--cached','--name-only').splitlines());assert staged and staged.issubset(set(proof['overlay']+docs))
    for n in staged:assert hashlib.sha256(subprocess.check_output(['git','-C',str(P),'show',':'+n])).hexdigest()==sha(P/n)
    message='fix(android): download application updates in-app with verified installer handoff' if MAIN_DOWNLOAD else 'fix(android): separate BS detection and server-selection intervals'
    subprocess.run(['git','-C',str(P),'-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit','-m',message],check=True,stdout=(d/'commit.log').open('wb'))
    commit=git('rev-parse','HEAD');subprocess.run(['git','-C',str(P),'push','origin','HEAD:main'],check=True)
    assert api('git/ref/heads/main')['object']['sha']==commit;save(sr,{'commit':commit,'parent':parent,'files':sorted(staged)})
latest=api('releases/latest');results=[]
frozen={r['path']:r for r in read(S/'SOURCE_MANIFEST.json')}
for tag,version in [('android-v0.6.1','0.6.1'),('android-v0.6','0.6')]:
    out=d/tag;out.mkdir(exist_ok=True);backup=out/'backup';backup.mkdir(exist_ok=True)
    before_file=backup/'release.json'
    before=read(before_file) if before_file.exists() else api('releases/tags/'+tag)
    if not before_file.exists():save(before_file,before)
    tag_file=backup/'tag.json'
    if not tag_file.exists():save(tag_file,api('git/ref/tags/'+tag))
    names={'mcrf-android-universal-release.apk','arm64-v8a-release.apk',f'mcrf-android-{version}-source.zip','SHA256SUMS.txt'}
    for a in before['assets']:
        if a['name'] not in names:continue
        target=backup/a['name']
        if not target.exists():subprocess.run([GH,'release','download',tag,'--repo',REPO,'--pattern',a['name'],'--dir',str(backup)],check=True,stdout=subprocess.DEVNULL)
        assert sha(target)==a['digest'].removeprefix('sha256:')
    main=out/'mcrf-android-universal-release.apk';alias=out/'arm64-v8a-release.apk'
    shutil.copyfile(proof['apk'],main);shutil.copyfile(main,alias)
    archive=out/f'mcrf-android-{version}-source.zip'
    if not archive.exists():
        with zipfile.ZipFile(BASE) as old,zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as new:
            manifest=[];included=set()
            def add(name,data):
                new.writestr(name,data);included.add(name);manifest.append({'path':name,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
            for info in old.infolist():
                n=info.filename
                if n=='CORRESPONDING_SOURCE_MANIFEST.json':continue
                data=(Path(proof['source'])/n).read_bytes() if n in frozen else (P/n).read_bytes() if n in docs else old.read(info)
                add(n,data)
            for n in set(proof['overlay']+docs)-included:add(n,(Path(proof['source'])/n).read_bytes() if n in frozen else (P/n).read_bytes())
            new.writestr('CORRESPONDING_SOURCE_MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2))
            new.writestr('APP_BUILD_INPUT_MANIFEST.json',json.dumps(list(frozen.values()),ensure_ascii=False,indent=2))
    with zipfile.ZipFile(archive) as z:
        covered=0
        for n,row in frozen.items():
            if n in z.namelist():assert hashlib.sha256(z.read(n)).hexdigest()==row['sha256'],n;covered+=1
        assert covered>=1107
    files=[main,alias,archive];sums=out/'SHA256SUMS.txt'
    sums.write_text(''.join(sha(p)+'  '+p.name+'\n' for p in files),encoding='utf-8');files.append(sums)
    # Same release IDs and tags; only the explicitly named artifacts are replaced.
    subprocess.run([GH,'release','upload',tag,*map(str,files),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
    after=api('releases/tags/'+tag);assert after['id']==before['id'] and not after['draft'] and not after['prerelease']
    for p in files:verify(next(a for a in after['assets'] if a['name']==p.name),p)
    a=next(a for a in after['assets'] if a['name']==alias.name)
    api('releases/assets/'+str(a['id']),'--method','PATCH','-f','label=arm64-v8a-release.apk · универсальный APK для старого обновлятора')
    if version=='0.6.1':notes='Раздельные интервалы автовыбора сервера и автоопределения БС: 15, 30, 45 секунд; 1, 2, 3, 4, 5, 10, 15 минут.\n\nИсправлен обновлятор: универсальный APK и короткие версии 0.6 / 0.7.\n\narm64-v8a-release.apk — совместимое имя того же универсального APK для старых версий.'
    else:
        marker='\n\n### Исправленный APK\n'
        notes=before['body'].split(marker)[0]+marker+'По запросу файл заменён на MCRF 0.6.1 с рабочим обновлятором и раздельными интервалами автоопределения БС и автовыбора серверов. Имя исходного архива сохранено для совместимости; внутри — соответствующие исправленному APK исходники. Уже установленные копии исходной 0.6 от замены файла на GitHub не изменяются.'
    if MAIN_DOWNLOAD:
        notes='Обновления приложения скачиваются внутри MCRF: прогресс, отмена, проверка целостности, подписи и версии, затем системное подтверждение Android. Без браузера.\n\n'+notes
    api('releases/'+str(after['id']),'--method','PATCH','-f','body='+notes,'-f','make_latest=false')
    assert api('git/ref/tags/'+tag)['object']['sha']==read(tag_file)['object']['sha']
    results.append({'tag':tag,'releaseId':after['id'],'mainSha256':sha(main),'sourceCovered':covered,'backup':str(backup),'assetsVerified':len(files)})
assert api('releases/latest')['id']==latest['id']
save(d/'receipt.json',{'state':'PUBLISHED_AWAITING_INAPP_RUNTIME' if MAIN_DOWNLOAD else 'PUBLISHED_061_AND_REPLACED_06_VERIFIED','sourceCommit':commit,'versionCode':proof['versionCode'],'versionName':'0.6.1','releases':results,'windowsLatestUnchanged':latest['tag_name'],'oldInstalled06NotRemotelyChanged':True})
print('PUBLISHED_BOTH_RELEASE_ASSETS_VERIFIED; '+commit)
