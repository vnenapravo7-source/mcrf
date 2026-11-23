"""Reviewed child: preserve150-case evidence, add final timing spans/budget and candidate version."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path('D:/MCRF/Builds/Android/p049-f523aad221694dbe9e729f6ebd7a8a51')
STAGE=Path('D:/MCRF/Builds/Android/p049-candidate-701f35cbd75f41dab40a97dafdca7cb0')
K='ports/android/app/src/main/kotlin/org/mcrf/client/'
T='ports/android/app/src/test/kotlin/org/mcrf/client/'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def once(text,before,after):
    assert text.count(before)==1,repr(before);return text.replace(before,after)
def main():
    assert not STAGE.exists()
    prior=json.loads((PARENT/'verification.json').read_text('utf-8-sig'))
    assert prior['state']=='PASS_COMMON_COMPILE_AND_AFFECTED_JVM' and prior['tests']==150 and prior['sourceStable']
    assert sha(PARENT/'SOURCE_MANIFEST.json')==prior['sourceManifestSha256']
    rows=json.loads((PARENT/'SOURCE_MANIFEST.json').read_text('utf-8'))
    dest=STAGE/'source';dest.mkdir(parents=True)
    for row in rows:
        source=PARENT/'source'/row['path'];assert sha(source)==row['sha256'],row['path']
        target=dest/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    for name in ['AccessProbeBatch','DirectAccessCheck']:shutil.copyfile(ROOT/K/(name+'.kt'),dest/K/(name+'.kt'))
    test=T+'DirectAccessBudgetTest.kt';shutil.copyfile(ROOT/test,dest/test)
    file=dest/K/'McrfVpnService.kt';text=file.read_text('utf-8')
    for before,after in [
        ('val transport=NativeCarrier.start(this,it,java.util.function.BooleanSupplier { requested!=epoch },carrierTimeout);carrier=transport',
         'val transport=ConnectTiming.measure(this,"carrier_start"){NativeCarrier.start(this,it,java.util.function.BooleanSupplier { requested!=epoch },carrierTimeout)};carrier=transport'),
        ('val native=OptionalCoreSession.start(this,plan,java.util.function.BooleanSupplier{requested!=epoch},vpn,true)',
         'val native=ConnectTiming.measure(this,"optional_core_start"){OptionalCoreSession.start(this,plan,java.util.function.BooleanSupplier{requested!=epoch},vpn,true)}'),
        ('val connected=ClientPrimaryWorker.connect(this,choice,runGeneration,platform,onDeath=onDeath)',
         'val connected=ConnectTiming.measure(this,"core_init"){ClientPrimaryWorker.connect(this,choice,runGeneration,platform,onDeath=onDeath)}')]:
        text=once(text,before,after)
    assert text.count('builder.establish()')==3
    text=text.replace('builder.establish()','ConnectTiming.measure(this,"tun_establish"){builder.establish()}')
    assert 'NativeRoutePreparation' not in text and 'RouteServerPlan' not in text
    file.write_text(text,encoding='utf-8',newline='\n')
    file=dest/'ports/android/app/build.gradle.kts';text=file.read_text('utf-8')
    text=once(text,'versionCode = 10021','versionCode = 10022')
    text=once(text,'versionName = "0.1.0-preview.20261020.1"','versionName = "0.1.0-preview.20261020.2"')
    file.write_text(text,encoding='utf-8',newline='\n')
    files={row['path'] for row in rows}|{test}
    manifest=[{'path':rel,'sha256':sha(dest/rel)} for rel in sorted(files)]
    (STAGE/'SOURCE_MANIFEST.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    (STAGE/'preparation.json').write_text(json.dumps({'sourceReady':True,'priority':0,'items':[46,47,49],
        'parent':str(PARENT),'retainedParentReceiptSha256':sha(PARENT/'verification.json'),'parentTests':150,
        'manifestSha256':sha(STAGE/'SOURCE_MANIFEST.json'),'excludedP2':True,'candidateVersionCode':10022,
        'candidateVersionName':'0.1.0-preview.20261020.2','published':False,'nextPublicVersion':'0.6',
        'delta':'5s shared deadline preserving old2.5+2.5 timeouts; carrier/optional/core-init/TUN timing spans; one new budget test; internal candidate version only'},indent=2),encoding='utf-8')
    print(json.dumps({'stage':str(STAGE),'inputs':len(manifest),'excludedP2':True,'candidate':10022}))
if __name__=='__main__':main()
