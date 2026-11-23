"""ONE affected P049 common batch. No phone, publication or unfinished P2.

Current whole-source gate must already be explicitly ready. A failure retains
the stage and exact failing phase; never retries an old diagnostic producer.
Resume reuses completed phase receipts with source hashes rather than starting
another process or silently changing inputs. Failed phases require review.
"""
import argparse
import json
from pathlib import Path
import sys
import time

from connection49_common import ROOT,BUILDS,digest,save

def main():
    from connection49_source_gate import require_complete_source
    require_complete_source(ROOT,expanded_common=True)
    parser=argparse.ArgumentParser();parser.add_argument('--resume',type=Path)
    parser.add_argument('--retain-tls-evidence',type=Path)
    parser.add_argument('--retain-native-compile',type=Path)
    parser.add_argument('--reverify',action='append',choices=['steerTls','utlsBuffers','jvm'],default=[])
    args=parser.parse_args()
    current=ROOT/'ports/android/coordination/current.json'
    live=json.loads(current.read_text('utf-8-sig'))
    if any(live['jobs'].values()):raise ValueError('resume existing process/session, not a concurrent producer')
    if args.resume:
        stage=args.resume.resolve(strict=True)
        if stage.parent!=BUILDS.resolve() or not stage.name.startswith('p049-expanded-'):raise ValueError('exact expanded stage required')
        report=json.loads((stage/'expanded-common.json').read_text('utf-8'))
        if report['state'] not in ('FAILED_PRESERVED','INTERRUPTED_PRESERVED'):raise ValueError('not an interrupted common batch')
        # Failed phase output is never erased to manufacture a fresh attempt.
        # A reviewed repair needs a separately frozen source package.
        if any(p.get('state')=='FAILED_PRESERVED' for p in report['phases'].values()):
            raise ValueError('failed phase needs reviewed source repair, not automatic retry')
    else:
        from prepare_connection49_expanded import main as prepare
        stage=prepare();report={'state':'RUNNING','p2Included':False,'runtimeAccepted':False,
            'phoneTouched':False,'published':False,'phases':{},'startedUnix':time.time()}
    # Complete source verification includes frozen recipes; a mutable script is
    # never accepted merely because it points at an old source-ready directory.
    from verify_connection49_expanded_host import verify_inputs
    prep=verify_inputs(stage)
    owner='P049_EXPANDED_COMMON:'+str(stage)
    live=json.loads(current.read_text('utf-8-sig'))
    if any(live['jobs'].values()):raise ValueError('live job appeared during preparation')
    live['jobs']['build']=owner;save(current,live)
    save(stage/'expanded-common.json',report)
    try:
        parent_optional=Path(prep['parents']['optional'])
        optional=json.loads((parent_optional/'android-native.json').read_text('utf-8'))['phases']['optional']
        baseline_optional=Path(optional['stage'])
        if digest(baseline_optional/'receipt.json')!=optional['receiptSha256']:raise ValueError('host baseline optional receipt changed')
        baseline_primary=Path(prep['parents']['mainAndSteer'])/'primary-stage'
        from verify_connection49_expanded_host import run as host
        from run_connection49_expanded_host_wire import run as host_wire
        from build_connection49_expanded_native import run as native
        from build_connection49_expanded_wire import run as wire
        from package_connection49_expanded import run as package
        phases=[('host','expanded-host.json',lambda:host(stage,baseline_primary,baseline_optional,args.retain_tls_evidence,set(args.reverify))),
            ('hostWire','expanded-host-wire.json',lambda:host_wire(stage,args.retain_tls_evidence)),
            ('native','expanded-native.json',lambda:native(stage,args.retain_tls_evidence,args.retain_native_compile)),
            ('wireBuild','expanded-wire-build.json',lambda:wire(stage)),
            ('package','expanded-packaging.json',lambda:package(stage))]
        for name,filename,action in phases:
            path=stage/filename
            if name in report['phases']:
                if digest(path)!=report['phases'][name]['receiptSha256']:raise ValueError('completed phase receipt changed')
                continue
            if path.exists():raise ValueError('partial phase exists; inspect its process and receipt before resuming')
            print('P049 common phase: '+name,flush=True)
            result=action()
            report['phases'][name]={'state':result['state'],'receiptSha256':digest(path)}
            save(stage/'expanded-common.json',report)
        report['state']='PASS_COMMON_HOST_BUILD_SIGNED_NOT_ANDROID_RUNTIME_ACCEPTED'
        report['remaining']='Fresh authorized Android production wire/FD/STOP, same saved Reality server, page loads, timings and settings restoration'
    except BaseException:
        report['state']='FAILED_PRESERVED';raise
    finally:
        report['finishedUnix']=time.time();save(stage/'expanded-common.json',report)
        live=json.loads(current.read_text('utf-8-sig'))
        if live['jobs']['build']==owner:live['jobs']['build']=None;save(current,live)
    print('P049 expanded common: '+str(stage),flush=True)

if __name__=='__main__':main()
