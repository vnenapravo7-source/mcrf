"""Compile ONLY the owned TLS test entry point; production objects stay intact."""
import hashlib, json, pathlib, re, shutil, subprocess, uuid

ROOT=pathlib.Path('D:/MCRF/Projects/MCRF')
BASE=pathlib.Path('D:/MCRF/Builds/Android')
PRODUCER=BASE/'p2-steer-candidate-d3992f56f32d41438c654d3d785162a1/producer-receipt.json'
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,data):
    with p.open('x',encoding='utf-8') as f: json.dump(data,f,indent=2)
def run(command,cwd):
    r=subprocess.run(command,cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
    if r.returncode: raise RuntimeError(r.stdout.decode(errors='replace')[-4000:])
    return r.stdout.decode()

p=read(PRODUCER); frozen=pathlib.Path(p['frozenSourceDirectory'])
assert p['sourceStable'] and p['state']=='compiled_candidate_not_runtime_accepted'
build=frozen/'cxx/RelWithDebInfo/3q3c2p3q/arm64-v8a'
source=ROOT/'work/steer-android-port/android-vpn/tests/host_probe_tls_test.c'
original=frozen/source.relative_to(ROOT)
old=original.read_text();new=source.read_text()
expected=old.replace('"socks://owned:%s@127.0.0.1:%d"','"socks://owned:%s@owned.example.test:%d"').replace(
    '    char uri[160];','    /* Exercise the production parser without weakening its loopback-import\n     * refusal. Only this owned fixture pins its parsed node to ADB loopback. */\n    char uri[160];').replace(
    'assert(!px_parse_url(uri,&node,PX_SOCKS));','assert(!px_parse_url(uri,&node,PX_SOCKS));\n    snprintf(node.vn.host,sizeof(node.vn.host),"127.0.0.1");')
expected=expected.replace('    assert((value>=0)==want&&value<=timeout&&prepared==1&&count_fds()==before);',
    '    int after=count_fds();\n    if((value>=0)!=want||value>timeout||prepared!=1||after!=before)\n        fprintf(stderr,"P2_ASSERT value=%ld want=%d prepared=%d fd_before=%d fd_after=%d elapsed=%lld\\n",value,want,prepared,before,after,elapsed);\n    assert((value>=0)==want&&value<=timeout&&prepared==1&&after==before);')
expected=expected.replace('#include "transport.h"','#include "transport.h"\n#include "tls49.h"').replace(
    '    int before=count_fds();long long start=now_ms();',
    '    /* Join lazy Go shared-runtime initialization before the descriptor baseline.\n     * This invalid local validation neither opens a connection nor changes trust. */\n    assert(mcrf49_validate("{}",2)!=0);\n    int before=count_fds();long long start=now_ms();')
expected=expected.replace('    if((value>=0)!=want||value>timeout||prepared!=1||after!=before)',
    '    if(after!=before){\n        DIR *fds=opendir("/proc/self/fd");assert(fds);struct dirent *entry;\n        while((entry=readdir(fds))){char path[512],target[512];\n            snprintf(path,sizeof(path),"/proc/self/fd/%s",entry->d_name);ssize_t n=readlink(path,target,sizeof(target)-1);\n            if(n>=0){target[n]=0;fprintf(stderr,"P2_FD %s %s\\n",entry->d_name,target);}}\n        closedir(fds);\n    }\n    if((value>=0)!=want||value>timeout||prepared!=1||after!=before)')
expected=expected.replace('#include "tls49.h"','#include "tls49.h"\n#include <android/log.h>').replace(
    '    assert(mcrf49_validate("{}",2)!=0);',
    '    assert(mcrf49_validate("{}",2)!=0);\n    /* Bionic\'s first resolver/log message opens process-lifetime logd/pmsg FDs. */\n    __android_log_write(ANDROID_LOG_INFO,"SteerP2","owned fixture initialized");')
assert new==expected, 'Only declared fixture initialization/seam/diagnostics admitted'
stage=BASE/('p2-probe-fixture-'+uuid.uuid4().hex);stage.mkdir()
snapshot=stage/'host_probe_tls_test.c';shutil.copyfile(source,snapshot)
commands=read(build/'compile_commands.json')
entry=next(c for c in commands if pathlib.Path(c['file'])==original)
compile_command=entry['command'];obj=re.search(r' -o (.+?) -c ',compile_command).group(1)
ninja=pathlib.Path('C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe')
link=run([str(ninja),'-C',str(build),'-t','commands','host_probe_tls_test'],build).splitlines()[-1]
link=link.split(' && ',1)[1].rsplit(' && ',1)[0]
text=(build/'build.ninja').read_text();blocks=text.replace('$\n','').split('\n\n')
block=next(b for b in blocks if ': C_EXECUTABLE_LINKER__host_probe_tls_test_' in b)
inputs=block.splitlines()[0].split(': C_EXECUTABLE_LINKER__',1)[1].split(' ',1)[1].split(' |',1)[0]
libs=re.search(r'^  LINK_LIBRARIES = (.*)$',block,re.M).group(1)
paths=re.search(r'^  LINK_PATH = (.*)$',block,re.M)
rsp=inputs+' '+(paths.group(1) if paths else '')+' '+libs
# First relink unchanged inputs and require byte identity with packaged fixture.
origin=read(pathlib.Path(p['primaryStage'])/'receipt.json')
fixture=next(r for r in origin['nativeFixtures'] if r['abi']=='arm64-v8a' and r['name']=='host_probe_tls_test')
oldbinary=pathlib.Path(p['primaryStage'])/'steer'/fixture['file']
assert sha(oldbinary)==fixture['sha256']
output=re.search(r' -o (.+?)\s*$',link).group(1)
rsp_token=re.search(r' @(\S+)',link).group(1)
reference=stage/'reference';reference_rsp=stage/'reference.rsp'
with reference_rsp.open('x') as f: f.write(rsp)
run(link.replace('@'+rsp_token,'@'+str(reference_rsp)).replace(' -o '+output,' -o '+str(reference)),build)
assert sha(reference)==fixture['sha256'], 'Retained object set does not reproduce packaged fixture'
object_path=stage/'fixture.o';binary=stage/'host_probe_tls_test'
compile_command=compile_command.replace(' -o '+obj,' -o '+str(object_path)).replace(' -c '+entry['file'],' -I'+str(original.parent)+' -c '+str(snapshot))
run(compile_command,build)
newrsp=stage/'fixture.rsp'
link_obj=obj.replace('\\','/')
assert rsp.count(link_obj)==1
with newrsp.open('x') as f: f.write(rsp.replace(link_obj,object_path.as_posix()))
run(link.replace('@'+rsp_token,'@'+str(newrsp)).replace(' -o '+output,' -o '+str(binary)),build)
assert sha(source)==sha(snapshot)
save(stage/'receipt.json',dict(state='FIXTURE_ONLY_COMPILED_RUNTIME_OPEN',producerReceiptSha256=sha(PRODUCER),
    originalNativeSha256=fixture['sha256'],referenceSha256=sha(reference),sourceSha256=sha(snapshot),
    originalSourceSha256=sha(original),nativeSha256=sha(binary),nativeFile=str(binary),
    assertionsEnabled='-UNDEBUG' in compile_command,productionUnchanged=True,
    compileCommand=compile_command,linkCommand=link,compileCommandsSha256=sha(build/'compile_commands.json'),
    buildNinjaSha256=sha(build/'build.ninja')))
print(stage/'receipt.json')
