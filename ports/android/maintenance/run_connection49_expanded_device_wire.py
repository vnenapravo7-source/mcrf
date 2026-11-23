"""New production ARM64 wire/FD checks. No app install/settings/VPN/UI mutation.

Normal self wake/unlock is handled outside this runner under the active human
grant. This runner never enters a password, grants a consent or stops a VPN.
Every fixture is a new exact executable in an exclusive owned directory.
"""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess
import time

from connection49_common import ROOT,BUILDS,SDK,digest,save
from run_connection49_expanded_host_wire import start_peer,close_peer
from verify_connection49_expanded_host import verify_inputs

ADB=SDK/'platform-tools/adb.exe'
SERIAL='R5CX73QYN3D'
OWNER='01a13a9b-6754-7b13-af52-422a6eb5579c'

def adb(*args,timeout=35):
    return subprocess.check_output([str(ADB),'-s',SERIAL,*map(str,args)],stderr=subprocess.PIPE,timeout=timeout)
def text(*args,timeout=35):return adb(*args,timeout=timeout).decode('utf-8','replace').strip()

def phone_guard(stage,claim=False):
    verify_inputs(stage)
    lease=json.loads((ROOT/'ports/android/coordination/device-lease.json').read_text('utf-8-sig'))
    authority=json.loads((ROOT/'ports/android/coordination/p0-49-phone-authority-2026-10-20.json').read_text('utf-8-sig'))
    live=json.loads((ROOT/'ports/android/coordination/current.json').read_text('utf-8-sig'))
    if lease['owner_thread']!=OWNER or lease['device']!=SERIAL:raise ValueError('main phone lease differs')
    if not authority['allowed'] or authority['completed'] or authority['device']!=SERIAL:raise ValueError('current P049 test authority missing')
    expected=None if claim else 'P049_EXPANDED_DEVICE:'+str(stage)
    if live['jobs']['device']!=expected or any(v for k,v in live['jobs'].items() if k!='device'):raise ValueError('conflicting live job')
    if text('shell','am','get-current-user')!='0':raise ValueError('unexpected Android user')
    if 'arm64-v8a' not in text('shell','getprop','ro.product.cpu.abilist').split(','):raise ValueError('wrong ABI')
    if 'mWakefulness=Awake' not in text('shell','dumpsys','power'):raise ValueError('ordinary authorized unlock required')
    if not re.search(r'(?m)^\s+showing=false\s*$',text('shell','dumpsys','window','policy')):raise ValueError('screen locked')
    vpn=text('shell','dumpsys','vpn_management').split('User 150')[0]
    if re.search(r'Active package name: \S+\s+Active vpn type: [01]\b',vpn):raise ValueError('active VPN; do not stop it')
    for package in ('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov'):
        if re.search(r'isForeground=true|startRequested=true|startForegroundCount=[1-9]',text('shell','dumpsys','activity','services',package)):
            raise ValueError('active service; do not touch it')

def run(stage):
    stage=stage.resolve(strict=True)
    if stage.parent!=BUILDS.resolve() or not re.fullmatch('p049-expanded-[a-f0-9]{32}',stage.name):raise ValueError('exact expanded stage required')
    common=json.loads((stage/'expanded-common.json').read_text('utf-8'))
    if common['state']!='PASS_COMMON_HOST_BUILD_SIGNED_NOT_ANDROID_RUNTIME_ACCEPTED':raise ValueError('whole common batch required')
    compiled=json.loads((stage/'expanded-wire-build.json').read_text('utf-8'))
    if digest(stage/'expanded-wire-build.json')!=common['phases']['wireBuild']['receiptSha256']:raise ValueError('wire receipt changed')
    fixtures={row['name']:row for row in compiled['fixtures'] if row['abi']=='arm64-v8a'}
    required={'cert_wire49_go_test','link-options49','tls-owner49','owned-reality49'}
    if set(fixtures)!=required or any(not r['productionTLS'] for r in fixtures.values()):raise ValueError('new production fixture closure missing')
    for row in fixtures.values():
        if digest(Path(row['path']))!=row['sha256']:raise ValueError('fixture bytes changed')
    dependency=next(row for row in compiled['dependencies'] if row['abi']=='arm64-v8a')
    if dependency['name']!='libmcrf_utls49.so' or digest(Path(dependency['path']))!=dependency['sha256']:
        raise ValueError('signed-module TLS helper fixture dependency differs')
    out=stage/'expanded-device-wire.json'
    if out.exists():raise ValueError('preserve prior device result; do not duplicate')
    phone_guard(stage,claim=True)
    current=ROOT/'ports/android/coordination/current.json';live=json.loads(current.read_text('utf-8-sig'))
    live['jobs']['device']='P049_EXPANDED_DEVICE:'+str(stage);save(current,live)
    remote='/data/local/tmp/m49-'+stage.name.rsplit('-',1)[1][:12]
    result={'state':'RUNNING','serial':SERIAL,'p2Included':False,'applicationSettingsTouched':False,
        'savedProfilesUsed':False,'vpnStarted':False,'appInstalled':False,'cases':[],
        'commonReceiptSha256':digest(stage/'expanded-common.json'),'remoteDirectory':remote}
    created=False;cleanup=[];pending_reverses=[];live_peers=[];save(out,result)
    try:
        phone_guard(stage)
        adb('shell','mkdir',remote);created=True
        phone_guard(stage);adb('push',dependency['path'],remote+'/'+dependency['name'])
        if text('shell','sha256sum',remote+'/'+dependency['name']).split()[0]!=dependency['sha256']:
            raise ValueError('pushed TLS helper differs')
        adb('shell','chmod','700',remote+'/'+dependency['name'])
        for name,row in fixtures.items():
            phone_guard(stage);adb('push',row['path'],remote+'/'+name)
            if text('shell','sha256sum',remote+'/'+name).split()[0]!=row['sha256']:raise ValueError('pushed fixture differs')
            adb('shell','chmod','700',remote+'/'+name)
        for name,marker in [('link-options49','LINK_OPTIONS49 aliases conflict full-fingerprint explicit-ALPN PASS'),
                            ('tls-owner49','TLS_OWNER49 blocked-stop+borrowed-fd+no-late-publish+fd-retirement PASS'),
                            ('cert_wire49_go_test','CERT_WIRE49_GO production-auth+bounded-codec+raw-transcript+negative-cert+fd-retirement PASS')]:
            phone_guard(stage);started=time.monotonic()
            output=text('shell','timeout','-s','KILL','50','env','MCRF_P049_FIXTURE_DIR='+remote,remote+'/'+name,timeout=65)
            if marker not in output.splitlines():raise ValueError('new Android fixture did not pass: '+name)
            result['cases'].append({'fixture':name,'sha256':fixtures[name]['sha256'],'state':'PASS',
                'elapsedMs':round((time.monotonic()-started)*1000)});save(out,result)
        peers=json.loads((stage/'expanded-host-wire.json').read_text('utf-8'))['peerExecutables']
        for label,modern,pq,curve,names in [
            ('modern',True,False,'',['chrome','firefox','safari']),
            ('modern-pq',True,True,'',['chrome']),
            ('legacy',False,False,'',['edge','ios','qq','hellofirefox_120'])]:
            peer_row=peers['modern' if modern else 'legacy'];executable=Path(peer_row['path'])
            if digest(executable)!=peer_row['sha256']:raise ValueError('owned peer bytes changed')
            phone_guard(stage);peer,banner=start_peer(stage,executable,modern,pq,curve);live_peers.append(peer);port=None;reverse_owned=False
            try:
                public=json.loads(banner);port=public['port'];target=int(public['target'].rsplit(':',1)[1])
                mapping='tcp:'+str(port)
                if any(mapping in row.split() for row in text('reverse','--list').splitlines()):raise ValueError('reverse port already owned')
                phone_guard(stage);adb('reverse','--no-rebind',mapping,mapping);reverse_owned=True;pending_reverses.append(mapping)
                for name in names:
                    # Prove real traffic before AND after each deliberate refusal.
                    for mode in ('fp:'+name,'fpbad:'+name,'fp:'+name):
                        phone_guard(stage);args=['shell','timeout','-s','KILL','20','env']
                        if public.get('pqVerify'):args+=['MCRF_P049_PQV='+public['pqVerify']]
                        args+=[remote+'/owned-reality49',str(port),public['publicKey'],str(target),mode]
                        started=time.monotonic();output=text(*args,timeout=25)
                        wanted='WIRE49 mode='+mode+' handshake='
                        lines=[line for line in output.splitlines() if line.startswith(wanted)]
                        if mode.startswith('fpbad:'):
                            ok=len(lines)==1 and re.fullmatch(re.escape(wanted)+r'-[0-9]+',lines[0])
                        else:ok=lines==[wanted+'0 vless=1 http204=1']
                        if not ok:raise ValueError('Android authenticated wire/refusal failed: '+label+'/'+mode)
                        result['cases'].append({'peer':label,'mode':mode,'state':'PASS',
                            'elapsedMs':round((time.monotonic()-started)*1000)});save(out,result)
            finally:
                try:
                    if reverse_owned:
                        mapping='tcp:'+str(port)
                        if any(row.split()[-2:]==[mapping,mapping] for row in text('reverse','--list').splitlines()):
                            adb('reverse','--remove',mapping)
                        pending_reverses.remove(mapping)
                finally:close_peer(peer)
        phone_guard(stage);result['state']='PASS_ANDROID_PRODUCTION_WIRE_FD_STOP_NOT_SAVED_SERVER_ACCEPTANCE'
        return result
    except BaseException:result['state']='FAILED_PRESERVED';raise
    finally:
        try:
            if created:
                # Exclusive created directory only. Never recursive/glob cleanup;
                # private ephemeral PKI leftovers are known roots-XXXXXX names.
                live_owner=json.loads(current.read_text('utf-8-sig'))['jobs']['device']
                lease=json.loads((ROOT/'ports/android/coordination/device-lease.json').read_text('utf-8-sig'))
                if live_owner!='P049_EXPANDED_DEVICE:'+str(stage) or lease['owner_thread']!=OWNER:
                    raise ValueError('ownership changed; preserve own directory for reconciliation')
                names=text('shell','ls','-1A',remote).splitlines()
                if any(n not in required|{dependency['name']} and not re.fullmatch('roots-[A-Za-z0-9]{6}',n) for n in names):
                    raise ValueError('unexpected owned-directory member; preserve for inspection')
                for name in names:adb('shell','rm','-f',remote+'/'+name);cleanup.append(name)
                adb('shell','rmdir',remote)
                result['ownedDirectoryRemoved']=True
        except BaseException:
            result['state']='FAILED_OWNED_CLEANUP_PRESERVED';raise
        finally:
            result['ownedFilesRemoved']=cleanup
            result['pendingOwnedReverseMappings']=pending_reverses
            result['ownedPeersRetired']=all(p.poll() is not None for p in live_peers)
            if pending_reverses or not result['ownedPeersRetired']:result['state']='FAILED_OWNED_CLEANUP_PRESERVED'
            save(out,result)
            live=json.loads(current.read_text('utf-8-sig'))
            # If cleanup could not be confirmed, preserve the job/receipt for
            # reconciliation rather than permitting a duplicate phone run.
            if (not created or result.get('ownedDirectoryRemoved')) and not pending_reverses and result['ownedPeersRetired'] and live['jobs']['device']=='P049_EXPANDED_DEVICE:'+str(stage):
                live['jobs']['device']=None;save(current,live)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('stage',type=Path);run(parser.parse_args().stage)
