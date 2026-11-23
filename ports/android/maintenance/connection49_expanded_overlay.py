"""SOURCE for the expanded common freezer. Never copy live P2 service/JNI/CMake.

No top-level work. Caller verifies frozen parent manifests and owns a fresh
stage. Edits below are allowlisted P0 hunks only, with before/after provenance.
"""
from hashlib import sha256
from pathlib import Path
import shutil

K='ports/android/app/src/main/kotlin/org/mcrf/client/'
T='ports/android/app/src/test/kotlin/org/mcrf/client/'
N='work/steer-android-port/'
CPP=N+'android-vpn/core/src/main/cpp/'
JAVA=N+'android-vpn/core/src/main/java/org/steer/core/SteerCore.java'
ENGINE=N+'android-vpn/module-apk/src/main/java/org/mcrf/engines/steer/SteerEngine.java'

NATIVE_FILES=[
 'src/lib/scrypto.c','src/lib/scrypto.h',
 'src/proto/tls/reality.c','src/proto/tls/reality.h','src/proto/tls/tls13.c','src/proto/tls/tls13.h',
 'src/proto/tls/alpn49.h','src/proto/tls/fingerprint49.h',
 'src/proto/tls/cert_compress49.c','src/proto/tls/cert_compress49.h',
 'src/proto/tls/tls49.c','src/proto/tls/tls49.h','src/proto/tls/tls49_abi.h',
 'src/proto/transport/transport.c','src/proto/transport/transport.h','src/proto/transport/trsec.c',
 'src/proto/transport/trxhttp.c','src/proto/transport/trupgrade.c','src/proto/transport/trws.c',
 'src/proto/vless/vless.h','src/proto/vless/sublink.c','src/proto/vless/sublink.h','src/proto/vless/sub.c',
 'src/proto/proxy/pxsub.c',
 'tests/alpn49.c','tests/link-options49.c','tests/fingerprint-shape49.c','tests/p25649.c',
 'tests/cert-compress49.c','tests/cert-wire49.c','tests/cert-wire49-go.c','tests/tls-owner49.c','tests/owned-reality49.c','tests/vlessmatch.c',
 'android-vpn/core/src/main/cpp/compression49.cmake',
 'android-vpn/core/src/main/cpp/compression49-fixtures.cmake',
 'android-vpn/core/src/main/cpp/tls49.cmake',
 'android-vpn/core/src/main/assets/licenses/brotli.txt',
 'android-vpn/core/src/main/assets/licenses/zstd.txt',
 'android-vpn/core/src/main/assets/licenses/steer-tls49-NOTICES.txt',
]


def digest(p):return sha256(p.read_bytes()).hexdigest()


def replace_once(text,old,new):
    if text.count(old)!=1:raise ValueError('P0 frozen hunk shape differs')
    return text.replace(old,new,1)


def change(path:Path,operation,proof:list,scope:str):
    before=digest(path);original=path.read_text('utf-8')
    updated=operation(original)
    if original==updated:raise ValueError('P0 hunk unexpectedly already present')
    path.write_text(updated,encoding='utf-8',newline='\n')
    proof.append({'path':str(path),'before':before,'after':digest(path),'scope':scope,'wholeCanonicalCopied':False})


def source_copy(root:Path,stage:Path,rel:str,proof:list):
    source=root/rel;target=stage/rel
    if not source.is_file() or source.is_symlink():raise ValueError('unsafe source file')
    before=digest(target) if target.exists() else None
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    if digest(target)!=digest(source):raise ValueError('source copy differs')
    proof.append({'path':rel,'before':before,'after':digest(target),'scope':'individually allowlisted P0 source'})


def apply(root:Path,app:Path,native:Path)->list:
    proof=[]
    engines='ports/android/engines/build.gradle.kts'
    text=(root/engines).read_text('utf-8')
    begin='                // P049_TLS_SHARED_BEGIN\n'
    end='                // P049_TLS_SHARED_END'
    if text.count(begin)!=1 or text.count(end)!=1:raise ValueError('exact two-ELF packaging guard missing')
    fragment=begin+text.split(begin,1)[1].split(end,1)[0]+end
    change(app/engines,lambda s:replace_once(s,
        '                require(native == if (name == "runtime.aar") abis.map{"jni/$it/$library"}.toSet() else emptySet<String>()){"Unexpected primary native payload"}',
        fragment),proof,'exact helper ABI/hash allowlist only; no other live/P2 Gradle source copied')
    for rel in [K+'ServerImport.kt',K+'ServerLinkOptions.kt',K+'SteerEffectiveProfile.kt',
                K+'EngineUpdatePrimary.kt',T+'EngineUpdatePrimaryTest.kt',
                T+'ServerLinkVariations49Test.kt',T+'SteerLinkEdits49Test.kt']:
        source_copy(root,app,rel,proof)
    change(app/(K+'McrfVpnService.kt'),lambda s:replace_once(s,
        'startSteerPrepared(template,SteerPrepared(entry.original,entry.host,entry.name,entry.id,requireNotNull(snapshot.settingsJson)),port,applyRoutes,requested,keepTraffic)',
        'startSteerPrepared(template,SteerPrepared(SteerEffectiveProfile.profile(entry),entry.host,entry.name,entry.id,requireNotNull(snapshot.settingsJson)),port,applyRoutes,requested,keepTraffic)'),
        proof,'ONE effective-profile argument, not canonical P2 service')
    for rel in NATIVE_FILES:source_copy(root,native,N+rel,proof)
    live=(root/(CPP+'bridge.c')).read_text('utf-8')
    marker='#ifdef MCRF_UTLS49\n#include "tls49.h"'
    prefix=live[live.index(marker):live.index('JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_verifyTlsNative')]
    if 'route_backend' in prefix or 'probeNative' in prefix:raise ValueError('P2 leaked into TLS owner prefix')
    def bridge(s):
        s=replace_once(s,'#include "node_bind49.h"','#include "node_bind49.h"\n#include <stdatomic.h>\n'+prefix.rstrip())
        s=replace_once(s,'        struct tr_node node;sl_tr_node(&vn,&node);',
            '#ifdef MCRF_UTLS49\n        vn.runtime_epoch49=begin_tls49();\n#endif\n        struct tr_node node;sl_tr_node(&vn,&node);')
        s=replace_once(s,'        tr_link_close(&link);steer_set_state_dir(NULL);',
            '        tr_link_close(&link);steer_set_state_dir(NULL);\n#ifdef MCRF_UTLS49\n        end_tls49(vn.runtime_epoch49);\n#endif')
        s=replace_once(s,'    rc=stack_run_fd(&output,&dialer,fd,NULL,NULL);',
            '#ifdef MCRF_UTLS49\n    node->runtime_epoch49=begin_tls49();\n    rc=node->runtime_epoch49?stack_run_fd(&output,&dialer,fd,NULL,NULL):2;\n    end_tls49(node->runtime_epoch49);\n#else\n    rc=stack_run_fd(&output,&dialer,fd,NULL,NULL);\n#endif')
        for forbidden in ('prepareRouteNative','relayRouteNative','probeNative','route_relay.h','route_slots.h'):
            if forbidden in s:raise ValueError('unfinished P2 JNI leaked into frozen P0')
        return s
    change(native/(CPP+'bridge.c'),bridge,proof,'TLS begin/cancel/verify/run ONLY; P2 JNI excluded')
    change(native/JAVA,lambda s:replace_once(s,'    public static native long[] counters();',
        '    public static native long[] counters();\n    /** Cancel TLS only; worker death remains mandatory. */\n    public static native void cancelTLS();'),proof,'one TLS cancel declaration; no P2 probe/route APIs')
    change(native/JAVA,lambda s:replace_once(s,'static { System.loadLibrary("steer_vpn"); }',
        'static { System.loadLibrary("mcrf_utls49"); System.loadLibrary("steer_vpn"); }'),
        proof,'same verified child module namespace; supported Android c-shared helper, no host preload')
    change(native/ENGINE,lambda s:replace_once(s,'        stopping = true;',
        '        stopping = true;\n        if(used)SteerCore.cancelTLS();'),proof,'cancel TLS owner before existing P1 cleanup')
    def cmake(s):
        # Same upstream SHA256 pins; use the previously verified local archives
        # frozen by this batch. No network download/TLS bypass at build time.
        s=replace_once(s,'URL https://github.com/wolfSSL/wolfssl/archive/refs/tags/v5.9.4-stable.tar.gz',
            'URL "${MCRF_CODEC49_ARCHIVES}/v5.9.4-stable.tar.gz"')
        s=replace_once(s,'URL https://github.com/ngtcp2/ngtcp2/releases/download/v1.25.0/ngtcp2-1.25.0.tar.xz',
            'URL "${MCRF_CODEC49_ARCHIVES}/ngtcp2-1.25.0.tar.xz"')
        s=replace_once(s,'add_library(steer_vpn SHARED bridge.c hostdial.c ip6_packet.c ${SOURCES} ${WOLF_FILES})',
            'add_library(steer_vpn SHARED bridge.c hostdial.c ip6_packet.c ${SOURCES} ${WOLF_FILES})\n'
            'include("${CMAKE_CURRENT_LIST_DIR}/compression49.cmake")\n'
            'mcrf_steer_cert_compression49(steer_vpn)\n'
            'include("${CMAKE_CURRENT_LIST_DIR}/tls49.cmake")\n'
            'mcrf_steer_tls49(steer_vpn)')
        s=replace_once(s,'target_link_libraries(steer_vpn ngtcp2_android log)',
            'target_link_libraries(steer_vpn PRIVATE ngtcp2_android log)')
        return s+'\ninclude("${CMAKE_CURRENT_LIST_DIR}/compression49-fixtures.cmake")\n'
    change(native/(CPP+'CMakeLists.txt'),cmake,proof,'two P0 TLS/codec hooks + NEW fixture; no P2 native targets')
    gradle=N+'android-vpn/core/build.gradle.kts'
    change(native/gradle,lambda s:replace_once(s,
        'externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }',
        'externalNativeBuild { cmake {\n'
        '            arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"\n'
        '            providers.gradleProperty("p049TlsManifest").orNull?.let {\n'
        '                arguments += "-DMCRF_TLS49_MANIFEST=" + file(it).canonicalPath.replace(\'\\\\\',\'/\')\n'
        '            }\n'
        '            providers.gradleProperty("p049CodecArchives").orNull?.let {\n'
        '                arguments += "-DMCRF_CODEC49_ARCHIVES=" + file(it).canonicalPath.replace(\'\\\\\',\'/\')\n'
        '            }\n        } }'),proof,'ABI-bound TLS archive + frozen codec source only')
    return proof
