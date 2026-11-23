"""Prepare immutable accepted P1 release/source assets. Never imports live unfinished P2."""
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path('D:/MCRF/Projects/MCRF')
STAGE=Path('D:/MCRF/Builds/Android/preview21-6f19501d734e48e19f543bda2b5f1265')
SOURCE=STAGE/'source'
PARENT=ROOT/'work/android-integration-8ae976348b7848b2afa72a66f2d642bd'
PUBLIC=Path('D:/MCRF/Builds/Android/preview21-public-source-7d7ae0a7887c4f879a54c0aceacaf454')
STEER=Path('D:/MCRF/Builds/Android/p1-steer-candidate-f84fa510d8874bd7b89a09f4e1b06245')
STEER_SOURCE=Path('D:/MCRF/Builds/Android/p1s-d72c3cc7f677')
WG=ROOT/'ports/android/transports/wg-backend/build/candidate-fa6bcf3afe614832910274a122f55051'
WIREGUARD=ROOT/'work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go'
OLD=Path('D:/MCRF/Builds/Android/p0-preview20-release-054e5c14ec874c29b07cd9c5f1c4c7fe')
TAG='android-v0.1.0-preview.20261020.1'
ASSETS=STAGE/'assets'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def safe_name(name):
    assert not name.startswith('/') and '..' not in name.split('/') and '\\' not in name
    assert not re.search(r'(^|/)(\.git|private|local\.properties)(/|$)|\.(keystore|jks|dpapi|p12|enc)$',name,re.I), 'Private public-source path'
def copy(source,target,expected=None):
    assert source.is_file() and not source.is_symlink()
    if expected:assert sha(source)==expected, 'Immutable artifact/source drift: '+source.name
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():assert sha(target)==sha(source),'Prepared input changed; do not overwrite'
    else:shutil.copyfile(source,target)
def archive(name,entries):
    path=ASSETS/name
    if path.exists():
        with zipfile.ZipFile(path) as previous:
            manifest=json.loads(previous.read('CORRESPONDING_SOURCE_MANIFEST.json'))
            assert set(row['path'] for row in manifest)==set(entries)
            for row in manifest:
                assert sha(entries[row['path']])==row['sha256']
                with previous.open(row['path']) as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==row['sha256']
        return {'files':len(manifest),'sha256':sha(path),'retainedVerifiedArchive':True}
    manifest=[]
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as out:
        for entry,source in sorted(entries.items()):
            safe_name(entry);assert source.is_file() and not source.is_symlink()
            out.write(source,entry);manifest.append({'path':entry,'sha256':sha(source),'bytes':source.stat().st_size})
        out.writestr('CORRESPONDING_SOURCE_MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    with zipfile.ZipFile(path) as check:
        assert len(check.namelist())==len(manifest)+1
        for row in manifest:
            with check.open(row['path']) as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==row['sha256']
    return {'files':len(manifest),'sha256':sha(path)}

build=read(STAGE/'build-receipt.json')
assert build['state']=='PASS_RELEASE21_R8_AND_SEVEN_NEW_CHANNEL_CASES' and build['sourceStable'] and build['excludedP2']
assert sha(STAGE/'SOURCE_MANIFEST.json')==build['sourceManifestSha256']
assert sha(ROOT/'work/ports-test-2026-10-14/p1-final-11b3e3b2cbab492abf0cf4833a70b6ff/receipt.json')=='dce550cb578219785310f3e8f30dafe3b8b14c92db2714d4b080a74819162b3d'
assert read(ROOT/'ports/android/coordination/p2-progress-2026-10-20.json')['readyForBuild'] is False
assert read(Path('D:/MCRF/Builds/Android/real-tour-9be97b40b8e64d27b2a3b5941e016f83')/'recording-complete.json')['changedPreferenceKeys']==[]
ASSETS.mkdir(exist_ok=True)
labels={}
names={'universal':'mcrf-android-universal-release.apk','arm64-v8a':'arm64-v8a-release.apk',
       'armeabi-v7a':'armeabi-v7a-release.apk','x86_64':'androidApp-x86_64-release.apk'}
for abi,name in names.items():
    item=build['artifacts'][abi];copy(Path(item['path']),ASSETS/name,item['sha256'])
for item in read(PARENT/'p1-owner-artifacts.json')['externalCandidates']:
    kind=item['kind'];assert kind in ('steer','wg')
    filename=Path(item['path'].replace('\\','/')).name
    abi=next(a for a in ('arm64-v8a','armeabi-v7a','x86_64','universal') if '-'+a+'-' in filename)
    public_kind='wg-awg' if kind=='wg' else kind
    name=f'mcrf-engine-{public_kind}-{abi}-release.apk'
    copy(PARENT/item['path'].replace('\\','/'),ASSETS/name,item['sha256'])
    labels[name]=f'MCRF tested P1 module; mcrf-version-code={3 if kind=="wg" else 6}'
old=read(OLD/'plan.json')
for item in old['assets']:
    if item['name'].startswith('mcrf-engine-sing-box-') or item['name']=='mcrf-sing-box-7-source.zip':
        copy(OLD/'assets'/item['name'],ASSETS/item['name'],item['sha256'])
        if item['name'].endswith('.apk'):labels[item['name']]='MCRF unchanged tested sing-box; mcrf-version-code=7'

# Public tree and archive are explicit manifests, never a recursive copy of the live repository.
public={};main={}
for row in read(STAGE/'SOURCE_MANIFEST.json'):
    name=row['path'].replace('\\','/');file=SOURCE/name
    assert sha(file)==row['sha256']
    ordinary=name.startswith(('ports/android/','ports/catalogue/','ports/fixtures/'))
    spi=name.startswith('work/steer-android-port/android-vpn/')
    if not (ordinary or spi) or '/coordination/' in name or '/build/' in name:continue
    safe_name(name);main[name]=file
    if file.suffix.lower() not in ('.aar','.apk','.so'):
        public[name]=file
for name in ['README.md','docs/ANDROID-GUIDE.md','ports/android/README.md','ports/android/gradlew.bat']:
    public[name]=ROOT/name;main[name]=ROOT/name
public['docs/screenshots/android-tour.gif']=ROOT/'docs/screenshots/android-tour.gif'
public['.gitattributes']=ROOT/'.gitattributes'

producer=read(STEER/'producer-receipt.json');assert producer['sourceStable']
steer={}
for name,item in producer['inputs'].items():
    if not name.startswith('work/steer-android-port/'):continue
    file=STEER_SOURCE/name;assert sha(file)==item['sha256']
    steer[name]=file
    assert name not in public or sha(public[name])==sha(file), 'Adapter/SPI differs from accepted main inputs'
    public[name]=file
# Include the reviewed exact packaged adapter/SPI and dependency licenses.
for name,file in main.items():
    if name.startswith(('work/steer-android-port/','ports/android/engines/','ports/android/primary-spi/')):
        steer.setdefault(name,file)
deps=STEER_SOURCE/'cxx/RelWithDebInfo/4s3fc6m9/arm64-v8a/_deps'
for dep in ('wolfssl-src','ngtcp2-src'):
    dep_root=deps/dep;assert dep_root.is_dir()
    for file in dep_root.rglob('*'):
        if (file.is_file() and '.git' not in file.parts and not file.is_symlink()
            and file.suffix.lower() not in ('.p12','.jks','.keystore','.enc','.pem','.der','.key','.crt')
            and 'private' not in file.relative_to(dep_root).parts):
            steer['third-party/'+dep+'/'+file.relative_to(dep_root).as_posix()]=file

wg={}
wg_receipt=read(WG/'receipt.json')
for item in wg_receipt['frozenInputs']:
    file=WG/'source'/item['name'];assert sha(file)==item['sha256']
    name='ports/android/transports/wg-backend/'+item['name'];wg[name]=file;public[name]=file
assert subprocess.check_output(['git','-C',str(WIREGUARD),'rev-parse','HEAD'],text=True).strip()==wg_receipt['upstreamCommit']
subprocess.run(['git','-C',str(WIREGUARD),'diff','--quiet','HEAD','--'],check=True)
for name in subprocess.check_output(['git','-C',str(WIREGUARD),'ls-files','-z']).decode().split('\0'):
    if name:
        file=WIREGUARD/name;assert not file.is_symlink()
        wg['wireguard-go/'+name]=file
for name,file in main.items():
    if name.startswith('ports/android/wg-module/'):wg[name]=file
wg['wg-backend-sources.jar']=WG/'wg-backend-sources.jar'

archives={}
archives['mcrf-android-source.zip']=archive('mcrf-android-source.zip',main)
archives['mcrf-steer-6-source.zip']=archive('mcrf-steer-6-source.zip',steer)
archives['mcrf-wg-awg-3-source.zip']=archive('mcrf-wg-awg-3-source.zip',wg)
public_rows=[]
for name,file in sorted(public.items()):
    safe_name(name);target=PUBLIC/name
    target.parent.mkdir(parents=True,exist_ok=True)
    # New staging checkout only. Never writes to canonical P2 sources or another worktree.
    shutil.copyfile(file,target)
    public_rows.append({'path':name,'sha256':sha(file),'bytes':file.stat().st_size})
manifest_name='ports/android/RELEASE-SOURCE-MANIFEST.json'
save(PUBLIC/manifest_name,{'versionCode':10021,'tag':TAG,'excludedP2':True,'files':public_rows})
public_rows.append({'path':manifest_name,'sha256':sha(PUBLIC/manifest_name),'bytes':(PUBLIC/manifest_name).stat().st_size})
save(STAGE/'public-source-plan.json',{'checkout':str(PUBLIC),'files':public_rows})
evidence={'versionCode':10021,'versionName':build['versionName'],'developmentSigned':True,
          'baseNativeLibraries':0,'excludedUnfinishedP2':True,'releaseTests':build['releaseTests'],
          'acceptedP1':{'mandatoryOwnedGatesPassed':31,'device':'Samsung','nativeModulesUnchanged':True,
                       'scope':'Supported Steer rules/DNS/authenticated loopback HTTP+SOCKS/IPv6; compact WG/AWG3.0/3.1 IP/BLOCK/managed DNS',
                       'universalAcceptance':False},
          'new10021InstalledOnPhone':False,'realGifRecordedOnInstalledAcceptedP1':True,
          'sourceArchives':archives,'signer':'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0',
          'limits':['Vivo user verification','Full physical IPv4/IPv6 route matrix remains open',
                    'Manual sidebar-fastest full matrix remains open','P046 source checked; actual startup scenario not accepted',
                    'No unfinished P2 code or arbitrary-provider/architecture acceptance claim']}
save(ASSETS/'BUILD_EVIDENCE.json',evidence)
(ASSETS/'SHA256SUMS.txt').write_text(''.join(sha(file)+'  '+file.name+'\n' for file in sorted(ASSETS.iterdir()) if file.name!='SHA256SUMS.txt'),encoding='utf-8')
plan={'tag':TAG,'versionCode':10021,'assets':[{'name':file.name,'bytes':file.stat().st_size,'sha256':sha(file),'label':labels.get(file.name,'')}
                   for file in sorted(ASSETS.iterdir())]}
save(STAGE/'publication-plan.json',plan)
print(json.dumps({'prepared':str(STAGE),'assets':len(plan['assets']),'publicSourceFiles':len(public_rows),'archives':archives}))
