"""Publish verified CSQTT2.5 module7 APKs without changing the app or phone."""
import importlib.util, json, shutil, subprocess, sys, zipfile
from pathlib import Path

spec=importlib.util.spec_from_file_location('publication',Path(__file__).with_name('Publish-OpenFlux016.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
ROOT=p.ROOT; GH=p.GH; REPO=p.REPO; CAT=p.CAT
STAGE=Path('D:/MCRF/Builds/Android/csqtt25-publication-20261026')
BUILD=Path(sys.argv[1]); proof=p.read(BUILD/'receipt.json')
assert proof['state']=='BUILT_VERIFIED_NOT_INSTALLED_OR_PUBLISHED' and proof['sourceStable'] and proof['versionCode']==7
assert len(proof['files'])==4 and proof['lint'] and proof['nativeRebuilt'] is False
assert p.read(Path('D:/MCRF/Builds/Android/publish071-20261026/public-verification.json'))['state']=='PUBLIC_DOWNLOADS_VERIFIED'
STAGE.mkdir(exist_ok=True); assets=STAGE/'assets';assets.mkdir(exist_ok=True)
frozen=Path(proof['snapshot']);native=frozen/'ports/android/native-stage-csqtt25'
assert p.sha(frozen/'source-manifest.json')==proof['sourceManifestSha256']
descriptor=p.read(native/'receipt.json');wrapper=Path(descriptor['source'])
assert p.sha(native/'source-manifest.json')==descriptor['sourceManifestSha256']
overlay={}
for row in p.read(native/'source-manifest.json'):
    name=p.safe_name(row['path']);path=wrapper/name
    assert p.sha(path)==row['sha256'],name
    overlay['upstream/csqtt25-wrapper-v2.5.1/'+name]=path
for name in ('ports/android/engines/build.gradle.kts','ports/android/engines/EngineUpdateCsqtt25Manifest.xml'):
    overlay[name]=frozen/name
for name in ('Build-Csqtt25AllAbis.ps1','Publish-Csqtt25AllAbis.py'):
    overlay['ports/android/maintenance/'+name]=ROOT/'ports/android/maintenance'/name
overlay['ports/android/release/csqtt25-7/native-receipt.json']=native/'receipt.json'
for path in (native/'licenses').iterdir():
    if path.is_file():overlay['ports/android/release/csqtt25-7/licenses/'+path.name]=path
source=assets/'mcrf-engine-csqtt25-2.5.1-mcrf7-source.zip'
if not source.exists():
    with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as z:
        for name,path in sorted(overlay.items()):z.write(path,name)
        for path in (frozen/'ports/android/transports/swg').rglob('*'):
            if path.is_file() and path.suffix not in ('.so','.exe','.apk'):
                z.write(path,'ports/android/transports/swg/'+path.relative_to(frozen/'ports/android/transports/swg').as_posix())
        for path in (frozen/'ports/android/engines/src').rglob('*'):
            if path.is_file() and path.suffix not in ('.so','.apk'):
                z.write(path,'ports/android/engines/src/'+path.relative_to(frozen/'ports/android/engines/src').as_posix())
        z.writestr('SOURCE.json',json.dumps({'versionCode':7,'moduleVersion':'2.5.1-swgpanel-abi.7','baseSource':'https://github.com/'+REPO+'/releases/download/android-v0.7.1/mcrf-android-0.7.1-source.zip','wrapperSourceIncluded':True,'transportProvenance':'ports/android/release/csqtt25-7/licenses/swg-panel-reference.json','nativeRebuilt':False,'deviceRuntimeTested':False},indent=2))
with zipfile.ZipFile(source) as z:
    assert len(z.namelist())==len(set(z.namelist()))
    for name,path in overlay.items():assert p.hashlib.sha256(z.read(name)).hexdigest()==p.sha(path)
files=[]
for row in proof['files']:
    original=Path(row['path']);assert p.sha(original)==row['sha256']
    name=row['name'].replace('engines-csqtt25-','mcrf-engine-csqtt25-');dest=assets/name
    if not dest.exists():shutil.copyfile(original,dest)
    assert p.sha(dest)==row['sha256'];files.append({'name':name,'sha256':row['sha256'],'bytes':row['bytes']})
receipt_path=STAGE/'receipt.json';receipt=p.read(receipt_path) if receipt_path.exists() else {'state':'PREPARING','published':False}
if not (STAGE/'catalogue-before.json').exists():
    p.save(STAGE/'catalogue-before.json',p.api('releases/tags/'+CAT))
    p.save(STAGE/'latest-before.json',p.api('releases/latest'))
before=p.read(STAGE/'catalogue-before.json');selected={r['name'] for r in files}|{'SHA256SUMS.txt'}
backup=STAGE/'catalogue-backup';backup.mkdir(exist_ok=True)
for a in before['assets']:
    if a['name'] in selected:p.download_verified(a,backup/a['name'])
if 'commit' not in receipt:
    parent=p.api('git/ref/heads/main')['object']['sha']
    p.git('fetch','--depth','1','origin','main');assert p.git('rev-parse','FETCH_HEAD')==parent
    p.git('read-tree',parent)
    current=p.git('show',parent+':ports/android/engines/build.gradle.kts')
    assert 'it.identifier=="arm64-v8a"' in current and 'csqtt25StagedAbis==csqtt25NativePins.keys else abis.all' not in current
    # Only exact built input and corresponding wrapper sources are staged.
    for name,path in sorted(overlay.items()):
        blob=p.git('hash-object','-w','--stdin',data=path.read_bytes())
        p.git('update-index','--add','--cacheinfo','100644',blob,name)
    staged=set(p.git('diff','--cached','--name-only',parent).splitlines());assert staged and staged.issubset(overlay)
    assert p.api('git/ref/heads/main')['object']['sha']==parent,'Concurrent source edit'
    tree=p.git('write-tree','--missing-ok')
    commit=p.git('-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit-tree',tree,'-p',parent,'-m','fix(android): publish CSQTT2.5 module7 for all three Android ABIs')
    receipt.update(parent=parent,commit=commit);p.save(receipt_path,receipt)
remote=p.api('git/ref/heads/main')['object']['sha']
if remote!=receipt['commit']:
    assert remote==receipt['parent'],'Concurrent branch change'
    p.git('-c','credential.helper=','-c','credential.helper=!"C:/Program Files/GitHub CLI/gh.exe" auth git-credential','push','origin',receipt['commit']+':refs/heads/main')
assert p.api('git/ref/heads/main')['object']['sha']==receipt['commit']
for row in files:
    current=p.api('releases/tags/'+CAT);old=[a for a in current['assets'] if a['name']==row['name']]
    if not old or old[0]['digest']!='sha256:'+row['sha256']:
        if old:
            initial=next(a for a in before['assets'] if a['name']==row['name'])
            assert old[0]['id']==initial['id'] and old[0]['digest']==initial['digest'] and 'mcrf-version-code=6' in old[0]['label']
        subprocess.run([GH,'release','upload',CAT,str(assets/row['name']),'--repo',REPO]+(['--clobber'] if old else []),check=True,stdout=subprocess.DEVNULL)
    asset=next(a for a in p.api('releases/tags/'+CAT)['assets'] if a['name']==row['name'])
    p.check_asset(asset,assets/row['name'])
    p.api('releases/assets/'+str(asset['id']),{'label':'CSQTT 2.5.1 SWG Panel; mcrf-version-code=7'},method='PATCH')
    print('Published '+row['name'],flush=True)
current=p.api('releases/tags/'+CAT);old=[a for a in current['assets'] if a['name']==source.name]
if not old:subprocess.run([GH,'release','upload',CAT,str(source),'--repo',REPO],check=True,stdout=subprocess.DEVNULL)
else:p.check_asset(old[0],source)
after=p.api('releases/tags/'+CAT)
for old in before['assets']:
    if old['name'] in selected:continue
    fresh=next(a for a in after['assets'] if a['name']==old['name'])
    assert fresh['id']==old['id'] and fresh['digest']==old['digest'],'Unrelated asset changed'
sums=STAGE/'SHA256SUMS.txt'
sums.write_text(''.join(a['digest'].removeprefix('sha256:')+'  '+a['name']+'\n' for a in sorted(after['assets'],key=lambda a:a['name']) if a['name']!='SHA256SUMS.txt'),encoding='utf-8')
current_sum=next(a for a in after['assets'] if a['name']=='SHA256SUMS.txt');old_sum=next(a for a in before['assets'] if a['name']=='SHA256SUMS.txt')
assert current_sum['digest'] in (old_sum['digest'],'sha256:'+p.sha(sums)),'Concurrent sums update'
if current_sum['digest']!='sha256:'+p.sha(sums):subprocess.run([GH,'release','upload',CAT,str(sums),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
after=p.api('releases/tags/'+CAT);p.save(STAGE/'catalogue-after.json',after)
assert p.api('releases/latest')['id']==p.read(STAGE/'latest-before.json')['id']
results=[]
for row in files+[{'name':source.name,'sha256':p.sha(source)}]:
    a=next(a for a in after['assets'] if a['name']==row['name'])
    results.append(p.public_bytes(a,row['sha256']));print('Public bytes verified '+row['name'],flush=True)
receipt.update(state='PUBLISHED_CSQTT25_ALL_ABIS_PUBLIC_BYTES_VERIFIED',published=True,versionCode=7,moduleVersion='2.5.1-swgpanel-abi.7',files=results,unrelatedAssetsPreserved=True,phoneUntouched=True,deviceRuntimeTested=False)
p.save(receipt_path,receipt)
print('CSQTT25_ALL_ABIS_PUBLISHED_VERIFIED',flush=True)
