"""Publish legacy filename compatibility and the universal-aware 0.6.1 bridge."""
import hashlib,json,re,shutil,subprocess,sys,urllib.request,zipfile
from pathlib import Path
GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf'
STAGE=Path(sys.argv[1]);MODE=sys.argv[2]
OLD=Path('D:/MCRF/Builds/Android/release06-publication-20261023/assets')
PUBLIC=Path('D:/MCRF/Builds/Android/engine-delivery-docs-public-20261023')
ROOT=Path('D:/MCRF/Projects/MCRF')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def gh(*a):return subprocess.check_output([GH,*a])
def api(p,*a):return json.loads(gh('api','repos/'+REPO+'/'+p,*a))
def find_release(tag):
    query='query { repository(owner:"vnenapravo7-source", name:"mcrf") { release(tagName:'+json.dumps(tag)+') { databaseId } } }'
    row=json.loads(gh('api','graphql','-f','query='+query))['data']['repository']['release']
    return api('releases/'+str(row['databaseId'])) if row else None
def git(*a):return subprocess.check_output(['git','-C',str(PUBLIC),*a]).decode().strip()
def verify(a,p):
    assert a['digest']=='sha256:'+sha(p) and a['size']==p.stat().st_size
    with urllib.request.urlopen(urllib.request.Request(a['browser_download_url'],headers={'User-Agent':'MCRF-update-bridge'}),timeout=120) as r:
        h=hashlib.sha256();size=0
        while chunk:=r.read(1024*1024):h.update(chunk);size+=len(chunk)
    assert h.hexdigest()==sha(p) and size==p.stat().st_size
def upload_missing(tag,files,public=True):
    release=find_release(tag);assert release is not None
    for p in files:
        a=next((a for a in release['assets'] if a['name']==p.name),None)
        if a:assert a['digest']=='sha256:'+sha(p),'Never overwrite a different existing APK'
        else:subprocess.run([GH,'release','upload',tag,str(p),'--repo',REPO],check=True,stdout=subprocess.DEVNULL)
    release=find_release(tag)
    for p in files:
        a=next(a for a in release['assets'] if a['name']==p.name)
        assert a['digest']=='sha256:'+sha(p) and a['size']==p.stat().st_size
        if public:verify(a,p)
    return release
if MODE=='alias':
    d=STAGE/'compatibility06';d.mkdir(exist_ok=True)
    before=api('releases/tags/android-v0.6');latest=api('releases/latest');save(d/'before.json',before)
    src=OLD/'mcrf-android-universal-release.apk'
    assert sha(src)=='8c79308dab7cbac4d1616af1931a88939e95617ff24f4819231b059dd1105169'
    alias=d/'arm64-v8a-release.apk';shutil.copyfile(src,alias)
    release=upload_missing('android-v0.6',[alias])
    a=next(a for a in release['assets'] if a['name']==alias.name)
    api('releases/assets/'+str(a['id']),'--method','PATCH','-f','label=Compatibility filename for old updater; identical universal APK, not an arm64-only build')
    sums=d/'SHA256SUMS.txt'
    sums.write_text(''.join(a['digest'].removeprefix('sha256:')+'  '+a['name']+'\n' for a in release['assets'] if a['name']!='SHA256SUMS.txt'),encoding='utf-8')
    # Preserve the replaced metadata and all original binaries.
    (d/'SHA256SUMS.before.txt').write_bytes((OLD/'SHA256SUMS.txt').read_bytes())
    subprocess.run([GH,'release','upload','android-v0.6',str(sums),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
    final=api('releases/tags/android-v0.6');verify(next(a for a in final['assets'] if a['name']==sums.name),sums)
    for old in before['assets']:
        if old['name']=='SHA256SUMS.txt':continue
        a=next(a for a in final['assets'] if a['name']==old['name']);assert (a['id'],a['digest'])==(old['id'],old['digest'])
    assert api('releases/latest')['id']==latest['id']
    save(d/'receipt.json',{'state':'PUBLISHED_06_COMPATIBILITY_ALIAS_VERIFIED','sha256':sha(alias),'originalBinariesUnchanged':True,'short06StillUnsupportedByOldParser':True})
    print('06_ALIAS_PUBLISHED_VERIFIED');sys.exit()
assert MODE=='release'
proof=read(STAGE/'receipt.json');assert proof['state']=='PASS_UPDATE_BRIDGE_PACKAGING' and proof['newTests']==5 and proof['sourceStable']
assert sha(Path(proof['apk']))==proof['sha256'] and proof['versionCode']==10044
badging=subprocess.check_output(['C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt2.exe','dump','badging',proof['apk']]).decode()
assert re.search(r"package: name='org.mcrf.client' versionCode='10044' versionName='0.6.1'",badging)
with zipfile.ZipFile(OLD/'mcrf-android-0.6-source.zip') as original:
    for row in read(STAGE/'SOURCE_MANIFEST.json'):
        if row['path'] in original.namelist():
            content=(Path(proof['source'])/row['path']).read_bytes()
            assert hashlib.sha256(content).hexdigest()==row['sha256'],row['path']
d=STAGE/'publication';d.mkdir(exist_ok=True);latest=api('releases/latest')
names=proof['overlay']+['README.md','ports/android/README.md','docs/ANDROID-GUIDE.md']
source_receipt=d/'source-receipt.json'
if source_receipt.exists():commit=read(source_receipt)['commit']
else:
    parent=git('rev-parse','HEAD');assert api('git/ref/heads/main')['object']['sha']==parent
    assert set(git('diff','--name-only').splitlines())==set(names[-3:]) and not git('ls-files','--others','--exclude-standard')
    for name in proof['overlay']:
        target=PUBLIC/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(Path(proof['source'])/name,target)
    # Documentation is prepared as a targeted patch before this script runs.
    for name in names:subprocess.run(['git','-C',str(PUBLIC),'add','--',name],check=True)
    staged=set(git('diff','--cached','--name-only').splitlines());assert staged==set(names),staged
    for name in names:
        assert hashlib.sha256(subprocess.check_output(['git','-C',str(PUBLIC),'show',':'+name])).hexdigest()==sha(PUBLIC/name)
    subprocess.run(['git','-C',str(PUBLIC),'-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit','-m','fix(android): bridge legacy updates to universal APK and short versions'],check=True,stdout=(d/'commit.log').open('wb'))
    commit=git('rev-parse','HEAD');subprocess.run(['git','-C',str(PUBLIC),'push','origin','HEAD:main'],check=True)
    assert api('git/ref/heads/main')['object']['sha']==commit;save(source_receipt,{'commit':commit,'parent':parent,'paths':names})
main=d/'mcrf-android-universal-release.apk';shutil.copyfile(proof['apk'],main)
alias=d/'arm64-v8a-release.apk';shutil.copyfile(main,alias)
archive=d/'mcrf-android-0.6.1-source.zip'
with zipfile.ZipFile(OLD/'mcrf-android-0.6-source.zip') as old,zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as new:
    manifest=[]
    frozen_by_name={row['path']:row for row in read(STAGE/'SOURCE_MANIFEST.json')}
    for info in old.infolist():
        name=info.filename
        if name=='CORRESPONDING_SOURCE_MANIFEST.json':continue
        # The old multi-module archive assembled some shared Gradle files from
        # another module's build. Bind every archived main input to THIS build.
        data=(Path(proof['source'])/name).read_bytes() if name in frozen_by_name else (PUBLIC/name).read_bytes() if name in names else old.read(info)
        new.writestr(name,data);manifest.append({'path':name,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
    new.writestr('CORRESPONDING_SOURCE_MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    new.writestr('APP_BUILD_INPUT_MANIFEST.json',json.dumps(read(STAGE/'SOURCE_MANIFEST.json'),ensure_ascii=False,indent=2))
with zipfile.ZipFile(archive) as z:
    frozen=read(STAGE/'SOURCE_MANIFEST.json');covered=0
    for row in frozen:
        if row['path'] in z.namelist():
            assert hashlib.sha256(z.read(row['path'])).hexdigest()==row['sha256'],row['path'];covered+=1
    assert covered>1000,covered
files=[main,alias,archive];sums=d/'SHA256SUMS.txt';sums.write_text(''.join(sha(p)+'  '+p.name+'\n' for p in files),encoding='utf-8');files.append(sums)
notes=d/'NOTES.md';notes.write_text('Исправлены поиск обновлений после перехода на универсальный APK и сравнение версий 0.6 / 0.7.\n\nФайл arm64-v8a-release.apk — совместимое имя того же универсального APK для старого обновлятора. В дальнейшем используется mcrf-android-universal-release.apk.\n',encoding='utf-8')
tag='android-v0.6.1'
release=find_release(tag)
if release is None:
    subprocess.run([GH,'release','create',tag,'--repo',REPO,'--target',commit,'--title','MCRF Android 0.6.1','--notes-file',str(notes),'--draft','--latest=false'],check=True,stdout=subprocess.DEVNULL)
    release=find_release(tag)
assert release['target_commitish']==commit and release['prerelease'] is False
release=upload_missing(tag,files,public=False)
if release['draft']:api('releases/'+str(release['id']),'--method','PATCH','-F','draft=false','-F','prerelease=false','-f','make_latest=false')
release=find_release(tag)
for p in files:verify(next(a for a in release['assets'] if a['name']==p.name),p)
assert api('releases/latest')['id']==latest['id']
save(d/'receipt.json',{'state':'PUBLISHED_061_UPDATE_BRIDGE_PUBLIC_DOWNLOADS_VERIFIED','commit':commit,'versionCode':10044,'newTests':5,'mainSha256':sha(main),'legacyAliasIdentical':sha(alias)==sha(main),'frozenArchiveInputsVerified':covered,'windowsLatestUnchanged':latest['tag_name'],'noNativeBuild':True,'deviceRuntimeNotRun':True})
print('061_BRIDGE_PUBLISHED_VERIFIED; '+commit)
