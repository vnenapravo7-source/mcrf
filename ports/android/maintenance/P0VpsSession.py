"""One explicitly authorized VPS session; password transient, known host required."""
import getpass
import json
import sys
import paramiko

HOST = '144.31.159.50'
KNOWN = 'D:/MCRF/Projects/MCRF/work/p0-vps-3ed776a3e07f434485dd35803197a6cf/known_hosts'

client = paramiko.SSHClient()
client.load_host_keys(KNOWN)
client.set_missing_host_key_policy(paramiko.RejectPolicy())
password = getpass.getpass('VPS password (not stored): ')
try:
    client.connect(HOST, port=22, username='root', password=password,
                   allow_agent=False, look_for_keys=False, timeout=12,
                   auth_timeout=15, banner_timeout=15)
except paramiko.AuthenticationException:
    print('VPS_AUTH_REJECTED; no remote command executed', flush=True)
    sys.exit(2)
finally:
    password = None
print('VPS_AUTHENTICATED; pinned host key matched', flush=True)
try:
    while True:
        print('VPS_COMMAND_JSON>', flush=True)
        line = sys.stdin.readline()
        if not line:
            break
        task = json.loads(line)
        if task.get('close'):
            break
        command = task['command']
        if not isinstance(command, str) or len(command) > 32768:
            raise ValueError('Bounded explicit command required')
        stdin, stdout, stderr = client.exec_command(command, timeout=60)
        stdin.close()
        output = stdout.read(24000).decode('utf-8', 'replace')
        error = stderr.read(4000).decode('utf-8', 'replace')
        code = stdout.channel.recv_exit_status()
        print(json.dumps({'exitCode': code, 'output': output, 'error': error}, ensure_ascii=False), flush=True)
finally:
    client.close()
