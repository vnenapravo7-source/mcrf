"""Freeze accepted preview21 + only the P0-47 picker change, never live P2."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path('D:/MCRF/Builds/Android/preview21-6f19501d734e48e19f543bda2b5f1265')
STAGE=Path('D:/MCRF/Builds/Android/p047-eacb79c431714020bd9621836dcc819f')
K='ports/android/app/src/main/kotlin/org/mcrf/client/'
T='ports/android/app/src/test/kotlin/org/mcrf/client/'
CHANGED={K+'AppPicker.kt',K+'RoutingPage.kt'}
ADDED={K+'AppPickerLoadGate.kt',T+'AppPickerLoadGateTest.kt'}

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def method(text):
    first=text.index('    private fun chooseGlobalApps(')
    last=text.index('    private fun find(',first)
    return first,last,text[first:last]

def main():
    assert not STAGE.exists(),'Never overwrite an existing verification snapshot'
    receipt=json.loads((PARENT/'build-receipt.json').read_text('utf-8-sig'))
    assert receipt['state']=='PASS_RELEASE21_R8_AND_SEVEN_NEW_CHANNEL_CASES'
    assert sha(PARENT/'SOURCE_MANIFEST.json')==receipt['sourceManifestSha256']
    rows=json.loads((PARENT/'SOURCE_MANIFEST.json').read_text('utf-8-sig'))
    for row in rows:
        src=PARENT/'source'/row['path']
        assert not src.is_symlink() and sha(src)==row['sha256'],row['path']
    p2=json.loads((ROOT/'ports/android/coordination/p2-progress-2026-10-20.json').read_text('utf-8-sig'))
    for key in ['sourceReady','readyForBuild','allowCommonVerification','allowApkBuild','allowNativeCandidateBuild']:
        assert p2[key] is False,key
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    assert not any(live['jobs'].values()),'An existing job must be resumed, not duplicated'
    source=STAGE/'source';source.mkdir(parents=True)
    for row in rows:
        dest=source/row['path'];dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(PARENT/'source'/row['path'],dest)
    # RoutingPage also has unfinished P2 changes. Copy ONLY the one edited method.
    target=source/K/'RoutingPage.kt';base=target.read_text('utf-8')
    start,end,_=method(base);_,_,replacement=method((ROOT/K/'RoutingPage.kt').read_text('utf-8'))
    target.write_text(base[:start]+replacement+base[end:],encoding='utf-8',newline='\n')
    assert 'RouteServerPicker' not in target.read_text('utf-8')
    for rel in sorted((CHANGED|ADDED)-{K+'RoutingPage.kt'}):
        dest=source/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/rel,dest)
    manifest=[]
    for row in rows:
        dest=source/row['path'];digest=sha(dest)
        if row['path'] not in CHANGED:assert digest==row['sha256'],row['path']
        else:assert digest!=row['sha256'],row['path']
        manifest.append({'path':row['path'],'sha256':digest})
    manifest.extend({'path':rel,'sha256':sha(source/rel)} for rel in sorted(ADDED))
    (STAGE/'SOURCE_MANIFEST.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    (STAGE/'preparation.json').write_text(json.dumps({'parent':str(PARENT),'parentManifestSha256':sha(PARENT/'SOURCE_MANIFEST.json'),
        'manifestSha256':sha(STAGE/'SOURCE_MANIFEST.json'),'changed':sorted(CHANGED),'added':sorted(ADDED),
        'excludedP2':True,'apkBuildAllowed':False,'phoneAllowed':False,'nativeBuildAllowed':False},indent=2),encoding='utf-8')
    print(json.dumps({'stage':str(STAGE),'inputs':len(manifest),'changed':4,'excludedP2':True}))

if __name__=='__main__':main()
