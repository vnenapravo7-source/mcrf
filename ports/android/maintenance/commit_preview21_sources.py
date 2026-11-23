"""Publish only the reviewed, byte-pinned release source allowlist to a fresh clone."""
import hashlib
import json
from pathlib import Path
import subprocess

STAGE=Path('D:/MCRF/Builds/Android/preview21-6f19501d734e48e19f543bda2b5f1265')
plan=json.loads((STAGE/'public-source-plan.json').read_text(encoding='utf-8'))
repo=Path(plan['checkout'])
def git(*args,raw=False):
    value=subprocess.check_output(['git','-C',str(repo),*args])
    return value if raw else value.decode('utf-8').strip()
assert git('rev-parse','HEAD')=='af2e7750b846ee02c506c17c090040d54dbf01b3'
assert git('remote','get-url','origin')=='https://github.com/vnenapravo7-source/mcrf.git'
assert subprocess.check_output(['gh','api','repos/vnenapravo7-source/mcrf/git/ref/heads/main','--jq','.object.sha'],text=True).strip()==git('rev-parse','HEAD'), 'Remote advanced; preserve it'
assert not git('diff','--cached','--name-only')
names=[]
for row in plan['files']:
    name=row['path'];file=repo/name
    assert not file.is_symlink() and hashlib.sha256(file.read_bytes()).hexdigest()==row['sha256']
    assert not any(x in name.split('/') for x in ['.git','coordination','private'])
    names.append(name)
spec=STAGE/'public-source-pathspec.bin';spec.write_bytes(b'\0'.join(s.encode() for s in names)+b'\0')
# /work is intentionally ignored by the Windows repository. Only these precise
# reviewed native/SPI SOURCE FILES are forced; no recursive work/build add.
subprocess.run(['git','-C',str(repo),'--literal-pathspecs','add','-f','--pathspec-from-file='+str(spec),'--pathspec-file-nul'],check=True)
staged={s for s in git('diff','--cached','--name-only','-z',raw=True).decode().split('\0') if s}
assert staged.issubset(set(names)) and len(staged)>1300
deleted=git('diff','--cached','--diff-filter=D','--name-only');assert not deleted
for row in plan['files']:
    if row['path'] in staged:
        blob=git('show',':'+row['path'],raw=True)
        assert hashlib.sha256(blob).hexdigest()==row['sha256'], 'Git line-ending conversion invalidated source contract'
subprocess.run(['git','-C',str(repo),'-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com',
                'commit','-m','feat(android): publish accepted P1 sources, roadmap and real Samsung UI tour'],check=True,
                stdout=(STAGE/'source-commit.log').open('wb'))
commit=git('rev-parse','HEAD')
subprocess.run(['git','-C',str(repo),'push','origin','HEAD:main'],check=True)
remote=subprocess.check_output(['gh','api','repos/vnenapravo7-source/mcrf/git/ref/heads/main','--jq','.object.sha'],text=True).strip()
assert remote==commit
receipt={'state':'SOURCE_COMMIT_PUSHED_VERIFIED','commit':commit,'parent':'af2e7750b846ee02c506c17c090040d54dbf01b3',
         'filesChanged':len(staged),'deletedFiles':0,'windowsSourceChanged':False,'excludedP2':True,'exactGitBlobBytesVerified':True}
(STAGE/'source-publication-receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
