"""Reproducible private timing evidence; never parses raw profile/log contents."""
import json,re
from pathlib import Path
STAGE=Path('D:/MCRF/Builds/Android/p049-phone-29ab72fdecd74d03a163fb5cfa7c0349')
CASES={6822011979:('singbox-vless-first','READY, later same-profile browser proof'),
       6822085001:('singbox-vless-warm','READY same process'),
       6822299116:('singbox-vless-bs-preflight','READY, physical sites accessible'),
       6822373515:('singbox-vless-https','READY and Chrome HTTPS loaded'),
       6822453047:('singbox-csqtt-https','READY and Chrome Wikipedia HTTPS loaded'),
       6822572354:('singbox-hysteria','READY only; incorrectly named csqtt-repeat artifacts are NOT CSQTT'),
       6822647201:('singbox-s3-local','LOCAL READY only; browser did NOT load'),
       6823245459:('steer-vless-failed','FAILED before READY; native retirement stuck, own-test recovery needed'),
       6824061471:('recovery-singbox-vless','10023 READY, original full 11332-entry routing settings'),
       6824227224:('recovery-steer-vless-hup','10023 READY with actual HTTPS204; temporary compatible TUN/minimal routing'),
       6824424384:('recovery-steer-vless-tcp-failed','10023 DNS/HTTPS readiness failed; service and worker retired automatically, no manual recovery'),
       6824626995:('recovery-singbox-vless-tcp','10023 READY and Google Chrome loaded on same TCP Reality profile that failed Steer'),
       6828097889:('compat-xray-hup','10024/Xray5 READY and Chrome Wikipedia HTTPS loaded'),
       6829012084:('compat-xray-tcp','10024/Xray5 SAME TCP Reality READY and mandatory HTTPS health passed'),
       6828328143:('compat-tls5-hup-failed','10024/TLS5 native start accepted but actual HTTPS health FAILED'),
       6828746550:('compat-steer7-tcp-failed','10024/Steer7 SAME TCP Reality readiness FAILED'),
       6830047311:('followup-tls6-hup','10024/TLS6 system server-DNS correction READY and Chrome Wikipedia HTTPS loaded'),
       6830283143:('followup-steer8-tcp-failed','10024/Steer8 SAME TCP Reality FAILED; bounded native diagnostics show TLS handshake timeout, not response parser')}
PATTERN=re.compile(r'^(.+) \[Тайминги\] ([a-z0-9_.-]+)=(\d+)ms mono=(\d+) pid=(\d+)$')
def main():
    points={}
    for path in STAGE.glob('*-timings.json'):
        for line in json.loads(path.read_text('utf-8'))['timings'].splitlines():
            m=PATTERN.fullmatch(line)
            if m:
                wall,phase,ms,mono,pid=m.groups();key=(int(mono),phase,int(pid))
                points[key]={'phase':phase,'ms':int(ms),'mono':int(mono),'pid':int(pid),'wall':wall}
    groups=[];group=None
    for p in sorted(points.values(),key=lambda p:(p['mono'],p['phase'])):
        if p['phase']=='ui_queue':
            group={'startMono':p['mono'],'points':[]};groups.append(group)
        if group is not None and not group.get('ready'):
            group['points'].append(p)
            if p['phase']=='tap_to_ready':group['ready']=True
    rows=[]
    for group in groups:
        if group['startMono'] not in CASES:continue
        name,state=CASES[group['startMono']];phases={p['phase']:p['ms'] for p in group['points']}
        # Failed runs do not acquire unrelated idle direct probes/native fixtures.
        if not group.get('ready'):
            phases.pop('direct_check',None)
        row={'case':name,'candidate':10024 if group['startMono']>=6828097889 else 10023 if group['startMono']>=6824061471 else 10022,'state':state,'ready':bool(group.get('ready')),'phasesMs':phases}
        if group.get('ready'):
            independent=['profile_read','previous_core_close','bs_preflight','carrier_start','optional_core_start','routes_load','routes_compile','core_init','worker_validate','worker_start']
            row['serviceOtherMs']=phases['service_to_ready']-sum(phases.get(k,0) for k in independent) if 'steer_prepare_start' not in phases else phases['service_to_ready']-phases['steer_prepare_start']-phases.get('profile_read',0)-phases.get('previous_core_close',0)
            row['uiAndDeliveryMs']=phases['tap_to_ready']-phases['service_to_ready']
        rows.append(row)
    result={'item':49,'baseline':{'source':'original supplied videos and old app timestamps','seconds':[13,16],'controlledSameRunAB':False},
        'timingDefinition':'tap to service READY, NOT browser load or protocol availability',
        'overlap':'native_validate is inside worker_validate; tun_establish is inside worker_start for sing-box; do not add both',
        'cases':rows,'allEnginesRuntimeAccepted':False,
        'gaps':['Steer HUP HTTPS204 works but SAME TCP Reality fails with native TLS handshake timeout; local owned Android Reality PQ handshake/VLESS/204 passed. No actual remote Reality fix accepted yet. NormalSTOP5.562s, unbounded cleanup fixed but native cancellation remains slow','No saved WG/AWG, CSQTT25, WDTT, QWDTT, OLCRTC or DNSTT profile in observed 17 VPN +3 BS list','No S3 traffic proof; no VPS unit started','Xray/TLS are optional engines, Telegram WS/WarpScout auxiliary; not fabricated as timed primary VPNs','OpenFlux explicitly excluded']}
    (STAGE/'measured-timing-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps([{'case':r['case'],'ms':r['phasesMs'].get('tap_to_ready'),'ready':r['ready']} for r in rows],ensure_ascii=False))
if __name__=='__main__':main()
