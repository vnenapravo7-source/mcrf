"""Owned Windows wire phase of one common P049 batch, not a saved-server test."""
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import uuid

from connection49_common import ROOT,GO,guard,digest,save,environment,execute
from verify_connection49_expanded_host import go_tests

def start_peer(stage,executable,modern,pq=False,curve=''):
    env=environment();env.update(MCRF_P049_OWNED_WIRE='1',MCRF_P049_CURVE=curve,
        MCRF_P049_PQ_CERT='1' if pq else '0')
    args=[str(executable)]
    if modern:args+=['-test.run=^TestP049OwnedRealityWire$','-test.timeout=310s']
    diag=stage/('peer-diagnostics-'+('modern' if modern else 'legacy')+'-'+uuid.uuid4().hex+'.log')
    peer=subprocess.Popen(args,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
        text=True,encoding='utf-8',errors='replace')
    lines=queue.Queue(maxsize=256);diag_lock=threading.Lock();diag_bytes=[0]
    def consume(stream,public):
        for line in stream:
            if public and len(line)<8192:
                try:lines.put_nowait(line)
                except queue.Full:pass
            # No raw banner/key/config transcript. Capture only bounded errors
            # and control results; all long encoded values are redacted too.
            if line.startswith('OWNED_REALITY49=') or re.search('privatekey|mldsa65seed|BEGIN.*PRIVATE',line,re.I):continue
            if not re.search('panic|runtime error|fail|error|reference|unsupported|signature|certificate|EOF|PASS|handshake|firstLen|accepted|proxy/vless|proxy/freedom|dispatcher',line,re.I):continue
            clean=re.sub('[A-Za-z0-9_+/=-]{40,}','[redacted]',line)[:512]
            with diag_lock:
                if diag_bytes[0]+len(clean.encode('utf-8'))>65536:continue
                with diag.open('a',encoding='utf-8') as out:out.write(clean)
                diag_bytes[0]+=len(clean.encode('utf-8'))
    readers=[threading.Thread(target=consume,args=(stream,public),daemon=True)
        for stream,public in [(peer.stdout,True),(peer.stderr,False)]]
    peer._mcrf_readers49=readers;peer._mcrf_diag49=str(diag)
    for reader in readers:reader.start()
    try:
        until=time.monotonic()+15
        while time.monotonic()<until:
            try:line=lines.get(timeout=.5)
            except queue.Empty:
                if peer.poll() is not None:break
                continue
            if not line.startswith('OWNED_REALITY49='):continue
            banner=line.partition('=')[2].strip();public=json.loads(banner)
            if not re.fullmatch('[A-Za-z0-9_-]{43}',public.get('publicKey','')):raise ValueError('owned peer key format')
            if not re.fullmatch(r'127\.0\.0\.1:[1-9][0-9]{3,4}',public.get('target','')):raise ValueError('peer target is not owned loopback')
            if not 1024<=public['port']<=65535:raise ValueError('peer port')
            if bool(public.get('pqVerify'))!=pq or public.get('curve','')!=curve:raise ValueError('peer matrix configuration differs')
            return peer,banner
        raise RuntimeError('owned peer did not produce bounded startup banner; inspect '+str(diag))
    except BaseException:
        close_peer(peer);raise

def close_peer(peer):
    if peer.stdin and not peer.stdin.closed:peer.stdin.close()
    try:peer.wait(timeout=8)
    except subprocess.TimeoutExpired:
        # Exact Popen handle we created, never a guessed process tree/PID.
        peer.kill();peer.wait(timeout=5)
        raise RuntimeError('owned peer did not retire after EOF')
    finally:
        for reader in getattr(peer,'_mcrf_readers49',[]):reader.join(timeout=1)

def run(stage:Path,previous:Path|None=None)->dict:
    guard(stage);out=stage/'expanded-host-wire.json'
    if out.exists():raise ValueError('inspect existing common wire result; do not duplicate')
    if previous is not None and (previous/'expanded-host-wire.json').is_file():
        prior=json.loads((previous/'expanded-host-wire.json').read_text('utf-8'))
        if prior['state']=='PASS_OWNED_HOST_REALITY_MATRIX_NOT_ANDROID_ACCEPTANCE':
            from connection49_retained_tls import retain_wire
            result=retain_wire(stage,previous);save(out,result);return result
    result={'state':'BUILDING_OWNED_PEERS','phoneTouched':False,'savedProfilesUsed':False,
        'runtimeAccepted':False,'phases':{},'cases':[]};save(out,result)
    env=environment();env.update(GOOS='windows',GOARCH='amd64',CGO_ENABLED='0')
    peers=stage/'peers';peers.mkdir(exist_ok=False)
    try:
        legacy=peers/'legacy.exe';modern=peers/'modern.exe'
        legacy_src=stage/'peer-source/ports/android/maintenance/p049-edge-peer'
        modern_src=stage/'peer-source/ports/android/transports/xray'
        result['phases']['legacyBuild']=execute(stage,'expanded-legacy-peer-build',[
            GO,'build','-mod=readonly','-trimpath','-o',legacy,'.'],legacy_src,env)
        result['phases']['modernBuild']=execute(stage,'expanded-modern-peer-build',[
            GO,'test','-mod=readonly','-c','-ldflags=-checklinkname=0','-o',modern,'.'],modern_src,env)
        result['peerExecutables']={'legacy':{'path':str(legacy),'sha256':digest(legacy)},
            'modern':{'path':str(modern),'sha256':digest(modern)}};save(out,result)
        # Identity version determines peer compatibility. A modern server that
        # requires MLKEM cannot be made into an Edge85 server by relabelling it.
        plans=[
          ('modern',True,False,'',['chrome','firefox','safari'],['chrome']),
          ('modern-pq',True,True,'',['chrome','firefox'],[]),
          ('legacy',False,False,'',['edge','ios','qq','hellofirefox_120','hellochrome_120'],
            ['chrome','firefox','safari','edge','ios','randomized']),
        ]
        for label,is_modern,pq,curve,steer_names,tls_names in plans:
            executable=modern if is_modern else legacy
            peer,banner=start_peer(stage,executable,is_modern,pq,curve)
            case={'name':label,'peerSha256':digest(executable),'steerFingerprints':steer_names,
                'tlsCoreFingerprints':tls_names,'phases':{},'state':'RUNNING',
                'peerDiagnosticLog':peer._mcrf_diag49}
            result['cases'].append(case);save(out,result)
            try:
                configured=dict(env,MCRF_P049_OWNED_PEER=banner,MCRF_P049_FINGERPRINTS=json.dumps(steer_names))
                if not is_modern:
                    proof=execute(stage,'expanded-independent-reference-'+label,[modern,
                        '-test.run=^TestP049OwnedReference$','-test.timeout=15s'],modern_src,configured,20)
                    lines=Path(proof['log']).read_text('utf-8').splitlines()
                    if 'OWNED_REFERENCE49 authenticated-vless-http204 PASS' not in lines or 'PASS' not in lines:
                        raise RuntimeError('legacy reference did not prove authenticated traffic')
                    case['phases']['reference']=proof;save(out,result)
                module=stage/'tls-source/steer-tls49'
                case['phases']['steer']=go_tests(stage,[GO,'test','-mod=readonly','-json','-count=1',
                    '-run=^TestOwnedRealityConfigured49$','-timeout=180s','.'],module,configured,
                    'expanded-steer-reality-'+label,['TestOwnedRealityConfigured49'])
                if tls_names:
                    configured['MCRF_P049_FINGERPRINTS']=json.dumps(tls_names)
                    tls=stage/'optional-source/ports/android/transports/tls-core'
                    case['phases']['tlsCore']=go_tests(stage,[GO,'test','-mod=readonly',
                        '-modfile='+str(tls/'tls49.mod'),'-tags=with_utls,with_purego',
                        '-ldflags=-checklinkname=0','-json','-count=1',
                        '-run=^TestP049TLSRealityOwnedVariations$','-timeout=90s','.'],tls,configured,
                        'expanded-tls-core-reality-'+label,['TestP049TLSRealityOwnedVariations'])
                case['state']='PASS_OWNED_HOST_REALITY_NOT_PHONE_ACCEPTANCE'
            finally:
                close_peer(peer);case['peerExitCode']=peer.returncode
                if peer.returncode!=0:raise RuntimeError('owned peer failed before common fixture completion')
                save(out,result)
        result['state']='PASS_OWNED_HOST_REALITY_MATRIX_NOT_ANDROID_ACCEPTANCE'
        return result
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:save(out,result)
