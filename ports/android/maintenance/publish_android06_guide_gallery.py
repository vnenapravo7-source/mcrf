"""Publish only the reviewed current guide and referenced native phone PNGs."""
import hashlib,json,re,shutil,struct,subprocess
from pathlib import Path
P=Path('D:/MCRF/Builds/Android/engine-delivery-docs-public-20261023');G=Path('D:/MCRF/Builds/Android/docs-gallery-20261023-06');GH='C:/Program Files/GitHub CLI/gh.exe'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def git(*a,raw=False):
    b=subprocess.check_output(['git','-C',str(P),*a]);return b if raw else b.decode('utf-8').strip()
def remote():return subprocess.check_output([GH,'api','repos/vnenapravo7-source/mcrf/git/ref/heads/main','--jq','.object.sha'],text=True).strip()
guide=P/'docs/ANDROID-GUIDE.md';text=guide.read_text(encoding='utf-8');names=re.findall(r'src="screenshots/([^"/]+\.png)"',text)
assert len(names)==len(set(names))==18 and all(x.endswith('-06.png') for x in names),'Only fresh reviewed PNGs in current guide'
assert 'android-servers-06.png' not in names and not any('private' in x for x in names)
assert not any(x in text for x in ('вариант своей архитектуры','находится в текущем плане П2','управления серверами находится в П4'))
assert text.count('<details>')==text.count('</details>')==1
files=['docs/ANDROID-GUIDE.md'];rows=[]
for name in names:
    p=G/name;header=p.read_bytes()[:24];assert header[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',header[16:24])==(1080,2640)
    dest=P/'docs/screenshots'/name;shutil.copyfile(p,dest);files.append('docs/screenshots/'+name)
    rows.append({'name':name,'sha256':sha(dest),'bytes':dest.stat().st_size,'nativePhoneCapture':True,'visualReviewed':True})
# Existing historical GIFs remain accessible, explicitly labelled as older UI.
for name in re.findall(r'src="screenshots/([^"/]+\.gif)"',text):assert (P/'docs/screenshots'/name).is_file()
parent=git('rev-parse','HEAD');assert parent==remote(),'Remote advanced; preserve it before publishing docs'
assert not git('diff','--cached','--name-only')
for name in files:subprocess.run(['git','-C',str(P),'add','--',name],check=True)
assert set(git('diff','--cached','--name-only').splitlines())==set(files)
for name in files:assert hashlib.sha256(git('show',':'+name,raw=True)).hexdigest()==sha(P/name),'Git transformed screenshot/guide bytes'
subprocess.run(['git','-C',str(P),'-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit','-m','docs(android): refresh 0.6 screenshots, Vk Turn, battery and autodeploy guide'],check=True,stdout=(G/'publication.log').open('wb'))
commit=git('rev-parse','HEAD');subprocess.run(['git','-C',str(P),'push','origin','HEAD:main'],check=True);assert remote()==commit
for name in files:
    public=subprocess.check_output([GH,'api','repos/vnenapravo7-source/mcrf/contents/'+name+'?ref='+commit,'--jq','.sha'],text=True).strip()
    assert public==git('rev-parse',commit+':'+name)
receipt={'state':'PUBLISHED_CURRENT_ANDROID06_GUIDE_AND_GALLERY_VERIFIED','commit':commit,'parent':parent,'screenshots':rows,'guideSha256':sha(guide),'windowsFilesChanged':False,'privateAndServerListFramesExcluded':True}
(G/'publication-receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
print('GUIDE_PUBLISHED_VERIFIED: '+str(len(names))+' real screenshots; commit='+commit)
