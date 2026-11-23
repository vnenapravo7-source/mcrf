"""Same incomplete human phone grant; upgrade only the frozen corrected P049 app."""
import json
from pathlib import Path
import p049_device as d
RECOVERY=Path('D:/MCRF/Builds/Android/p049-recovery-4b4fc542e5234a6b966455622d52a30e')
def open_own():
    focus=d.text('shell','dumpsys','window')
    assert any(x in focus for x in ['org.mcrf.client/','com.android.chrome/','com.sec.android.app.launcher/'])
    d.adb('shell','am','start','-n','org.mcrf.client/.MainActivity');d.ready(install=True)
def main():
    current=json.loads((d.ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    assert not any(current['jobs'].values()),'Existing process/job must be continued'
    build=json.loads((RECOVERY/'build.json').read_text('utf-8-sig'))
    assert build['state']=='PASS_R8_RECOVERY_AND_156_JVM' and build['sourceStable']
    assert d.sha(RECOVERY/'SOURCE_MANIFEST.json')==build['sourceManifestSha256']
    apk=build['artifacts']['universal'];assert d.sha(Path(apk['path']))==apk['sha256']
    data=d.state();assert data['installedCandidate'] and not data.get('recoveryInstalled') and not data['ownsVpn']
    current['jobs']['device']=d.JOB;d.save(d.ROOT/'ports/android/coordination/current.json',current)
    open_own();before=d.checkpoint('before-install10023');open_own()
    assert before['versionCode']==10022 and before['prefs']['primaryCore']=='singbox' and not before['prefs']['wantedRunning']
    assert 'Success' in d.text('install','--user','0','--no-streaming','-r',apk['path'],timeout=90)
    assert d.installed('org.mcrf.client')['sha256']==apk['sha256']
    open_own();after=d.checkpoint('after-install10023')
    assert after['versionCode']==10023 and all(after[k]==before[k] for k in ['prefHashes','files','modules'])
    data['recoveryInstalled']=True;data['candidate']=10023;data['state']='RECOVERY_INSTALLED_RUNTIME_PENDING'
    d.save(d.STAGE/'device.json',data)
    d.save(d.STAGE/'recovery-install.json',{'installed':10023,'sha256':apk['sha256'],'bytes':apk['bytes'],'protectedUnchanged':True,'published':False})
    open_own();print('INSTALLED10023_EXACT_PROTECTED_UNCHANGED')
if __name__=='__main__':main()
