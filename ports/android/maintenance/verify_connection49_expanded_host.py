"""One affected HOST phase of the WHOLE expanded P049 common batch.

No native/device acceptance or publication. Closed unrelated suites are not run.
The common caller owns the build slot; every phase rechecks current scope.
"""
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT=Path('D:/MCRF/Projects/MCRF')
GO=ROOT/'work/toolchains/go1.26.8/go/bin/go.exe'
JAVA=Path('C:/Program Files/Java/jdk-17')


def digest(path):return sha256(path.read_bytes()).hexdigest()


def verify_inputs(stage:Path)->dict:
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT,expanded_common=True)
    prep=json.loads((stage/'preparation.json').read_text('utf-8'))
    if prep.get('state')!='EXPANDED_SOURCE_FROZEN_NOT_COMPILED' or prep.get('p2Included') is not False:
        raise ValueError('wrong whole-package source snapshot')
    folders={'app':'app-source','steer':'steer-source','optional':'optional-source','tls':'tls-source','peer':'peer-source',
        'compression':'compression-source','recipes':'recipes'}
    for name,folder in folders.items():
        path=stage/(name+'-inputs.json')
        if digest(path)!=prep['manifestHashes'][name]:raise ValueError('source manifest changed')
        for row in json.loads(path.read_text('utf-8')):
            file=(stage/folder/row['path']).resolve(strict=True)
            if not file.is_relative_to((stage/folder).resolve()) or digest(file)!=row['sha256']:
                raise ValueError('frozen source input changed')
            if name=='recipes':
                live=ROOT/'ports/android/maintenance'/row['path']
                if digest(live)!=row['sha256']:raise ValueError('common recipe changed; do not run against stale snapshot')
    return prep


def call(stage,cmd,cwd,env,name,timeout=900):
    verify_inputs(stage)
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    if live['jobs']['build']!='P049_EXPANDED_COMMON:'+str(stage):raise ValueError('lost common owner')
    log=stage/(name+'.log');started=time.monotonic()
    with log.open('xb') as out:
        done=subprocess.run(list(map(str,cmd)),cwd=cwd,env=env,stdout=out,stderr=subprocess.STDOUT,timeout=timeout)
    if done.returncode:raise RuntimeError('affected common phase failed; inspect '+str(log))
    verify_inputs(stage)
    return {'log':str(log),'logSha256':digest(log),'elapsedMs':round((time.monotonic()-started)*1000)}


def go_tests(stage,cmd,cwd,env,name,required):
    result=call(stage,cmd,cwd,env,name)
    passed=set()
    for line in Path(result['log']).read_text('utf-8',errors='replace').splitlines():
        if not line.startswith('{'):continue
        event=json.loads(line)
        if event.get('Action')=='pass' and event.get('Test'):passed.add(event['Test'])
    if not set(required).issubset(passed):raise RuntimeError('scoped Go cases did not execute: '+name)
    result['executedCases']=len(passed);result['mandatoryCases']=required
    return result


def run(stage:Path,primary_stage:Path,optional_stage:Path,retain_tls:Path|None=None,reverify=frozenset())->dict:
    stage=stage.resolve(strict=True);verify_inputs(stage)
    output=stage/'expanded-host.json'
    if output.exists():raise ValueError('resume evidence explicitly; do not duplicate host phase')
    env=os.environ.copy()
    env.update(GOPATH=str(ROOT/'work/toolchains/go-home'),GOCACHE=str(ROOT/'work/toolchains/go-build-cache'),
        GOWORK='off',GOPROXY='off',GOOS='windows',GOARCH='amd64',CGO_ENABLED='0',GOFLAGS='',
        JAVA_HOME=str(JAVA),ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk',
        GRADLE_USER_HOME=str(ROOT/'work/toolchains/gradle-home'))
    tmp=stage/'gotmp';tmp.mkdir(exist_ok=True);env['GOTMPDIR']=str(tmp)
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
    result={'state':'RUNNING_EXPANDED_HOST','p2Included':False,'runtimeAccepted':False,'phoneTouched':False,'phases':{}}
    try:
        module=stage/'tls-source/steer-tls49'
        def tls_phase(key,*args):
            if retain_tls is not None and key not in reverify:
                from connection49_retained_tls import retain
                verify_inputs(stage)
                return retain(stage,retain_tls,key,args[-1])
            return go_tests(stage,*args)
        result['phases']['steerTls']=tls_phase('steerTls',[GO,'test','-mod=readonly','-count=1','-json','-timeout=180s','.'],module,env,
            'expanded-steer-tls-host',['TestAllRegisteredHelloAndALPN49','TestEveryRegisteredIdentityActualTLSAndALPN49','TestActualTLS12And13AndPolicies49',
             'TestActualTLSCertificateNegativesAndExplicitInsecure49','TestStopInterruptsActualBlockedHandshake49',
             'TestActualHelloRetryRequestP25649','TestStandardTLSReceiveDirectPreservesTLSWriter49',
             'TestActualHelloRetryRequestHybrid49',
             'TestOwnedECHAcceptedAndRejectedWithoutFallback49',
             'TestUTLSReceiveDirectCoalescedWirePreservesTLSWriter49',
             'TestEpochStoppedAndBadHandshakeDoNotLeak49','TestTLS12RealityConflictBeforeDial49',
             'TestOptionsRejectAmbiguityAndMalformed49','TestStandardRecordReaderNeverPrefetchesNextRecord49'])
        result['phases']['utlsBuffers']=tls_phase('utlsBuffers',[GO,'test','-mod=readonly',
            '-modfile='+str(stage/'tls-source/utls-tests.mod'),'-count=1','-json','-run=^TestMCRF','-timeout=60s','.'],
            stage/'tls-source/utls',env,'expanded-utls-buffers',
            ['TestMCRFBufferedRecord49','TestMCRFPendingOrderAndNoTLSWriterDetach49'])
        optional=stage/'optional-source/ports/android'
        result['phases']['xrayReference']=tls_phase('xrayReference',[GO,'test','-mod=readonly','-count=1','-json',
            '-run=^TestP049AllRegisteredFingerprintReferences$','-timeout=90s','.'],optional/'transports/xray',env,
            'expanded-xray-reference',['TestP049AllRegisteredFingerprintReferences'])
        tls=optional/'transports/tls-core'
        result['phases']['tlsCoreALPN']=tls_phase('tlsCoreALPN',[GO,'test','-mod=readonly','-modfile='+str(tls/'tls49.mod'),
            '-tags=with_utls,with_purego','-ldflags=-checklinkname=0','-count=1','-json',
            '-run=^TestP049(PinnedTLSAllFingerprintExplicitALPN|TLSAllIdentitiesActualTLSAndALPN)$','-timeout=180s','.'],tls,env,
            'expanded-tls-core-alpn',['TestP049PinnedTLSAllFingerprintExplicitALPN','TestP049TLSAllIdentitiesActualTLSAndALPN'])
        result['phases']['tlsCoreHybridHRR']=tls_phase('tlsCoreHybridHRR',[GO,'test','-mod=readonly','-modfile='+str(tls/'tls49.mod'),
            '-tags=with_utls,with_purego','-ldflags=-checklinkname=0','-count=1','-json',
            '-run=^TestMCRFTLSHybridRetry49$','-timeout=90s','github.com/sagernet/sing-box/common/tls'],tls,env,
            'expanded-tls-core-hybrid-hrr',['TestMCRFTLSHybridRetry49'])
        app=stage/'app-source/ports/android'
        if retain_tls is not None and 'jvm' not in reverify:
            from connection49_retained_tls import retain
            result['phases']['jvm']=retain(stage,retain_tls,'jvm',[])
            result['state']='PASS_EXPANDED_AFFECTED_HOST_NOT_NATIVE_OR_DEVICE_ACCEPTANCE'
            return result
        result['phases']['jvm']=call(stage,[JAVA/'bin/java.exe','-classpath','gradle/wrapper/gradle-wrapper.jar',
            'org.gradle.wrapper.GradleWrapperMain',':app:testDebugUnitTest',
            '--tests','org.mcrf.client.EngineUpdatePrimaryTest',
            ':app:compileReleaseKotlin',
            '-PtestRelease=true','-PprimaryCoreStage='+str(primary_stage),'-PprimaryCoreNativeGate=true',
            '-PoptionalCoreStage='+str(optional_stage),'-PoptionalCoreNativeGate=true',
            '--offline','--console=plain','--no-daemon','--max-workers=4'],app,env,'expanded-app-jvm',1200)
        reports=app/'app/build/test-results/testDebugUnitTest'
        required={'org.mcrf.client.EngineUpdatePrimaryTest'};seen=set();total=0
        for path in reports.glob('TEST-*.xml'):
            suite=ET.parse(path).getroot();n=int(suite.attrib.get('tests',0))
            if int(suite.attrib.get('failures',0)) or int(suite.attrib.get('errors',0)) or int(suite.attrib.get('skipped',0)):
                raise RuntimeError('affected JVM failure or skipped cases')
            if n>0:seen.add(suite.attrib.get('name'));total+=n
        if not required.issubset(seen):raise RuntimeError('new JVM cases did not run')
        result['phases']['jvm']['executedCases']=total
        result['phases']['jvm']['mandatoryCases']=sorted(required)
        result['state']='PASS_EXPANDED_AFFECTED_HOST_NOT_NATIVE_OR_DEVICE_ACCEPTANCE'
        return result
    except BaseException:
        result['state']='FAILED_PRESERVED';raise
    finally:
        output.write_text(json.dumps(result,indent=2),encoding='utf-8')
