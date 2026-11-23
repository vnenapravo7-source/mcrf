"""Publish verified Android engines without touching Windows assets or latest."""
import concurrent.futures,hashlib,json,subprocess,urllib.request
from pathlib import Path
S=Path('D:/MCRF/Builds/Android/release06-publication-20261023');B=Path('D:/MCRF/Builds/Android/release06-catalogue-backup-20261022')
GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf';TAG='engines-2026-10-13-1'
def gh(*a):return subprocess.check_output([GH,*a])
def api(p,*a):return json.loads(gh('api','repos/'+REPO+'/'+p,*a))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def identity(a):return (a['id'],a['name'],a['size'],a.get('digest'),a.get('label'))
plan=json.loads((S/'plan.json').read_text(encoding='utf-8'));before=json.loads((B/'catalogue-before.json').read_text(encoding='utf-8'))
assert json.loads((B/'backup-receipt.json').read_text())['state']=='PASS_RECOVERY_BACKUP'
assert json.loads((S/'source-publication-receipt.json').read_text())['state']=='SOURCE_COMMIT_PUSHED_VERIFIED'
assert api('releases/latest')['tag_name']=='v1.1'
names={x['name'] for x in plan['engines']}|{'packages.json','SHA256SUMS.txt'}
protected=[a for a in before['assets'] if a['name'] not in names]
current=api('releases/tags/'+TAG);assert current['id']==before['id']==411713792
for old in protected:assert identity(next(x for x in current['assets'] if x['name']==old['name']))==identity(old),'Unrelated catalogue asset advanced; inspect'
pending=[]
for row in plan['engines']:
    p=S/'assets'/row['name'];assert sha(p)==row['sha256'] and p.stat().st_size==row['bytes']
    old=next((x for x in current['assets'] if x['name']==row['name']),None)
    if not old or old.get('digest')!='sha256:'+row['sha256']:pending.append(str(p))
if pending:subprocess.run([GH,'release','upload',TAG,*pending,'--repo',REPO,'--clobber'],check=True,stdout=(S/'catalogue-upload.log').open('ab'))
current=api('releases/tags/'+TAG)
for row in plan['engines']:
    a=next(x for x in current['assets'] if x['name']==row['name'])
    assert a['size']==row['bytes'] and a.get('digest')=='sha256:'+row['sha256'] and a['state']=='uploaded'
    if a.get('label')!=row['label']:api('releases/assets/'+str(a['id']),'--method','PATCH','-f','label='+row['label'])
current=api('releases/tags/'+TAG)
def verify(row):
    a=next(x for x in current['assets'] if x['name']==row['name'])
    request=urllib.request.Request(a['browser_download_url'],headers={'User-Agent':'MCRF-release-verifier','Cache-Control':'no-cache'})
    with urllib.request.urlopen(request,timeout=120) as f:
        h=hashlib.sha256();count=0
        while chunk:=f.read(1024*1024):h.update(chunk);count+=len(chunk)
    assert h.hexdigest()==row['sha256'] and count==row['bytes'],'Public download mismatch: '+row['name']
    return row['name']
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:verified=list(pool.map(verify,plan['engines']))
# The old packages file was stale. Rebuild from LIVE assets, including every
# unrelated/new Windows asset, rather than copying old hashes over their updates.
current=api('releases/tags/'+TAG)
rows=[{'name':a['name'],'label':a.get('label') or '', 'sha256':a['digest'].removeprefix('sha256:'),'bytes':a['size']} for a in current['assets'] if a['name'] not in ('packages.json','SHA256SUMS.txt')]
assert all(len(x['sha256'])==64 for x in rows)
metadata=S/'catalogue-metadata';metadata.mkdir(exist_ok=True)
save(metadata/'packages.json',{'schema':1,'release':TAG,'platforms':['android','windows'],'linux':'No Linux engine acceptance claimed','files':sorted(rows,key=lambda x:x['name'])})
checks=rows+[{'name':'packages.json','sha256':sha(metadata/'packages.json')}]
(metadata/'SHA256SUMS.txt').write_text(''.join(x['sha256']+'  '+x['name']+'\n' for x in sorted(checks,key=lambda x:x['name'])),encoding='utf-8')
subprocess.run([GH,'release','upload',TAG,str(metadata/'packages.json'),str(metadata/'SHA256SUMS.txt'),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
final=api('releases/tags/'+TAG)
for old in protected:assert identity(next(x for x in final['assets'] if x['name']==old['name']))==identity(old),'Unrelated catalogue asset changed'
for name in ('packages.json','SHA256SUMS.txt'):
    a=next(x for x in final['assets'] if x['name']==name);assert a['digest']=='sha256:'+sha(metadata/name)
assert api('releases/latest')['tag_name']=='v1.1'
save(S/'catalogue-publication-receipt.json',{'state':'PUBLISHED_CATALOGUE_PUBLIC_DOWNLOADS_VERIFIED','assets':len(verified),'publicUrlsVerified':verified,'windowsAssetsUnchanged':True,'windowsLatest':'v1.1','oldAssetsBackedUp':True})
print('CATALOGUE_PUBLISHED_AND_PUBLIC_DOWNLOADS_VERIFIED='+str(len(verified)))
