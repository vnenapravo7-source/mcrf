"""Seven explicit docs/media files only; source and release assets stay frozen."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from PIL import Image

ROOT=Path('D:/MCRF/Projects/MCRF')
CLONE=Path('D:/MCRF/Builds/Android/preview21-public-source-7d7ae0a7887c4f879a54c0aceacaf454')
STAGE=Path('D:/MCRF/Builds/Android/human-videos-20261020')
GIT='C:/Program Files/Git/cmd/git.exe'
GH='C:/Program Files/GitHub CLI/gh.exe'
DOCS=['README.md','docs/ANDROID-GUIDE.md','docs/DEVELOPMENT.md']
NAMES=['connection','appearance','functionality','sections']
GIFS=[f'docs/screenshots/android-{n}.gif' for n in NAMES]
PATHS=DOCS+GIFS

def git(*args):
    result=subprocess.run([GIT,*args],cwd=CLONE,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    return result.stdout.decode('utf-8').strip()

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    assert not (STAGE/'publication.json').exists(),'Inspect prior attempt before retry'
    receipt=json.loads((STAGE/'compact/encoding-receipt.json').read_text('utf-8'))
    assert receipt['humanSupplied'] and receipt['secretsEditorExcluded'] and receipt['sourcePlaybackSpeed']==1
    rows=receipt['files'];assert len(rows)==4
    for row in rows:
        src=STAGE/'compact'/(row['name']+'.gif')
        assert sha(src)==row['sha256']
        # No kept frame may fall within the credential-editor interval.
        if row['source']=='2222.mp4':
            assert all(end<=16 or start>=22 for start,end in row['cuts'])
        with Image.open(src) as gif:
            assert gif.is_animated and gif.size==(360,880) and gif.n_frames==row['frames']
            for i in range(gif.n_frames):gif.seek(i);gif.load()
        shutil.copyfile(src,ROOT/'docs/screenshots'/(row['name']+'.gif'))
    for doc in DOCS:
        value=(ROOT/doc).read_text('utf-8')
        for match in re.findall(r'(?:src="([^"]+)"|\]\(([^)]+)\))',value):
            target=next(x for x in match if x).split('#')[0]
            if target and '://' not in target:assert ((ROOT/doc).parent/target).exists(),target
    readme=(ROOT/'README.md').read_text('utf-8')
    assert '| Финальный |' not in readme
    assert readme.count('github.com/masterking32/MasterDnsVPN')==1 and '| П4 | №48:' in readme
    assert 'Легковесный' in readme and '| Размер загрузки |' in readme
    assert len(re.findall(r'^\| (?:П[0-4]|Вне плана) \|',readme,re.M))==36
    for doc in DOCS[:2]:
        value=(ROOT/doc).read_text('utf-8');images=[line for line in value.splitlines() if '.gif"' in line]
        assert len(images)==1 and images[0].count('<img ')==4,'One four-column GIF row required'
    assert not git('status','--porcelain'),'Public clone has existing changes'
    git('fetch','origin','main');git('merge','--ff-only','origin/main');base=git('rev-parse','HEAD')
    assert base=='28cddfe4aebbf27bd1acf9dc6162ae1a009ce5e0','Human remote edits require review before copying'
    for path in PATHS:
        dest=CLONE/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,dest)
    git('add','--',*PATHS)
    assert set(git('diff','--cached','--name-only').splitlines())==set(PATHS)
    git('diff','--cached','--check')
    git('-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com',
        'commit','-m','docs: use supplied Android tours, module sizes and revised roadmap')
    head=git('rev-parse','HEAD');git('push','origin','HEAD:main')
    actual=json.loads(subprocess.run([GH,'api',f'repos/vnenapravo7-source/mcrf/git/trees/{head}?recursive=1'],
        check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout)
    tree={row['path']:row['sha'] for row in actual['tree']}
    assert not actual.get('truncated')
    for path in PATHS:assert git('hash-object','--',path)==tree[path],path
    assert not git('status','--porcelain')
    result={'state':'PUBLISHED_VERIFIED','base':base,'commit':head,'files':PATHS,
        'sha256':{path:sha(ROOT/path) for path in PATHS},'phoneChanged':False,'apkBuilt':False,
        'releaseChanged':False,'p2Published':False,'credentialIntervalExcluded':True,'gifsAdjacent':True}
    (STAGE/'publication.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({'state':result['state'],'commit':head,'files':len(PATHS)}))

if __name__=='__main__':main()
