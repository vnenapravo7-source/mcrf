"""Build NEW production-Go/native composition fixtures in the common batch."""
from hashlib import sha256
import json
import re
from pathlib import Path
import shlex
import shutil

from connection49_common import NDK,guard,digest,save,environment,execute

def run(stage:Path)->dict:
    guard(stage)
    path=stage/'expanded-wire-build.json'
    if path.exists():raise ValueError('do not duplicate wire-fixture compilation')
    native=json.loads((stage/'expanded-native.json').read_text('utf-8'))
    if native['state']!='COMPILED_EXPANDED_EXTERNAL_NOT_RUNTIME_ACCEPTED':raise ValueError('new native phase required')
    short=Path(native['shortCheckout']);result={'state':'BUILDING','runtimeAccepted':False,'fixtures':[]}
    save(path,result);env=environment()
    try:
        # CMake's private full-crypto target enables certgen ONLY for this test.
        # Its generated mock is checked against independent uTLS framing; no
        # WOLFSSL_CERT_GEN or fixture key enters libsteer_vpn.so.
        for abi in ('arm64-v8a','x86_64'):
            folders=[p.parent for p in (short/'cxx').rglob('compile_commands.json')
                if p.parent.name==abi and (p.parent/'build.ninja').is_file() and (p.parent/'CMakeCache.txt').is_file()]
            if len(folders)!=1:raise ValueError('ambiguous configured native ABI')
            build=folders[0]
            # The upstream mock relied on a transitive Reality declaration.
            # Supply its explicit header in this generated private include,
            # not by modifying frozen production sources or their objects.
            body=(short/'work/steer-android-port/tests/vlessmatch.c').read_text('utf-8')
            body=body.replace('/tmp/','/data/local/tmp/').replace('../src/',str(short/'work/steer-android-port/src/').replace('\\','/')+'/')
            include=build/'vlessmatch49.inc';expected='#define _GNU_SOURCE\n#include "reality.h"\n'+body
            if include.read_text('utf-8') not in (body,expected):raise ValueError('private generated mock changed')
            if include.read_text('utf-8')!=expected:include.write_text(expected,encoding='utf-8',newline='\n')
            execute(stage,'expanded-compressed-fixture-'+abi,[
                'C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe',
                '--build',build,'--target','cert_wire49_go_test','--parallel','4','--','-k','0'],short,env)
            cache=(build/'CMakeCache.txt').read_text('utf-8')
            runtime=re.findall(r'^CMAKE_RUNTIME_OUTPUT_DIRECTORY:[^=]+=(.+)$',cache,re.M)
            if len(runtime)!=1:raise ValueError('ambiguous native runtime output')
            source=(Path(runtime[0].strip())/'cert_wire49_go_test').resolve(strict=True)
            if not source.is_relative_to(short.resolve()):raise ValueError('native runtime escaped checkout')
            if not source.is_file():raise ValueError('new production-Go composition fixture missing')
            out=stage/'wire-fixtures'/abi;out.mkdir(parents=True)
            target=out/source.name;shutil.copyfile(source,target)
            result['fixtures'].append({'abi':abi,'name':target.name,'path':str(target),'sha256':digest(target),
                'productionTLS':True,'executed':False})
            rows=json.loads((build/'compile_commands.json').read_text('utf-8'))
            candidates=[r for r in rows if r['file'].endswith('vldial.c') and
                'CMakeFiles/steer_vpn.dir/' in r.get('command',' '.join(r.get('arguments',[]))).replace('\\','/')]
            if len(candidates)!=1:raise ValueError('ambiguous production TLS compile flags')
            row=candidates[0]
            args=row.get('arguments') or shlex.split(row['command'],posix=False)
            args=[a[1:-1] if a.startswith('"') and a.endswith('"') else a for a in args]
            flags=args[1:args.index('-o')]
            if '-DMCRF_UTLS49=1' not in flags:raise ValueError('wire fixture would test diagnostic TLS, not production')
            objects=[p for p in sorted((build/'CMakeFiles/steer_vpn.dir').rglob('*.o'))
                if p.name!='bridge.c.o' and not p.as_posix().endswith('/daemon/main.c.o')]
            if len(objects)<50:raise ValueError('missing production native objects')
            archive=json.loads((stage/'tls-library.json').read_text('utf-8'))['abis'][abi]
            go_archive=Path(native['phases']['steer']['stage'])/'steer/native'/abi/'libmcrf_utls49.so'
            if digest(go_archive)!=native['tlsPackaging'][abi]['packagedSha256']:
                raise ValueError('production packaged TLS helper differs')
            helper=out/'libmcrf_utls49.so';shutil.copyfile(go_archive,helper)
            result.setdefault('dependencies',[]).append({'abi':abi,'name':helper.name,
                'path':str(helper),'sha256':digest(helper)})
            libs=[go_archive,build/'libmcrf_brotli_dec49.a',build/'libmcrf_zstd_dec49.a',build/'libngtcp2_android.a']
            if not all(p.is_file() for p in libs):raise ValueError('missing source-bound wire libraries')
            for name in ('link-options49','tls-owner49','owned-reality49'):
                target=out/name;source=short/'work/steer-android-port/tests'/(name+'.c')
                link=[*flags,'-UNDEBUG','-fPIE','-pie',str(source),*map(str,objects),*map(str,libs),
                    '-Wl,--gc-sections','-Wl,--exclude-libs,ALL','-Wl,-z,max-page-size=16384','-Wl,-rpath,$ORIGIN',
                    '-lz','-llog','-latomic','-lm','-o',str(target)]
                rsp=out/(name+'.rsp');rsp.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in link),encoding='utf-8')
                execute(stage,'expanded-'+name+'-'+abi,[args[0],'@'+str(rsp)],build,env)
                result['fixtures'].append({'abi':abi,'name':name,'path':str(target),'sha256':digest(target),
                    'productionTLS':True,'executed':False})
        result['state']='COMPILED_PRODUCTION_WIRE_FIXTURES_NOT_EXECUTED'
        return result
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:save(path,result)
