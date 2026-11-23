"""Resume only new fixture link after product two-ABI native compile PASSED."""
import json,shutil
from pathlib import Path
import build_connection49_android_native as b
import build_connection49_steer_wire as wire
S=Path('D:/MCRF/Builds/Android/p049-steerdiag-d467d4f9097147b195cd066d3afd869a')
SHORT=Path('D:/MCRF/Builds/Android/p1s-975a298afc71')
def main():
    result=json.loads((S/'android-native.json').read_text('utf-8'));assert result['state']=='FAILED_PRESERVED'
    assert 'BUILD SUCCESSFUL' in (S/'steer-native-resume.log').read_text('utf-8')
    assert '__android_log_print' in (S/'node-bind49-build.log').read_text('utf-8')
    assert not (S/'android-native-attempt2.json').exists();shutil.copyfile(S/'android-native.json',S/'android-native-attempt2.json');shutil.copyfile(S/'node-bind49-build.log',S/'node-bind49-link-attempt1.log')
    rows=json.loads((S/'steer-inputs.json').read_text('utf-8'))
    for r in rows:assert b.sha(S/'steer-source'/r['path'])==r['sha256']==b.sha(SHORT/r['path'])
    receipt=json.loads((S/'primary-stage/receipt.json').read_text('utf-8'));assert receipt['modules']['steer']['versionCode']==11
    for r in receipt['nativeLibraries']:
        path=S/'primary-stage'/r['file'];assert b.sha(path)==r['sha256'];b.check_steer_elf(path.read_bytes(),r['abi'])
    current=b.ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'));assert not any(live['jobs'].values())
    owner='P049_STEER11_FIXTURE:'+str(S);live['jobs']['hostTest']=owner;b.save(current,live)
    try:
        wire.build(S,SHORT,'node-bind49',True)
        result['phases']['steer']={'state':'COMPILED_NOT_EXECUTED','stage':str(S/'primary-stage'),'short':str(SHORT),'receiptSha256':b.sha(S/'primary-stage/receipt.json')};result['state']='COMPILED_ANDROID_EXTERNAL_NOT_RUNTIME_ACCEPTED'
    finally:
        b.save(S/'android-native.json',result);live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['hostTest']==owner:live['jobs']['hostTest']=None;b.save(current,live)
    print(result['state'])
if __name__=='__main__':main()
