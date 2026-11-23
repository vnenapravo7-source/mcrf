"""Shared guards for the single expanded P049 batch. No top-level side effects."""
from hashlib import sha256
from io import BytesIO
import json
import os
from pathlib import Path
import struct
import subprocess
import time
import zipfile

ROOT=Path('D:/MCRF/Projects/MCRF')
BUILDS=Path('D:/MCRF/Builds/Android')
JAVA=Path('C:/Program Files/Java/jdk-17')
SDK=Path('C:/Users/dieonfire/AppData/Local/Android/Sdk')
NDK=SDK/'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
GO=ROOT/'work/toolchains/go1.26.8/go/bin/go.exe'

def digest(path):return sha256(path.read_bytes()).hexdigest()
def save(path,value):path.write_text(json.dumps(value,indent=2),encoding='utf-8')

def guard(stage):
    from verify_connection49_expanded_host import verify_inputs
    stage=stage.resolve(strict=True)
    if stage.parent!=BUILDS.resolve() or not stage.name.startswith('p049-expanded-'):
        raise ValueError('exact expanded stage required')
    prep=verify_inputs(stage)
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    if live['jobs']['build']!='P049_EXPANDED_COMMON:'+str(stage):raise ValueError('common build ownership lost')
    if live['jobs'].get('device') or live['jobs'].get('emulator'):raise ValueError('overlapping device/emulator job')
    return prep

def environment():
    env=os.environ.copy()
    for name in ('GOOS','GOARCH','GOARM','CC','CXX','CGO_ENABLED'):env.pop(name,None)
    env.update(JAVA_HOME=str(JAVA),ANDROID_HOME=str(SDK),GOWORK='off',GOPROXY='off',
        GOTOOLCHAIN='local',GOFLAGS='',GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'),
        GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'))
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
    return env

def execute(stage,name,command,cwd,env,timeout=1800):
    guard(stage);started=time.monotonic();log=stage/(name+'.log')
    with log.open('xb') as stream:
        result=subprocess.run(list(map(str,command)),cwd=cwd,env=env,stdout=stream,
            stderr=subprocess.STDOUT,timeout=timeout)
    guard(stage)
    if result.returncode:raise RuntimeError('common phase failed; preserve '+str(log))
    return {'log':str(log),'logSha256':digest(log),'elapsedMs':round((time.monotonic()-started)*1000)}

def zip_bytes(files):
    output=BytesIO()
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(files.items()):
            if name.startswith('/') or '..' in Path(name).parts:raise ValueError('unsafe archive member')
            item=zipfile.ZipInfo(name,(2026,10,20,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(item,data)
    return output.getvalue()

def copy_inputs(stage,name,destination):
    import shutil
    guard(stage);source=stage/(name+'-source');destination.mkdir(exist_ok=False)
    for row in json.loads((stage/(name+'-inputs.json')).read_text('utf-8')):
        target=destination/row['path'];target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source/row['path'],target)
        if digest(target)!=row['sha256']:raise ValueError('short source copy differs')

def elf16(data,abi):
    expected={'arm64-v8a':183,'x86_64':62,'armeabi-v7a':40}[abi]
    if len(data)<64 or data[:4]!=b'\x7fELF' or data[5]!=1:raise ValueError('not little-endian ELF')
    kind,machine=struct.unpack_from('<HH',data,16)
    if kind!=3 or machine!=expected:raise ValueError('ELF kind/ABI mismatch')
    if data[4]==2:
        start=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
        fmt='<IIQQQQQQ'
    elif data[4]==1:
        start=struct.unpack_from('<I',data,28)[0];size,count=struct.unpack_from('<HH',data,42)
        fmt='<IIIIIIII'
    else:raise ValueError('ELF class')
    loads=0
    for i in range(count):
        values=struct.unpack_from(fmt,data,start+i*size)
        ptype=values[0]
        if data[4]==2:offset,vaddr,align=values[2],values[3],values[7]
        else:offset,vaddr,align=values[1],values[2],values[7]
        if ptype==1:
            if align<16384 or offset%align!=vaddr%align:raise ValueError('ELF 16KiB alignment')
            loads+=1
    if not loads:raise ValueError('ELF has no load segments')
