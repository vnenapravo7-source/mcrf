"""Exact P049 external AAR payload delta proof, not an acceptance shortcut."""
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import zipfile

ASSETS={
 'assets/licenses/brotli.txt',
 'assets/licenses/zstd.txt',
 'assets/licenses/steer-tls49-NOTICES.txt',
}
CLASS='org/steer/core/SteerCore.class'
ADAPTER_CLASS='org/mcrf/engines/steer/SteerEngine.class'
HELPER={'jni/arm64-v8a/libmcrf_utls49.so','jni/x86_64/libmcrf_utls49.so'}
JNI={'jni/arm64-v8a/libsteer_vpn.so','jni/x86_64/libsteer_vpn.so'}|HELPER


def entries(data:bytes)->dict:
    with zipfile.ZipFile(BytesIO(data)) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise ValueError('duplicate archive entry')
        if any(n.startswith('/') or '..' in Path(n).parts for n in names):raise ValueError('unsafe archive path')
        return {n:z.read(n) for n in names if not n.endswith('/')}


def verify(original:bytes,candidate:bytes,asset_hashes:dict,class_hash:str,jni_hashes:dict)->dict:
    before=entries(original);after=entries(candidate)
    if set(asset_hashes)!=ASSETS or set(jni_hashes)!=JNI:
        raise ValueError('exact source-bound license/JNI allowlist required')
    if set(after)-set(before)!=ASSETS|HELPER or set(before)-set(after):
        raise ValueError('unexpected AAR entry addition/removal')
    allowed=ASSETS|JNI|{'classes.jar'}
    for name,value in before.items():
        if name not in allowed and after[name]!=value:raise ValueError('unrelated AAR payload changed: '+name)
    for name,expected in {**asset_hashes,**jni_hashes}.items():
        if sha256(after[name]).hexdigest()!=expected:raise ValueError('AAR payload not from frozen source: '+name)
    old_classes=entries(before['classes.jar']);new_classes=entries(after['classes.jar'])
    if set(old_classes)!=set(new_classes):raise ValueError('AAR class inventory changed')
    for name,value in old_classes.items():
        if name!=CLASS and new_classes[name]!=value:raise ValueError('unrelated class changed: '+name)
    if new_classes.get(CLASS)==old_classes.get(CLASS) or sha256(new_classes[CLASS]).hexdigest()!=class_hash:
        raise ValueError('exact new TLS cancel API class missing')
    return {'state':'PASS_EXACT_ALLOWED_PAYLOAD_DELTA','changedClass':CLASS,
            'licenseAssets':sorted(ASSETS),'jni':jni_hashes,'allOtherPayloadExact':True,
            'runtimeAccepted':False,'p2Included':False}


def verify_adapter(original:bytes,candidate:bytes,expected_class_sha:str)->dict:
    before=entries(original);after=entries(candidate)
    if set(before)!=set(after):raise ValueError('adapter AAR inventory changed')
    for name,value in before.items():
        if name!='classes.jar' and after[name]!=value:raise ValueError('unrelated adapter payload changed')
    old=entries(before['classes.jar']);new=entries(after['classes.jar'])
    if set(old)!=set(new):raise ValueError('adapter class inventory changed')
    for name,value in old.items():
        if name!=ADAPTER_CLASS and new[name]!=value:raise ValueError('unrelated adapter class changed')
    if new.get(ADAPTER_CLASS)==old.get(ADAPTER_CLASS) or sha256(new[ADAPTER_CLASS]).hexdigest()!=expected_class_sha:
        raise ValueError('exact P0 TLS stop hook class missing')
    return {'state':'PASS_EXACT_ADAPTER_STOP_HOOK_DELTA','changedClass':ADAPTER_CLASS,
            'allOtherPayloadExact':True,'runtimeAccepted':False,'p2Included':False}
