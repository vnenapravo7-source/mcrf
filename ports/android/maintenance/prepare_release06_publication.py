"""Prepare hash-bound release assets and a source-only allowlist; no remote writes."""
import hashlib,json,re,shutil,subprocess,zipfile
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
BASE=Path('D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e')
PRIMARY=Path('D:/MCRF/Builds/Android/p2-primary-release06-dfecc988d4a5433fbcd428501d77b36f')
OPTIONAL=Path('D:/MCRF/Builds/Android/p2-optional-release06-413efb66829146af8e0897522e648c33')
OUT=Path('D:/MCRF/Builds/Android/release06-publication-20261023')
REPO=Path('D:/MCRF/Builds/Android/release06-public-source-20261022')
GH='C:/Program Files/GitHub CLI/gh.exe'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def save(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def copy(a,b):
    b.parent.mkdir(parents=True,exist_ok=True)
    if b.exists():assert sha(a)==sha(b),'Prepared artifact changed'
    else:shutil.copyfile(a,b)
def source_safe(name):
    p=Path(name)
    return (not p.is_absolute() and '..' not in p.parts and
      not any(x in p.parts for x in ('.git','.gradle','target','node_modules','__pycache__','coordination','private')) and
      p.name not in ('local.properties','prepared-expansion-source.json') and
      p.suffix.lower() not in ('.so','.aar','.apk','.exe','.dll','.jks','.keystore','.dpapi','.enc','.p12','.pfx','.pyc','.o','.obj','.a','.class','.pem','.key','.der'))
def archive(name,entries):
    dest=OUT/'assets'/name;manifest=[]
    with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as z:
        for name,p in sorted(entries.items()):
            assert not p.is_symlink() and p.is_file()
            z.write(p,name);manifest.append({'path':name,'sha256':sha(p),'bytes':p.stat().st_size})
        z.writestr('CORRESPONDING_SOURCE_MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    with zipfile.ZipFile(dest) as z:
        for row in manifest:
            with z.open(row['path']) as f:assert hashlib.file_digest(f,'sha256').hexdigest()==row['sha256']
    return dest
def tree(entries,prefix,directory):
    assert directory.is_dir(),str(directory)
    for p in directory.rglob('*'):
        if p.is_file() and not p.is_symlink():
            rel=p.relative_to(directory).as_posix()
            if source_safe(rel) and 'build' not in Path(rel).parts:entries[prefix+'/'+rel]=p
OUT.mkdir(exist_ok=True);(OUT/'assets').mkdir(exist_ok=True)
receipts=[read(x/'verification-receipt.json') for x in (BASE,PRIMARY,OPTIONAL)]
assert [x['state'] for x in receipts]==['PASS_RELEASE_PACKAGING','PASS_PRIMARY_RELEASE_PACKAGING','PASS_OPTIONAL_RELEASE_PACKAGING']
assert all(x['sourceStable'] for x in receipts)
artifacts=[row for r in receipts for row in r['artifacts']]
assert len(artifacts)==31
assets=[]
for row in artifacts:
    p=Path(row['path']);assert sha(p)==row['sha256'] and p.stat().st_size==row['bytes']
    name='mcrf-android-universal-release.apk' if row['kind']=='main' else f"mcrf-engine-{row['kind']}-{row['abi']}-release.apk"
    copy(p,OUT/'assets'/name)
    assets.append({'name':name,'sha256':row['sha256'],'bytes':row['bytes'],'versionCode':row['versionCode'],'kind':row['kind'],'abi':row['abi'],'label':f"MCRF Android 0.6; mcrf-version-code={row['versionCode']}"})
public={};main={}
for row in read(BASE/'SOURCE_MANIFEST.json'):
    name=row['path'].replace('\\','/');p=BASE/'source'/name
    assert sha(p)==row['sha256']
    if (name.startswith(('ports/android/','ports/catalogue/','ports/fixtures/','work/steer-android-port/')) and source_safe(name) and
        '/build/' not in name and '/native-stage-' not in name and '/verification/' not in name):
        main[name]=p;public[name]=p
# All packaging overlays are metadata/gates only; accepted runtime sources remain frozen.
for name in ['ports/android/engines/build.gradle.kts','ports/android/wg-module/build.gradle.kts','ports/android/p2-route-dispatcher/build.gradle.kts']:
    main[name]=ROOT/name;public[name]=ROOT/name
for name in ['README.md','ports/android/README.md','docs/ANDROID-GUIDE.md','.gitattributes']:
    main[name]=REPO/name;public[name]=REPO/name
for p in (ROOT/'ports/android/maintenance').glob('*Release06*'):
    main[p.relative_to(ROOT).as_posix()]=p;public[p.relative_to(ROOT).as_posix()]=p
archive('mcrf-android-0.6-source.zip',main)
native={}
# Exact Steer producer input closure, including local JNI/adapter changes.
producer=read(Path('D:/MCRF/Builds/Android/p2-steer-candidate-d3992f56f32d41438c654d3d785162a1/producer-receipt.json'))
frozen=Path(producer['frozenSourceDirectory'])
for name,row in producer['inputs'].items():
    if source_safe(name) and not name.startswith('ports/android/coordination/'):
        p=frozen/name;assert sha(p)==row['sha256']
        native['steer/'+name]=p
        if name.startswith('work/steer-android-port/'):public[name]=p
deps=frozen/'cxx/RelWithDebInfo/3q3c2p3q/arm64-v8a/_deps'
for dep in ('wolfssl-src','ngtcp2-src','mcrf_brotli49-src','mcrf_zstd49-src'):tree(native,'steer/third-party/'+dep,deps/dep)
sing=Path('D:/MCRF/Builds/Android/p2-singbox-route-source-2f8e34d8ca304776b0b0cb3eb452f1f1')
for name in ('core.tar','submodules-gvisor.tar','submodules-sing-tun.tar','submodules-utls.tar','submodules-wireguard-go.tar'):native['sing-box/'+name]=sing/name
tree(native,'sing-box/direct-family',sing/'direct-family');tree(native,'sing-box/route-pipes',sing/'route-pipes')
wg=ROOT/'ports/android/transports/wg-backend/build/candidate-0a6d6707d0ad443595d82da34ea85e1a'
wg_receipt=read(wg/'receipt.json')
for row in wg_receipt['frozenInputs']:
    p=wg/'source'/row['name'];assert sha(p)==row['sha256'];native['wg-awg/'+row['name']]=p
native['wg-awg/wg-backend-sources.jar']=wg/'wg-backend-sources.jar'
native['wg-awg/upstream-wireguard-go.tar']=sing/'submodules-wireguard-go.tar'
for kind in ('csqtt','csqtt25','qwdtt','wdtt'):
    stage=BASE/'source/ports/android'/('native-stage-csqtt-legacy' if kind=='csqtt' else 'native-stage-'+kind)
    r=read(stage/'receipt.json');source=Path(r['source'])
    tree(native,'turn/'+kind+'/go',source/('wdtt' if kind=='wdtt' else 'qwdtt/qwdtt'))
    if r.get('legacyTransportStage'):
        old=Path(r['legacyTransportStage'])
        if (old/'receipt.json').exists():
            old_r=read(old/'receipt.json')
            for key in ('source','sourceDirectory'):
                if old_r.get(key) and Path(old_r[key]).is_dir():tree(native,'turn/'+kind+'/legacy-native',Path(old_r[key]));break
for directory in ('csqtt-historical-9037b6e108d84e17bd68e228b3d3f2dd','csqtt-historical-533a6b5342d0408bacd44d6497f6c23c'):
    d=Path('D:/MCRF/Builds/Android')/directory;r=read(d/'receipt.json');tree(native,'turn/'+r['engine']+'/rust',d/'source')
follow=Path('D:/MCRF/Builds/Android/p049-followup-4f3f833bd3094a1891c5f35701fdb439')
for row in read(follow/'optional-inputs.json'):
    p=follow/'native'/row['path'];assert sha(p)==row['sha256'];native['optional/'+row['path']]=p
tree(native,'optional/recipes',ROOT/'ports/android/optional-cores/licenses')
for name,p in main.items():
    if name.startswith(('ports/android/transports/','ports/android/engines/','ports/android/primary-','ports/android/wg-module/')):native['integration/'+name]=p
archive('mcrf-android-engines-0.6-source.zip',native)
native_asset=OUT/'assets/mcrf-android-engines-0.6-source.zip'
assets.append({'name':native_asset.name,'sha256':sha(native_asset),'bytes':native_asset.stat().st_size,'kind':'source','label':'MCRF Android 0.6 engine corresponding source and upstream inputs'})
public_rows=[]
for name,p in sorted(public.items()):
    assert source_safe(name)
    # Never transplant Windows files or private coordination into this checkout.
    assert name in ('README.md','.gitattributes','docs/ANDROID-GUIDE.md') or name.startswith(('ports/android/','ports/catalogue/','ports/fixtures/','work/steer-android-port/'))
    dest=REPO/name
    if p.resolve()!=dest.resolve():dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
    public_rows.append({'path':name,'sha256':sha(dest),'bytes':dest.stat().st_size})
save(OUT/'source-plan.json',{'checkout':str(REPO),'files':public_rows,'baseCommit':subprocess.check_output(['git','-C',str(REPO),'rev-parse','HEAD'],text=True).strip()})
copy(ROOT/'outputs/android-0.6-prerelease-notes-draft.txt',OUT/'NOTES.md')
save(OUT/'plan.json',{'tag':'android-v0.6','versionCode':10043,'versionName':'0.6','prerelease':False,'main':next(x for x in assets if x['kind']=='main'),'engines':[x for x in assets if x['kind']!='main'],'source':{'name':'mcrf-android-0.6-source.zip','sha256':sha(OUT/'assets/mcrf-android-0.6-source.zip'),'bytes':(OUT/'assets/mcrf-android-0.6-source.zip').stat().st_size}})
print(f'PREPARED: one main, {len(artifacts)-1} engine ABI APKs, two source archives; public source files={len(public_rows)}; no remote writes')
