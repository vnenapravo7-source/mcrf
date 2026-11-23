"""Remove only superseded engine APK duplicates, after recovery/public gates."""
import hashlib,json,re,subprocess
from pathlib import Path
S=Path('D:/MCRF/Builds/Android/release06-publication-20261023');GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf'
def api(p,*a):return json.loads(subprocess.check_output([GH,'api','repos/'+REPO+'/'+p,*a]))
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
release=json.loads((S/'release-publication-receipt.json').read_text(encoding='utf-8'))
catalog=json.loads((S/'catalogue-publication-receipt.json').read_text(encoding='utf-8'))
assert release['state']=='PUBLISHED_RELEASE06_PUBLIC_DOWNLOADS_VERIFIED' and catalog['state']=='PUBLISHED_CATALOGUE_PUBLIC_DOWNLOADS_VERIFIED'
central=api('releases/tags/engines-2026-10-13-1')
completed=[]
for tag in ('android-v0.1.0-preview.20261020.1','android-v0.1.0-preview.20261019.2'):
    directory=S/'old-preview-engine-backups'/tag;directory.mkdir(parents=True,exist_ok=True)
    path=directory/'before.json';current=api('releases/tags/'+tag)
    if path.exists():before=json.loads(path.read_text(encoding='utf-8'))
    else:before=current;save(path,before)
    assert before['tag_name']==tag and before['prerelease'] is True
    engines=[x for x in before['assets'] if re.fullmatch(r'mcrf-engine-.+\.apk',x['name'])]
    protected=[x for x in before['assets'] if x not in engines]
    for old in engines:
        m=re.fullmatch(r'mcrf-engine-(.+)-(arm64-v8a|armeabi-v7a|x86_64|universal)-release\.apk',old['name']);assert m
        kind,abi=m.groups()
        assert any(a['name'].startswith('mcrf-engine-'+kind+'-') and a['name'].endswith('-release.apk') for a in central['assets']), 'Missing central replacement'
        p=directory/old['name']
        if not p.exists():subprocess.run([GH,'release','download',tag,'--repo',REPO,'--pattern',old['name'],'--dir',str(directory)],check=True,stdout=subprocess.DEVNULL)
        assert p.stat().st_size==old['size'] and sha(p)==old['digest'].removeprefix('sha256:')
    # Resolve exact current IDs once more after backups, before every deletion.
    for old in engines:
        current=api('releases/'+str(before['id']));found=[x for x in current['assets'] if x['id']==old['id']]
        if found:
            assert found[0]['name']==old['name'] and found[0]['digest']==old['digest'] and found[0]['size']==old['size']
            subprocess.run([GH,'api','--method','DELETE','repos/'+REPO+'/releases/assets/'+str(old['id'])],check=True,stdout=subprocess.DEVNULL)
    after=api('releases/'+str(before['id']))
    assert not any(re.fullmatch(r'mcrf-engine-.+\.apk',x['name']) for x in after['assets'])
    for old in protected:
        a=next(x for x in after['assets'] if x['id']==old['id']);assert (a['name'],a['digest'],a['size'])==(old['name'],old['digest'],old['size'])
    note='Движки перенесены в [единый каталог «Движки и транспорты»](https://github.com/vnenapravo7-source/mcrf/releases/tag/engines-2026-10-13-1).'
    if note not in after['body']:api('releases/'+str(before['id']),'--method','PATCH','-f','body='+after['body'].rstrip()+'\n\n'+note+'\n')
    completed.append({'tag':tag,'engineApksRemoved':len(engines),'mainAndSourcesPreserved':True,'recoveryDirectory':str(directory)})
save(S/'preview-engine-cleanup-receipt.json',{'state':'PASS_PREVIEW_ENGINE_DUPLICATES_REMOVED_RECOVERABLE','releases':completed})
print(json.dumps(completed))
