"""Adopt the exact stopped owned unit for the authorized route comparison."""
import getpass
import ipaddress
import json
import pathlib
import paramiko

UNIT='mcrf-s3-p0-72075570.service'
PRIVATE=pathlib.Path('D:/MCRF/Private/s3-p0-72075570f1a547fea9460f92ac81f7af')
ssh=paramiko.SSHClient()
ssh.load_host_keys('D:/MCRF/Projects/MCRF/work/p0-vps-3ed776a3e07f434485dd35803197a6cf/known_hosts')
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
password=getpass.getpass('VPS password (transient, not logged): ')
try:ssh.connect('144.31.159.50',port=22,username='root',password=password,allow_agent=False,look_for_keys=False,timeout=12,auth_timeout=15)
finally:password=None

def command(value):
    stdin,stdout,stderr=ssh.exec_command(value,timeout=30);stdin.close()
    out=stdout.read(8192);stderr.read(8192);code=stdout.channel.recv_exit_status()
    if code:raise RuntimeError('Owned unit operation failed; raw output suppressed; exit '+str(code))
    return out.decode('utf-8','replace').strip()

receipt={'unit':UNIT,'state':'ADOPTED_STOPPED','unrelatedServicesChanged':False,'runtimeMaxSeconds':300}
try:
    digest=command('sha256sum /opt/mcrf-s3-p0-72075570/s3bypass').split()[0]
    if digest!='e6ad764ac3f64464b7ae2c512183b835454ec07e40dcc6c42c8bd3a353fc4b93':raise RuntimeError('Owned binary changed')
    state=command('systemctl show '+UNIT+' -p ActiveState -p MainPID -p RuntimeMaxUSec -p Restart -p UnitFileState')
    if not all(x in state for x in ('ActiveState=inactive','MainPID=0','RuntimeMaxUSec=5min','Restart=no','UnitFileState=static')):raise RuntimeError('Owned unit not safely stopped/bounded')
    address=command('curl --ipv4 --proto =https --max-time 15 --silent --show-error https://api.ipify.org')
    receipt['vpsObservedEgress']=str(ipaddress.ip_address(address))
    print('VPS_EGRESS='+receipt['vpsObservedEgress'],flush=True)
    while True:
        print('OWNED_UNIT_ACTION>',flush=True)
        action=json.loads(input()).get('action')
        if action=='start':command('systemctl start '+UNIT);receipt['started']=True;print('OWNED_UNIT_STARTED_300SEC',flush=True)
        elif action=='status':print(command('systemctl show '+UNIT+' -p ActiveState -p SubState -p MainPID -p Result').replace('\n',';'),flush=True)
        elif action=='stop':
            command('systemctl stop '+UNIT)
            receipt['finalStatus']=command('systemctl show '+UNIT+' -p ActiveState -p SubState -p MainPID -p Result')
            receipt['explicitlyStopped']=True;print(receipt['finalStatus'].replace('\n',';'),flush=True)
        elif action=='close':break
        else:raise RuntimeError('Unsupported action')
finally:
    ssh.close()
    (PRIVATE/'route-comparison-service-receipt.json').write_text(json.dumps(receipt),encoding='utf-8')
