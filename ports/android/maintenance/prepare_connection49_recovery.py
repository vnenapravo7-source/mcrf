"""Narrow recovery child of the tested P049-only snapshot, never the live P2 tree."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path('D:/MCRF/Builds/Android/p049-candidate-701f35cbd75f41dab40a97dafdca7cb0')
STAGE=Path('D:/MCRF/Builds/Android/p049-recovery-4b4fc542e5234a6b966455622d52a30e')
K='ports/android/app/src/main/kotlin/org/mcrf/client/'
T='ports/android/app/src/test/kotlin/org/mcrf/client/'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def once(value,old,new):
    assert value.count(old)==1,old[:100]
    return value.replace(old,new)
def main():
    assert not STAGE.exists()
    prior=json.loads((PARENT/'build.json').read_text('utf-8-sig'))
    assert prior['state']=='PASS_R8_CANDIDATE_AND_151_JVM' and prior['sourceStable']
    assert sha(PARENT/'SOURCE_MANIFEST.json')==prior['sourceManifestSha256']
    rows=json.loads((PARENT/'SOURCE_MANIFEST.json').read_text('utf-8'))
    dest=STAGE/'source';dest.mkdir(parents=True)
    for row in rows:
        source=PARENT/'source'/row['path'];assert sha(source)==row['sha256']
        target=dest/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    extra=[K+'WorkerSelfRetirement.kt',T+'WorkerSelfRetirementTest.kt']
    for rel in extra:shutil.copyfile(ROOT/rel,dest/rel)
    file=dest/K/'ClientPrimaryWorkerService.kt';text=file.read_text('utf-8')
    canonical=(ROOT/K/'ClientPrimaryWorkerService.kt').read_text('utf-8')
    start='    private fun armReaper() {';end='    private fun retireWorker() {'
    assert text.count(start)==canonical.count(start)==text.count(end)==canonical.count(end)==1
    text=text[:text.index(start)]+canonical[canonical.index(start):canonical.index(end)]+text[text.index(end):]
    text=once(text,'import android.os.ResultReceiver','import android.os.ResultReceiver\nimport android.os.SystemClock')
    file.write_text(text,encoding='utf-8',newline='\n')
    file=dest/K/'McrfVpnService.kt';text=file.read_text('utf-8')
    old='''                closeCore()
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                val primaryFailure=ClientPrimaryPreparationFailure.code(error)
                val explanation=primaryFailure?.let(ClientPrimaryPreparationFailure::message)
                    ?:ClientEvents.redact(error.message)
                status = "Ошибка подключения: $explanation"; ClientEvents.record(this, "Ошибка", status)'''
    new='''                val primaryFailure=ClientPrimaryPreparationFailure.code(error)
                val explanation=primaryFailure?.let(ClientPrimaryPreparationFailure::message)
                    ?:ClientEvents.redact(error.message)
                ClientEvents.record(this,"Ошибка","Ошибка подключения: $explanation")
                closeCore()
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                status = "Ошибка подключения: $explanation"'''
    text=once(text,old,new)
    assert 'NativeRoutePreparation' not in text and 'RouteServerPlan' not in text
    file.write_text(text,encoding='utf-8',newline='\n')
    file=dest/'ports/android/app/build.gradle.kts';text=file.read_text('utf-8')
    text=once(text,'versionCode = 10022','versionCode = 10023')
    text=once(text,'versionName = "0.1.0-preview.20261020.2"','versionName = "0.1.0-preview.20261020.3"')
    file.write_text(text,encoding='utf-8',newline='\n')
    files={row['path'] for row in rows}|set(extra)
    manifest=[{'path':rel,'sha256':sha(dest/rel)} for rel in sorted(files)]
    (STAGE/'SOURCE_MANIFEST.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    (STAGE/'preparation.json').write_text(json.dumps({'sourceReady':True,'priority':0,'items':[47,49],
        'parent':str(PARENT),'retainedParentReceiptSha256':sha(PARENT/'build.json'),'parentTests':151,
        'manifestSha256':sha(STAGE/'SOURCE_MANIFEST.json'),'excludedP2':True,'candidateVersionCode':10023,
        'candidateVersionName':'0.1.0-preview.20261020.3','published':False,'nextPublicVersion':'0.6',
        'delta':'Exact private worker self-retirement after authenticated STOP regardless of alive main; interrupted grace retained; redacted startup cause logged before retirement; five policy tests'},indent=2),encoding='utf-8')
    print(json.dumps({'stage':str(STAGE),'inputs':len(manifest),'excludedP2':True,'candidate':10023}))
if __name__=='__main__':main()
