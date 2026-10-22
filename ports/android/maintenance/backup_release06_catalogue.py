"""Back up exact overwritten engine assets; read-only against GitHub."""
import hashlib,json,subprocess
from pathlib import Path
GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf'
STAGE=Path('D:/MCRF/Builds/Android/release06-publication-20261023')
BACKUP=Path('D:/MCRF/Builds/Android/release06-catalogue-backup-20261022')
def api(path):return json.loads(subprocess.check_output([GH,'api','repos/'+REPO+'/'+path]))
def save(p,v):p.write_text(json.dumps(v,indent=2,ensure_ascii=False),encoding='utf-8')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
plan=json.loads((STAGE/'plan.json').read_text(encoding='utf-8'))
current=api('releases/tags/engines-2026-10-13-1');latest=api('releases/latest')
assert current['id']==411713792 and latest['tag_name']=='v1.1'
before=BACKUP/'catalogue-before.json'
if before.exists():
    old=json.loads(before.read_text(encoding='utf-8'))
    assert [(x['id'],x['name'],x.get('digest')) for x in old['assets']]==[(x['id'],x['name'],x.get('digest')) for x in current['assets']], 'Catalogue advanced before backup; inspect changes'
else:save(before,current);save(BACKUP/'windows-latest-before.json',latest)
names={x['name'] for x in plan['engines']}|{'packages.json','SHA256SUMS.txt'}
old=[x for x in current['assets'] if x['name'] in names]
args=[GH,'release','download',current['tag_name'],'--repo',REPO,'--dir',str(BACKUP)]
for x in old:
    p=BACKUP/x['name']
    if p.exists():assert sha(p)=='%s'%x['digest'].removeprefix('sha256:') and p.stat().st_size==x['size'], 'Existing recovery download mismatches live asset'
    else:args+=['--pattern',x['name']]
if '--pattern' in args:subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
for x in old:
    p=BACKUP/x['name'];assert sha(p)==x['digest'].removeprefix('sha256:') and p.stat().st_size==x['size']
save(BACKUP/'backup-receipt.json',{'state':'PASS_RECOVERY_BACKUP','assets':len(old),'catalogueId':current['id'],'windowsLatest':latest['tag_name'],'remoteWrites':False})
print('CATALOGUE_RECOVERY_VERIFIED='+str(len(old)))
