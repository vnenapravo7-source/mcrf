"""Accepted P1 +47 +49 only. Port bounded startup edits; never copy live P2 methods."""
import hashlib,json,shutil
from pathlib import Path

ROOT=Path('D:/MCRF/Projects/MCRF')
PARENT=Path('D:/MCRF/Builds/Android/p047-eacb79c431714020bd9621836dcc819f')
STAGE=Path('D:/MCRF/Builds/Android/p049-f523aad221694dbe9e729f6ebd7a8a51')
K='ports/android/app/src/main/kotlin/org/mcrf/client/'
T='ports/android/app/src/test/kotlin/org/mcrf/client/'

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def once(text,before,after):
    assert text.count(before)==1,repr(before[:100]);return text.replace(before,after)
def live(name):return (ROOT/K/(name+'.kt')).read_text('utf-8')
def read(name):return (STAGE/'source'/K/(name+'.kt')).read_text('utf-8')
def write(name,text):(STAGE/'source'/K/(name+'.kt')).write_text(text,encoding='utf-8',newline='\n')

def main():
    assert not STAGE.exists(),'Never overwrite a previous attempt'
    prior=json.loads((PARENT/'verification.json').read_text('utf-8-sig'));assert prior['state']=='PASS_COMPILE_AND_PICKER_MODELS'
    assert sha(PARENT/'SOURCE_MANIFEST.json')==prior['sourceManifestSha256']
    rows=json.loads((PARENT/'SOURCE_MANIFEST.json').read_text('utf-8-sig'))
    p2=json.loads((ROOT/'ports/android/coordination/p2-progress-2026-10-20.json').read_text('utf-8-sig'))
    assert all(p2[k] is False for k in ['sourceReady','readyForBuild','allowCommonVerification','allowApkBuild','allowNativeCandidateBuild'])
    dest=STAGE/'source';dest.mkdir(parents=True)
    for row in rows:
        source=PARENT/'source'/row['path'];assert sha(source)==row['sha256'],row['path']
        target=dest/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    for name in ['RouteTextCache','ConnectionStartPolicy','ConnectTiming','WorkerValidationCache','AccessProbeBatch','DirectAccessCheck','BsStartupPolicy','BsDetectionMonitor']:
        shutil.copyfile(ROOT/K/(name+'.kt'),dest/K/(name+'.kt'))
    for name in ['RouteTextCacheTest','ConnectionStartPolicyTest','WorkerValidationCacheTest','AccessProbeBatchTest','RouteParsingPerformanceTest','BsStartupPolicyTest']:
        shutil.copyfile(ROOT/T/(name+'.kt'),dest/T/(name+'.kt'))
    # Only the pure parser prefix changes. The live apply() includes unfinished P2.
    marker='    private fun any('
    text=read('RouteRules');current=live('RouteRules');write('RouteRules',current[:current.index(marker)]+text[text.index(marker):])
    text=read('MainActivity')
    text=once(text,'        busy = true; connect?.isEnabled = false\n        worker.execute {',
        '        busy = true; connect?.isEnabled = false\n        connectionStartedNs=ConnectTiming.now()\n        val queuedAt=connectionStartedNs\n        worker.execute {\n            ConnectTiming.mark(this,"ui_queue",queuedAt)\n            val prepareAt=ConnectTiming.now()')
    text=once(text,'                        ClientPacketSelection.prepare(this,entry.original,ClientPacketSelection.validationDial(entry.host))',
        '                        // Service owns fresh packet admission and module identity before TUN.')
    start=text.index('                    val session=ClientPrimaryWorker.connect(this,"steer"')
    end=text.index('}finally{session.close()}',start)+len('}finally{session.close()}')
    text=text[:start]+'                    if(!ConnectionStartPolicy.skipTemporarySteerWorker(automatic,false)) {\n'+text[start:end]+'\n                    }'+text[end:]
    current=live('MainActivity');a=current.index('                // P049_DEFER_BEGIN:');b=current.index('                // P049_DEFER_END',a)+len('                // P049_DEFER_END')
    helper=current[a:b].replace('routes?.let {RouteServerPlan.activeIds(it).isNotEmpty()}?:false','false')
    before='                var prepared = LocalProxyConfig.apply('
    text=once(text,before,helper+'\n'+before)
    text=once(text,'            }\n        }\n    }\n    private fun launchConnection() {',
        '            } finally {ConnectTiming.mark(this,"ui_prepare",prepareAt)}\n        }\n    }\n    private var connectionStartedNs=0L\n    private fun launchConnection() {')
    before='        preparedProxyPort?.let {launch.putExtra("preparedProxyPort",it)};preparedProxyPort=null'
    text=once(text,before,'        launch.putExtra(ConnectTiming.EXTRA,connectionStartedNs);connectionStartedNs=0\n'+before)
    assert 'RouteServerPlan' not in text and 'NativeRoutePreparation' not in text
    write('MainActivity',text)
    text=read('ClientPrimaryWorkerService');current=live('ClientPrimaryWorkerService')
    text=once(text,'    private val stopping = AtomicBoolean()','    private val stopping = AtomicBoolean()\n    private val validatedInput=WorkerValidationCache<Pair<Engine.Validation,String?>>()')
    a=text.index('    private fun validated(');b=text.index('    private fun validateInput(',a)
    c=current.index('    private fun validated(');d=current.index('    private fun validateInput(',c)
    text=text[:a]+current[c:d]+text[b:]
    text=once(text,'    private fun stopWorker() {','    private fun stopWorker() {\n        validatedInput.close()');write('ClientPrimaryWorkerService',text)
    text=read('McrfVpnService');current=live('McrfVpnService')
    # Accepted P1 snapshot already includes the source-reviewed P046 helper.
    # Replace only that isolated block to add the new timing span.
    assert '                if(bs==null) startVpnWithBsPreflight(source,vpn,port,applyRoutes,requested)' in text
    a=current.index('    // P046_BEGIN:');b=current.index('\n',current.index('    // P046_END',a))+1
    helper=current[a:b];c=text.index('    // P046_BEGIN:');d=text.index('\n',text.index('    // P046_END',c))+1
    text=text[:c]+helper+text[d:]
    text=once(text,'        val applyRoutes = intent.getBooleanExtra("applyRoutes", false)',
        '        val applyRoutes = intent.getBooleanExtra("applyRoutes", false)\n        val connectStartedNs=intent.getLongExtra(ConnectTiming.EXTRA,0)\n        val serviceQueuedNs=ConnectTiming.now()')
    text=once(text,'        worker.execute {\n            try {\n                if (requested != epoch) return@execute\n                closeCore()',
        '        worker.execute {\n            ConnectTiming.mark(this,"service_queue",serviceQueuedNs)\n            val serviceStartedNs=ConnectTiming.now()\n            try {\n                if (requested != epoch) return@execute\n                ConnectTiming.measure(this,"previous_core_close"){closeCore()}')
    text=once(text,'JSONObject(ProfileStore(this).read())','ConnectTiming.measure(this,"profile_read"){JSONObject(ProfileStore(this).read())}')
    anchor='                    scheduleHealth(source,candidates,chosen,bs.getInt("interval"),vpn,port,applyRoutes,requested)\n                }'
    text=once(text,anchor,anchor+'\n                if(requested==epoch){ConnectTiming.mark(this,"service_to_ready",serviceStartedNs);ConnectTiming.mark(this,"tap_to_ready",connectStartedNs)}')
    for name,call in [('wg_prepare_start','startCompactWireGuard(template,vpn,port,applyRoutes,requested)'),('steer_prepare_start','startSteer(template,vpn,port,applyRoutes,requested,keepTraffic)')]:
        text=once(text,'            '+call,'            ConnectTiming.measure(this,"'+name+'"){'+call+'}')
    # Legacy P1 has its route preparation on one line. Preserve its exact remaining logic.
    old='if(applyRoutes){val routes=RoutingStore(this);routes.load();'
    text=once(text,old,'if(applyRoutes){val routes=RoutingStore(this);ConnectTiming.measure(this,"routes_load"){routes.load()};')
    old='prepared=RouteRules.apply(prepared,settings,vpn,PresetAssets.resolve(this,settings),PresetAssets.ipRuleSetTags(this,settings),directIpv4);'
    text=once(text,old,'prepared=ConnectTiming.measure(this,"routes_compile"){RouteRules.apply(prepared,settings,vpn,PresetAssets.resolve(this,settings),PresetAssets.ipRuleSetTags(this,settings),directIpv4)};')
    text=once(text,'val validation=server.validate("",settings,mode)','val validation=ConnectTiming.measure(this,"worker_validate"){server.validate("",settings,mode)}')
    text=once(text,'        server.start("",settings,mode)','        ConnectTiming.measure(this,"worker_start"){server.start("",settings,mode)}')
    assert 'NativeRoutePreparation' not in text and 'RouteServerSession' not in text
    write('McrfVpnService',text)
    files={row['path'] for row in rows}
    for prefix,names in [(K,['RouteTextCache','ConnectionStartPolicy','ConnectTiming','WorkerValidationCache','AccessProbeBatch','DirectAccessCheck','BsStartupPolicy','BsDetectionMonitor']),
                         (T,['RouteTextCacheTest','ConnectionStartPolicyTest','WorkerValidationCacheTest','AccessProbeBatchTest','RouteParsingPerformanceTest','BsStartupPolicyTest'])]:
        files.update(prefix+n+'.kt' for n in names)
    manifest=[{'path':rel,'sha256':sha(dest/rel)} for rel in sorted(files)]
    (STAGE/'SOURCE_MANIFEST.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    (STAGE/'preparation.json').write_text(json.dumps({'sourceReady':True,'priority':0,'items':[46,47,49],
        'parent':str(PARENT),'manifestSha256':sha(STAGE/'SOURCE_MANIFEST.json'),'excludedP2':True,'inputs':len(manifest)},indent=2),encoding='utf-8')
    print(json.dumps({'stage':str(STAGE),'inputs':len(manifest),'excludedP2':True}))

if __name__=='__main__':main()
