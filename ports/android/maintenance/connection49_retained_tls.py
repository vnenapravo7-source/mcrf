"""Retain ONLY closed unchanged TLS/reference suites with full consumer trees.

Private local module replacement paths differ in fresh immutable directories.
Exactly TWO named .mod files may rebase that one old/new absolute stage path;
all other TLS source/dependency/test bytes and inventories must remain exact.
The Xray reference has its own exact whole Xray+bridge source/graph boundary.
No failed or skipped suite is promoted; no native/phone claim is made here.
"""
from hashlib import sha256
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from connection49_common import BUILDS,digest

def retain(stage:Path,previous:Path,phase:str,required:list)->dict:
    previous=previous.resolve(strict=True)
    if previous.parent!=BUILDS.resolve() or not previous.name.startswith('p049-expanded-') or previous==stage:
        raise ValueError('exact prior common stage required')
    if phase=='xrayReference':return retain_xray(stage,previous,required)
    if phase=='jvm':return retain_jvm(stage,previous)
    if phase in ('tlsCoreALPN','tlsCoreHybridHRR'):return retain_optional_tls(stage,previous,phase,required)
    if phase not in ('steerTls','utlsBuffers'):raise ValueError('retention limited to closed unchanged suites')
    prep=json.loads((previous/'preparation.json').read_text('utf-8'))
    manifest=previous/'tls-inputs.json'
    if digest(manifest)!=prep['manifestHashes']['tls']:raise ValueError('retained TLS manifest changed')
    old={r['path']:r['sha256'] for r in json.loads(manifest.read_text('utf-8'))}
    new={r['path']:r['sha256'] for r in json.loads((stage/'tls-inputs.json').read_text('utf-8'))}
    if set(old)!=set(new):raise ValueError('TLS tree inventory differs; retained checks invalid')
    rebased=[]
    owned_bounds_only=False
    for name,expected in old.items():
        source=(previous/'tls-source'/name).resolve(strict=True)
        if not source.is_relative_to((previous/'tls-source').resolve()) or digest(source)!=expected:
            raise ValueError('retained TLS source changed')
        data=source.read_bytes()
        if expected!=new[name]:
            if name=='steer-tls49/reality_wire49_test.go':
                # ONLY inactive owned-peer fixture helper changes. The closed
                # general TLS/HRR/PKI suites do not call this helper. Require
                # the exact reviewed one-condition change, not a file waiver.
                before=b'if len(reply) >= 2+int(reply[1])+12 && reply[0] == 0 {'
                after=b'if len(reply) >= 2 && len(reply) >= 2+int(reply[1])+12 && reply[0] == 0 {'
                if data.count(before)!=1:raise ValueError('owned reply test repair layout differs')
                data=data.replace(before,after);owned_bounds_only=True
            elif name not in ('steer-tls49/go.mod','utls-tests.mod'):
                raise ValueError('TLS source/test changed; must verify affected suites: '+name)
            else:
                before=(previous/'tls-source/utls').as_posix().encode()
                after=(stage/'tls-source/utls').as_posix().encode()
                # utf8 module text contains normalized forward slashes only.
                if data.count(before)!=1:raise ValueError('unexpected module replacement rebasing')
                data=data.replace(before,after);rebased.append(name)
        if sha256(data).hexdigest()!=new[name]:raise ValueError('TLS source not equivalent')
    row=passed_phase(previous,phase,required)
    return dict(row,retainedFrom=str(previous),retainedHostReceiptSha256=digest(previous/'expanded-host.json'),
        productionTLSInputsExact=True,ownedWireFixtureBoundsOnly=owned_bounds_only,
        onlyRebasedModuleFiles=rebased,newExecution=False)

def passed_phase(previous,phase,required):
    host=previous/'expanded-host.json';row=json.loads(host.read_text('utf-8'))['phases'][phase]
    if row['mandatoryCases']!=required or row['executedCases']<len(required):raise ValueError('retained suite admission differs')
    log=Path(row['log'])
    if digest(log)!=row['logSha256']:raise ValueError('retained test log changed')
    passed=set();package_pass=False
    for line in log.read_text('utf-8').splitlines():
        if not line.startswith('{'):continue
        event=json.loads(line)
        if event.get('Action')=='fail':raise ValueError('a failed suite cannot be retained')
        if event.get('Action')=='pass':
            if event.get('Test'):passed.add(event['Test'])
            else:package_pass=True
    if not package_pass or not set(required).issubset(passed):raise ValueError('retained suite did not actually pass')
    return row

def retain_xray(stage,previous,required):
    prefixes=('ports/android/transports/xray/','ports/android/optional-cores/bridge/')
    prep=json.loads((previous/'preparation.json').read_text('utf-8'))
    old_manifest=previous/'optional-inputs.json'
    if digest(old_manifest)!=prep['manifestHashes']['optional']:raise ValueError('old optional manifest changed')
    old={r['path']:r['sha256'] for r in json.loads(old_manifest.read_text('utf-8')) if r['path'].startswith(prefixes)}
    new={r['path']:r['sha256'] for r in json.loads((stage/'optional-inputs.json').read_text('utf-8')) if r['path'].startswith(prefixes)}
    if not old or old!=new:raise ValueError('Xray reference/bridge source differs; retained result invalid')
    for path,expected in old.items():
        file=(previous/'optional-source'/path).resolve(strict=True)
        if not file.is_relative_to((previous/'optional-source').resolve()) or digest(file)!=expected:
            raise ValueError('retained Xray source changed')
    row=passed_phase(previous,'xrayReference',required)
    return dict(row,retainedFrom=str(previous),retainedHostReceiptSha256=digest(previous/'expanded-host.json'),
        xrayAndBridgeTreeExact=True,pinnedModuleGraphsExact=True,newExecution=False)

def retain_optional_tls(stage,previous,phase,required):
    prep=json.loads((previous/'preparation.json').read_text('utf-8'))
    file=previous/'optional-inputs.json'
    if digest(file)!=prep['manifestHashes']['optional']:raise ValueError('old optional source manifest differs')
    old={r['path']:r['sha256'] for r in json.loads(file.read_text('utf-8'))}
    new={r['path']:r['sha256'] for r in json.loads((stage/'optional-inputs.json').read_text('utf-8'))}
    if old.keys()!=new.keys():raise ValueError('optional source inventory changed')
    rebased=[]
    for name,expected in old.items():
        source=(previous/'optional-source'/name).resolve(strict=True)
        if not source.is_relative_to((previous/'optional-source').resolve()) or digest(source)!=expected:
            raise ValueError('old optional source changed')
        data=source.read_bytes()
        if expected!=new[name]:
            if name!='ports/android/transports/tls-core/tls49.mod':raise ValueError('optional code/test/dependency changed')
            before=(previous/'optional-source').as_posix().encode();after=(stage/'optional-source').as_posix().encode()
            if data.count(before)!=2:raise ValueError('optional private module replacement layout differs')
            data=data.replace(before,after);rebased.append(name)
        if sha256(data).hexdigest()!=new[name]:raise ValueError('optional sources not equivalent')
    row=passed_phase(previous,phase,required)
    return dict(row,retainedFrom=str(previous),retainedHostReceiptSha256=digest(previous/'expanded-host.json'),
        fullOptionalTreeEquivalent=True,onlyRebasedModuleFiles=rebased,newExecution=False)

def retain_jvm(stage,previous):
    prep=json.loads((previous/'preparation.json').read_text('utf-8'))
    new_prep=json.loads((stage/'preparation.json').read_text('utf-8'))
    if prep['parents']!=new_prep['parents']:raise ValueError('JVM baseline module parents changed')
    manifest=previous/'app-inputs.json'
    if digest(manifest)!=prep['manifestHashes']['app']:raise ValueError('old JVM app manifest changed')
    old={r['path']:r['sha256'] for r in json.loads(manifest.read_text('utf-8'))}
    new={r['path']:r['sha256'] for r in json.loads((stage/'app-inputs.json').read_text('utf-8'))}
    if old!=new:raise ValueError('app/test/Gradle sources changed; JVM result cannot be retained')
    for name,expected in old.items():
        file=(previous/'app-source'/name).resolve(strict=True)
        if not file.is_relative_to((previous/'app-source').resolve()) or digest(file)!=expected:
            raise ValueError('retained app source changed')
    receipt=previous/'expanded-host.json';row=json.loads(receipt.read_text('utf-8'))['phases'].get('jvm')
    if row is None:
        # One exact human-stopped run completed JVM before its final source
        # gate refused continuation. Keep FAILED batch intact, retain ONLY its
        # independently successful, pinned command plus all 59 zero-skip XMLs.
        if previous.name!='p049-expanded-522db602221840429a77480509d7d8c9':
            raise ValueError('missing recorded JVM phase')
        row={'log':str(previous/'expanded-app-jvm.log'),
             'logSha256':'ba49c469349abc64ac9755537c722dae646e626c4eb65e76169e60b9543f6b6a',
             'executedCases':59,'interruptedAfterSuccessfulCommand':True}
    log=Path(row['log'])
    if digest(log)!=row['logSha256']:raise ValueError('old JVM compile log changed')
    text=log.read_text('utf-8')
    if 'BUILD SUCCESSFUL' not in text or 'BUILD FAILED' in text:raise ValueError('old JVM build not successful')
    required=set(row.get('mandatoryCases',['org.mcrf.client.ServerLinkVariations49Test','org.mcrf.client.SteerLinkEdits49Test']))
    seen=set();count=0;reports={}
    for file in (previous/'app-source/ports/android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite=ET.parse(file).getroot();n=int(suite.attrib.get('tests',0))
        if any(int(suite.attrib.get(key,0)) for key in ('failures','errors','skipped')):raise ValueError('retained JVM suite incomplete')
        if n:seen.add(suite.attrib['name']);count+=n;reports[file.name]=digest(file)
    if not required.issubset(seen) or count!=row['executedCases']:raise ValueError('retained JVM case/report count differs')
    return dict(row,retainedFrom=str(previous),retainedHostReceiptSha256=digest(receipt),
        fullAppTreeExact=True,baselineModuleParentsExact=True,retainedJUnitSha256=reports,newExecution=False)


def retain_wire(stage,previous):
    """Only complete successful wire matrix; no failed case promotion."""
    host=json.loads((previous/'expanded-host.json').read_text('utf-8'))
    tls_proof=retain(stage,previous,'steerTls',host['phases']['steerTls']['mandatoryCases'])
    optional_proof=retain_optional_tls(stage,previous,'tlsCoreALPN',host['phases']['tlsCoreALPN']['mandatoryCases'])
    prep=json.loads((previous/'preparation.json').read_text('utf-8'))
    old_file=previous/'peer-inputs.json'
    if digest(old_file)!=prep['manifestHashes']['peer']:raise ValueError('old peer manifest changed')
    rows=json.loads(old_file.read_text('utf-8'))
    if rows!=json.loads((stage/'peer-inputs.json').read_text('utf-8')):raise ValueError('owned peer source changed')
    for row in rows:
        if digest(previous/'peer-source'/row['path'])!=row['sha256']:raise ValueError('old peer source changed')
    path=previous/'expanded-host-wire.json';result=json.loads(path.read_text('utf-8'))
    if result['state']!='PASS_OWNED_HOST_REALITY_MATRIX_NOT_ANDROID_ACCEPTANCE':raise ValueError('wire matrix incomplete')
    if [row['name'] for row in result['cases']]!=['modern','modern-pq','legacy']:raise ValueError('wire matrix scope differs')
    for case in result['cases']:
        if case['state']!='PASS_OWNED_HOST_REALITY_NOT_PHONE_ACCEPTANCE' or case['peerExitCode']!=0:raise ValueError('wire case not complete')
        for key,row in case['phases'].items():
            log=Path(row['log'])
            if digest(log)!=row['logSha256']:raise ValueError('wire log changed')
            if key=='reference':
                if 'OWNED_REFERENCE49 authenticated-vless-http204 PASS' not in log.read_text('utf-8').splitlines():raise ValueError('missing independent reference')
            else:
                events=[json.loads(line) for line in log.read_text('utf-8').splitlines() if line.startswith('{')]
                if any(e.get('Action')=='fail' for e in events):raise ValueError('failed wire suite cannot be retained')
                passed={e.get('Test') for e in events if e.get('Action')=='pass'}
                if not set(row['mandatoryCases']).issubset(passed) or None not in passed:raise ValueError('wire proof incomplete')
    for row in result['peerExecutables'].values():
        if digest(Path(row['path']))!=row['sha256']:raise ValueError('owned peer executable changed')
    result.update(retainedFrom=str(previous),retainedReceiptSha256=digest(path),newExecution=False,
        sourceProof={'tls':tls_proof,'optional':optional_proof,'peerTreeExact':True})
    return result
