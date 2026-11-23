"""Repair the three sing-box delivery packages, retaining exact runtime bytes."""
import hashlib,json,shutil,subprocess,urllib.request,zipfile
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF');S=Path('D:/MCRF/Builds/Android/singbox10-delivery-fix-db1e29c8858c43f5bc0702033b6ce73d')
P=Path('D:/MCRF/Builds/Android/engine-delivery-docs-public-20261023');GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf';TAG='engines-2026-10-13-1'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def api(p,*a):return json.loads(subprocess.check_output([GH,'api','repos/'+REPO+'/'+p,*a]))
def git(*a,raw=False):
    b=subprocess.check_output(['git','-C',str(P),*a]);return b if raw else b.decode('utf-8').strip()
proof=read(S/'verification-receipt.json');runtime=read(Path('D:/MCRF/Builds/Android/delivery-checks-59706295a62c481cb7ff24d714e84d71/receipt.json'))
assert proof['state']=='PASS_DELIVERY_METADATA_SIGNER_RUNTIME_IDENTITY' and proof['adapterAndNativeBytesUnchanged']
assert runtime['state']=='PASS_DELIVERY_RUNTIME' and runtime['checks']==13 and runtime['protectedUnchanged'] and runtime['mainUnchanged'] and runtime['helperRemoved']
assets=S/'assets';backup=S/'catalogue-backup';assets.mkdir(exist_ok=True);backup.mkdir(exist_ok=True)
rows=[]
for row in proof['artifacts']:
    f=Path(row['path']);assert sha(f)==row['sha256'];name=f"mcrf-engine-sing-box-{row['abi']}-release.apk";shutil.copyfile(f,assets/name)
    rows.append(dict(name=name,bytes=f.stat().st_size,sha256=sha(f),label='MCRF sing-box delivery format fix; mcrf-version-code=10'))
files={}
for base in ('p2-route-dispatcher','primary-adapter','primary-spi'):
    d=S/'source/ports/android'/base
    for f in d.rglob('*'):
        rel=f.relative_to(S/'source').as_posix()
        if f.is_file() and '/build/' not in rel and '/.gradle/' not in rel:files[rel]=f
native=Path('D:/MCRF/Builds/Android/p2-singbox-route-source-2f8e34d8ca304776b0b0cb3eb452f1f1')
for name in ('core.tar','submodules-gvisor.tar','submodules-sing-tun.tar','submodules-utls.tar','submodules-wireguard-go.tar'):files['native/'+name]=native/name
for name in ('source-manifest.json','api-compatibility.json'):files['native/'+name]=native/name
zip_path=assets/'mcrf-sing-box-10-delivery-source.zip'
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as z:
    manifest=[]
    for name,f in sorted(files.items()):z.write(f,name);manifest.append({'path':name,'sha256':sha(f),'bytes':f.stat().st_size})
    z.writestr('CORRESPONDING_SOURCE_MANIFEST.json',json.dumps(manifest,indent=2))
rows.append(dict(name=zip_path.name,bytes=zip_path.stat().st_size,sha256=sha(zip_path),label='Exact sing-box10 delivery sources; runtime unchanged from9'))
source_receipt=S/'source-publication.json'
if source_receipt.exists():commit=read(source_receipt)['commit']
else:
    parent=git('rev-parse','HEAD');assert api('git/ref/heads/main')['object']['sha']==parent
    names=['ports/android/p2-route-dispatcher/build.gradle.kts','ports/android/p2-route-dispatcher/src/main/AndroidManifest.xml','README.md']
    for name in names[:2]:(P/name).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,P/name)
    for name in names:subprocess.run(['git','-C',str(P),'add','--',name],check=True)
    staged=set(git('diff','--cached','--name-only').splitlines());assert staged==set(names)
    for name in names:assert hashlib.sha256(git('show',':'+name,raw=True)).hexdigest()==sha(P/name)
    subprocess.run(['git','-C',str(P),'-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit','-m','fix(android): restore sing-box download format marker and clarify completed roadmap'],check=True,stdout=(S/'source-commit.log').open('wb'))
    commit=git('rev-parse','HEAD');subprocess.run(['git','-C',str(P),'push','origin','HEAD:main'],check=True)
    assert api('git/ref/heads/main')['object']['sha']==commit;save(source_receipt,{'state':'SOURCE_PUSHED_VERIFIED','commit':commit,'parent':parent,'files':names})
before_file=backup/'before.json'
if before_file.exists():before=read(before_file)
else:before=api('releases/tags/'+TAG);save(before_file,before)
names={r['name'] for r in rows}|{'packages.json','SHA256SUMS.txt'};protected=[a for a in before['assets'] if a['name'] not in names]
latest=api('releases/latest')
for a in before['assets']:
    if a['name'] in names:
        p=backup/a['name']
        if not p.exists():subprocess.run([GH,'release','download',TAG,'--repo',REPO,'--pattern',a['name'],'--dir',str(backup)],check=True,stdout=subprocess.DEVNULL)
        assert sha(p)==a['digest'].removeprefix('sha256:') and p.stat().st_size==a['size']
subprocess.run([GH,'release','upload',TAG,*[str(assets/r['name']) for r in rows],'--repo',REPO,'--clobber'],check=True,stdout=(S/'upload.log').open('ab'))
current=api('releases/tags/'+TAG)
for row in rows:
    a=next(a for a in current['assets'] if a['name']==row['name']);assert a['digest']=='sha256:'+row['sha256'] and a['size']==row['bytes']
    api('releases/assets/'+str(a['id']),'--method','PATCH','-f','label='+row['label'])
    with urllib.request.urlopen(urllib.request.Request(a['browser_download_url'],headers={'User-Agent':'MCRF-delivery-fix','Cache-Control':'no-cache'}),timeout=120) as f:
        h=hashlib.sha256();count=0
        while chunk:=f.read(1024*1024):h.update(chunk);count+=len(chunk)
    assert h.hexdigest()==row['sha256'] and count==row['bytes']
current=api('releases/tags/'+TAG);meta=S/'metadata';meta.mkdir(exist_ok=True)
catalog=[{'name':a['name'],'label':a.get('label') or '', 'sha256':a['digest'].removeprefix('sha256:'),'bytes':a['size']} for a in current['assets'] if a['name'] not in ('packages.json','SHA256SUMS.txt')]
save(meta/'packages.json',{'schema':1,'release':TAG,'platforms':['android','windows'],'linux':'No Linux engine acceptance claimed','files':sorted(catalog,key=lambda x:x['name'])})
(meta/'SHA256SUMS.txt').write_text(''.join(r['sha256']+'  '+r['name']+'\n' for r in sorted(catalog+[{'name':'packages.json','sha256':sha(meta/'packages.json')}],key=lambda x:x['name'])),encoding='utf-8')
subprocess.run([GH,'release','upload',TAG,str(meta/'packages.json'),str(meta/'SHA256SUMS.txt'),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
final=api('releases/tags/'+TAG)
for old in protected:
    a=next(a for a in final['assets'] if a['name']==old['name']);assert (a['id'],a['digest'],a['size'])==(old['id'],old['digest'],old['size'])
assert api('releases/latest')['id']==latest['id']
save(S/'publication-receipt.json',{'state':'PUBLISHED_SINGBOX10_PUBLIC_URLS_VERIFIED','sourceCommit':commit,'mainApkUnchanged':True,'nativeAndAdapterBytesUnchanged':True,'newDeliveryChecks':13,'assets':rows,'otherAssetsUnchanged':True})
print('SINGBOX10_PUBLISHED_PUBLIC_URLS_VERIFIED; source='+commit)
