"""Publish only the reviewed documentation/media allowlist; no live P2 sources."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from PIL import Image

ROOT=Path('D:/MCRF/Projects/MCRF')
CLONE=Path('D:/MCRF/Builds/Android/preview21-public-source-7d7ae0a7887c4f879a54c0aceacaf454')
FRESH=Path('D:/MCRF/Builds/Android/real-gallery-08bf329721364695b5237b714761aef0')
GIT='C:/Program Files/Git/cmd/git.exe'
GH='C:/Program Files/GitHub CLI/gh.exe'
DOCS=['README.md','docs/ANDROID-GUIDE.md','docs/DEVELOPMENT.md','docs/releases/1.0.11.md','ports/android/README.md']
GIFS=['connection','appearance','functionality','sections']
BASE=['home','connected','servers','bs','routes','settings','dns','appearance','light','vpn-proxy','rule-editor']
FRESH_NAMES=['warp','autodeploy-current','background','geodata','engines','advanced','logs-current',
             'sidebar','minimalism','colors','icons']

def git(*args):
    return subprocess.run([GIT,*args],cwd=CLONE,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout.decode().strip()

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    receipt=json.loads((FRESH/'receipt.json').read_text(encoding='utf-8'))
    assert receipt['state']=='CAPTURE_FINISHED_UNCHANGED'
    # New shots have already been visually reviewed before this publisher runs.
    for name in FRESH_NAMES:
        source=FRESH/f'android-{name}.png'
        with Image.open(source) as img:
            assert img.size==(1080,2640)
            img.resize((540,1320),Image.Resampling.LANCZOS).save(ROOT/f'docs/screenshots/android-{name}.png',optimize=True)
    paths=DOCS+[f'docs/screenshots/android-{x}.gif' for x in GIFS]+[
        f'docs/screenshots/android-{x}.png' for x in BASE+FRESH_NAMES]
    for doc in DOCS:
        value=(ROOT/doc).read_text(encoding='utf-8')
        # Verify all referenced local files before publication, including relative guide links.
        for match in re.findall(r'(?:src="([^"]+)"|\]\(([^)]+)\))',value):
            target=next(x for x in match if x).split('#')[0]
            if not target or '://' in target:continue
            assert ((ROOT/doc).parent/target).exists(),f'Missing local link {doc}: {target}'
    readme=(ROOT/'README.md').read_text(encoding='utf-8')
    assert readme.count('<img src="docs/screenshots/android-')==4
    assert 'Готовность | Проверка' not in readme and 'Не проверено' not in readme
    headings=[readme.index(f'### П{i}') for i in range(5)]
    assert headings==sorted(headings)
    for name in GIFS:
        with Image.open(ROOT/f'docs/screenshots/android-{name}.gif') as gif:
            assert gif.is_animated and gif.size==(360,880)
    assert not git('status','--porcelain'),'Publication clone must be clean'
    git('fetch','origin','main');git('merge','--ff-only','origin/main');base=git('rev-parse','HEAD')
    for path in paths:
        dest=CLONE/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,dest)
    git('add','--',*paths)
    staged=git('diff','--cached','--name-only').splitlines()
    assert set(staged)==set(paths),'Unexpected staged files or unchanged allowlist entry'
    git('diff','--cached','--check')
    git('-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com',
        'commit','-m','docs: split Android gallery, simplify priority roadmap and engine guide')
    commit=git('rev-parse','HEAD');git('push','origin','HEAD:main')
    remote=json.loads(subprocess.run([GH,'api','repos/vnenapravo7-source/mcrf/git/trees/'+commit+'?recursive=1'],
            check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout)
    assert not remote.get('truncated')
    blobs={x['path']:x['sha'] for x in remote['tree'] if x['type']=='blob'}
    assert all(blobs[path]==git('hash-object',str(ROOT/path)) for path in paths)
    head=json.loads(subprocess.run([GH,'api','repos/vnenapravo7-source/mcrf/git/ref/heads/main'],
            check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout)['object']['sha']
    assert head==commit
    result={'state':'DOCS_GALLERY_PUSHED_VERIFIED','base':base,'commit':commit,'files':len(paths),
            'gifs':len(GIFS),'screenshots':len(BASE+FRESH_NAMES),'codeChanged':False,'releaseAssetsChanged':False,
            'outputs':[{'path':p,'sha256':sha(ROOT/p)} for p in paths]}
    (FRESH/'publication-receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='outputs'}))

if __name__=='__main__':main()
