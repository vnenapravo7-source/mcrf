"""Commit only prepared, byte-verified Android source allowlist onto current main."""
import hashlib,json,subprocess
from pathlib import Path
S=Path('D:/MCRF/Builds/Android/release06-publication-20261023');GH='C:/Program Files/GitHub CLI/gh.exe'
p=json.loads((S/'source-plan.json').read_text(encoding='utf-8'));repo=Path(p['checkout'])
def git(*a,raw=False):
    b=subprocess.check_output(['git','-C',str(repo),*a]);return b if raw else b.decode('utf-8').strip()
def remote():return subprocess.check_output([GH,'api','repos/vnenapravo7-source/mcrf/git/ref/heads/main','--jq','.object.sha'],text=True).strip()
assert git('rev-parse','HEAD')==p['baseCommit']==remote(),'Remote advanced: inspect rather than overwrite'
assert git('remote','get-url','origin')=='https://github.com/vnenapravo7-source/mcrf.git'
assert not git('diff','--cached','--name-only')
names=[]
for row in p['files']:
    name=row['path'];f=repo/name
    assert not f.is_symlink() and hashlib.sha256(f.read_bytes()).hexdigest()==row['sha256']
    assert not set(Path(name).parts)&{'private','coordination','.git'}
    assert name in ('README.md','.gitattributes','docs/ANDROID-GUIDE.md') or name.startswith(('ports/android/','ports/catalogue/','ports/fixtures/','work/steer-android-port/'))
    names.append(name)
spec=S/'source-pathspec.bin';spec.write_bytes(b'\0'.join(x.encode() for x in names)+b'\0')
subprocess.run(['git','-C',str(repo),'--literal-pathspecs','add','-f','--pathspec-from-file='+str(spec),'--pathspec-file-nul'],check=True)
staged={x for x in git('diff','--cached','--name-only','-z',raw=True).decode().split('\0') if x}
assert staged and staged<=set(names) and not git('diff','--cached','--diff-filter=D','--name-only')
for row in p['files']:
    if row['path'] in staged:assert hashlib.sha256(git('show',':'+row['path'],raw=True)).hexdigest()==row['sha256'],'Git byte conversion changed exact source'
assert remote()==p['baseCommit']
with (S/'source-commit.log').open('wb') as log:subprocess.run(['git','-C',str(repo),'-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit','-m','feat(android): release 0.6 P2 routing, Vk Turn, CSQTT generations and autodeploy'],check=True,stdout=log)
commit=git('rev-parse','HEAD');subprocess.run(['git','-C',str(repo),'push','origin','HEAD:main'],check=True)
assert remote()==commit
receipt={'state':'SOURCE_COMMIT_PUSHED_VERIFIED','commit':commit,'parent':p['baseCommit'],'changedFiles':len(staged),'windowsSourceChanged':False,'windows11Preserved':True,'exactGitBlobBytesVerified':True}
(S/'source-publication-receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8');print(json.dumps(receipt))
