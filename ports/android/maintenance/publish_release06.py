"""Publish ordinary Android 0.6, retaining Windows as global latest."""
import hashlib,json,subprocess,urllib.request
from pathlib import Path
S=Path('D:/MCRF/Builds/Android/release06-publication-20261023');GH='C:/Program Files/GitHub CLI/gh.exe';REPO='vnenapravo7-source/mcrf'
def gh(*a):return subprocess.check_output([GH,*a])
def api(p,*a):return json.loads(gh('api','repos/'+REPO+'/'+p,*a))
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def save(p,v):p.write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
plan=read(S/'plan.json');source=read(S/'source-publication-receipt.json');catalogue=read(S/'catalogue-publication-receipt.json')
assert source['state']=='SOURCE_COMMIT_PUSHED_VERIFIED' and source['windowsSourceChanged'] is False
assert catalogue['state']=='PUBLISHED_CATALOGUE_PUBLIC_DOWNLOADS_VERIFIED' and catalogue['assets']==31
assert plan['tag']=='android-v0.6' and plan['versionCode']==10043 and plan['prerelease'] is False
latest=api('releases/latest');assert not latest['tag_name'].startswith('android-')
rows=[plan['main'],plan['source']]
for row in rows:assert sha(S/'assets'/row['name'])==row['sha256'] and (S/'assets'/row['name']).stat().st_size==row['bytes']
checks=S/'assets/SHA256SUMS.txt';checks.write_text(''.join(x['sha256']+'  '+x['name']+'\n' for x in rows),encoding='utf-8')
rows.append({'name':checks.name,'sha256':sha(checks),'bytes':checks.stat().st_size})
receipt_path=S/'release-publication-receipt.json';receipt=read(receipt_path) if receipt_path.exists() else {'state':'PUBLISHING','sourceCommit':source['commit'],'published':False}
try:
    def find_release():
        query='query { repository(owner:"vnenapravo7-source", name:"mcrf") { release(tagName:"android-v0.6") { databaseId } } }'
        result=json.loads(gh('api','graphql','-f','query='+query))['data']['repository']['release']
        return api('releases/'+str(result['databaseId'])) if result else None
    release=find_release()
    if release is None:
        assert not receipt.get('releaseId'),'Previously created release was removed; do not recreate'
        subprocess.run([GH,'release','create',plan['tag'],'--repo',REPO,'--target',source['commit'],'--title','MCRF Android 0.6','--notes-file',str(S/'NOTES.md'),'--draft','--latest=false'],check=True,stdout=subprocess.DEVNULL)
        release=find_release()
    assert release is not None
    assert release['prerelease'] is False and release['target_commitish']==source['commit']
    receipt['releaseId']=release['id'];save(receipt_path,receipt)
    missing=[]
    for row in rows:
        old=next((x for x in release['assets'] if x['name']==row['name']),None)
        if old:assert old['digest']=='sha256:'+row['sha256'] and old['size']==row['bytes']
        else:missing.append(str(S/'assets'/row['name']))
    if missing:subprocess.run([GH,'release','upload',plan['tag'],*missing,'--repo',REPO],check=True,stdout=subprocess.DEVNULL)
    release=api('releases/'+str(release['id']));assert len(release['assets'])==3
    for row in rows:
        a=next(x for x in release['assets'] if x['name']==row['name']);assert a['digest']=='sha256:'+row['sha256'] and a['size']==row['bytes']
    if release['draft']:release=api('releases/'+str(release['id']),'--method','PATCH','-F','draft=false','-F','prerelease=false','-f','make_latest=false')
    assert release['draft'] is False and release['prerelease'] is False
    assert api('git/ref/tags/'+plan['tag'])['object']['sha']==source['commit']
    for row in rows:
        a=next(x for x in release['assets'] if x['name']==row['name'])
        with urllib.request.urlopen(urllib.request.Request(a['browser_download_url'],headers={'User-Agent':'MCRF-release-verifier'}),timeout=120) as f:
            h=hashlib.sha256();size=0
            while chunk:=f.read(1024*1024):h.update(chunk);size+=len(chunk)
        assert h.hexdigest()==row['sha256'] and size==row['bytes']
    assert api('releases/latest')['id']==latest['id'],'Global latest advanced; inspect, never roll back Windows'
    receipt.update(state='PUBLISHED_RELEASE06_PUBLIC_DOWNLOADS_VERIFIED',published=True,url=release['html_url'],versionCode=10043,prerelease=False,assetsVerified=3,mainNativeLibraries=0,windowsLatestUnchanged=latest['tag_name'])
except Exception:
    receipt['state']='FAILED_PRESERVED';save(receipt_path,receipt);raise
save(receipt_path,receipt);print(json.dumps(receipt))
