"""Manual bounded S3 service setup. No password/key argv, source or raw logs."""
import ctypes
from ctypes import wintypes
import getpass
import hashlib
import json
import pathlib
import shlex
import sys
import paramiko

PRIVATE = pathlib.Path('D:/MCRF/Private/s3-p0-72075570f1a547fea9460f92ac81f7af')
BUILD = pathlib.Path('D:/MCRF/Builds/Android/s3bypass-linux-server-197d0d03b777418c8b2a1149d812aafe')
KNOWN = 'D:/MCRF/Projects/MCRF/work/p0-vps-3ed776a3e07f434485dd35803197a6cf/known_hosts'
REMOTE = '/opt/mcrf-s3-p0-72075570'
UNIT = 'mcrf-s3-p0-72075570.service'

class Blob(ctypes.Structure):
    _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]

def unprotect(path):
    encrypted = path.read_bytes(); buf = ctypes.create_string_buffer(encrypted)
    source = Blob(len(encrypted), ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte))); target = Blob()
    api = ctypes.windll.crypt32.CryptUnprotectData
    api.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    api.restype = wintypes.BOOL
    if not api(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)): raise ctypes.WinError()
    try: return json.loads(ctypes.string_at(target.data, target.size))
    finally: ctypes.windll.kernel32.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))

proof = json.loads((BUILD / 'receipt.json').read_text(encoding='utf-8-sig'))
if proof['state'] != 'PASS' or not proof['sourceStable'] or not proof['bucketLifecycleMutationDisabled']:
    raise SystemExit('Frozen no-lifecycle binary required')
if hashlib.sha256((BUILD / 's3bypass').read_bytes()).hexdigest() != proof['sha256']:
    raise SystemExit('Linux binary hash mismatch')
secret_data = unprotect(PRIVATE / 'credentials.dpapi')
server = secret_data['server']
if server['inbounds'][0]['settings']['storage']['bucket'] != 'outright': raise SystemExit('Wrong bucket')
if server['inbounds'][0]['settings']['webhook'] != {'enabled': False, 'autoSetup': False}: raise SystemExit('Webhook boundary changed')

ssh = paramiko.SSHClient(); ssh.load_host_keys(KNOWN); ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
password = getpass.getpass('VPS password (transient, not logged): ')
try: ssh.connect('144.31.159.50', port=22, username='root', password=password, allow_agent=False, look_for_keys=False, timeout=12, auth_timeout=15)
except paramiko.AuthenticationException:
    print('VPS_AUTH_REJECTED; no remote changes', flush=True); sys.exit(2)
finally: password = None

def command(text):
    stdin, stdout, stderr = ssh.exec_command(text, timeout=30); stdin.close()
    output, error = stdout.read(8192), stderr.read(8192)
    code = stdout.channel.recv_exit_status()
    if code: raise RuntimeError('Bounded remote operation failed with exit ' + str(code) + '; raw output suppressed')
    return output.decode('utf-8', 'replace').strip()

result = {'state': 'STARTED', 'service': UNIT, 'remote': REMOTE, 'bucket': 'outright', 'credentialContentsLogged': False,
          'runtimeMaxSeconds': 300, 'enabledAtBoot': False, 'bucketLifecycleModified': False, 'unrelatedServicesChanged': False}
try:
    command('test ! -e ' + shlex.quote(REMOTE) + ' && test ! -e /etc/systemd/system/' + UNIT)
    command('install -d -m 0711 ' + shlex.quote(REMOTE))
    with ssh.open_sftp() as sftp:
        sftp.put(str(BUILD / 's3bypass'), REMOTE + '/s3bypass')
        sftp.chmod(REMOTE + '/s3bypass', 0o555)
        with sftp.open(REMOTE + '/server.json', 'wx') as stream:
            sftp.chmod(REMOTE + '/server.json', 0o600)
            stream.write(json.dumps(server))
        sftp.put('D:/MCRF/Builds/Android/s3bypass-native-ecb18766cf934dc18458e0af616f3f37/source/LICENSE', REMOTE + '/LICENSE')
        actual = command('sha256sum ' + REMOTE + '/s3bypass').split()[0]
        if actual != proof['sha256']: raise RuntimeError('Remote binary hash mismatch')
        unit = ('[Unit]\nDescription=MCRF owned S3 P0 bounded verification\nAfter=network-online.target\n'
                '[Service]\nType=simple\nDynamicUser=yes\nNoNewPrivileges=yes\nPrivateTmp=yes\nPrivateDevices=yes\n'
                'ProtectSystem=strict\nProtectHome=yes\nProtectKernelTunables=yes\nProtectControlGroups=yes\n'
                'RestrictSUIDSGID=yes\nLockPersonality=yes\nMemoryMax=192M\nCPUQuota=50%\nTasksMax=64\n'
                'RuntimeMaxSec=300\nTimeoutStopSec=15\nKillMode=control-group\nRestart=no\n'
                'LoadCredential=config.json:' + REMOTE + '/server.json\n'
                'ExecStart=' + REMOTE + '/s3bypass run -config %d/config.json\n'
                'StandardOutput=null\nStandardError=null\n')
        with sftp.open('/etc/systemd/system/' + UNIT, 'wx') as stream: stream.write(unit)
        sftp.chmod('/etc/systemd/system/' + UNIT, 0o644)
    command('systemd-analyze verify /etc/systemd/system/' + UNIT)
    command('systemctl daemon-reload')
    status = command('systemctl show ' + UNIT + ' -p ActiveState -p SubState -p MainPID -p Result -p RuntimeMaxUSec -p DynamicUser -p UnitFileState')
    result.update(state='PREPARED_STOPPED', binarySha256=proof['sha256'], status=status)
    print('P0_REAL_S3_SERVICE=' + json.dumps(result), flush=True)
    # Keep the SSH connection available for explicit scoped status/stop commands.
    while True:
        print('SERVICE_ACTION_JSON>', flush=True)
        line = sys.stdin.readline()
        if not line: break
        action = json.loads(line)
        if action.get('action') == 'start':
            command('systemctl start ' + UNIT)
            result['startIssued'] = True
            print('OWNED_SERVICE_STARTED_BOUNDED_300SEC', flush=True)
        elif action.get('action') == 'status':
            status = command('systemctl show ' + UNIT + ' -p ActiveState -p SubState -p MainPID -p Result')
            print('OWNED_SERVICE_STATUS=' + status.replace('\n', ';'), flush=True)
        elif action.get('action') == 'stop':
            command('systemctl stop ' + UNIT)
            status = command('systemctl show ' + UNIT + ' -p ActiveState -p SubState -p MainPID -p Result')
            print('OWNED_SERVICE_STOP=' + status.replace('\n', ';'), flush=True)
            result['explicitlyStopped'] = True; result['finalStatus'] = status
        elif action.get('action') == 'close': break
        else: raise RuntimeError('Unknown action; no arbitrary remote command')
except Exception as error:
    result['state'] = 'FAILED_PRESERVED'; result['error'] = str(error)
    print('OWNED_SERVICE_FAILURE=' + json.dumps(result), flush=True)
finally:
    ssh.close(); secret_data = server = None
    (PRIVATE / 'service-receipt.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
