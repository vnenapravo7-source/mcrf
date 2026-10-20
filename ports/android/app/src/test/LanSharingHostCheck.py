"""Second-device authenticated HTTP/SOCKS5 check. No public account or server is used."""
import argparse
import base64
import hashlib
import http.client
import json
import pathlib
import queue
import re
import secrets
import socket
import ssl
import subprocess
import threading
import time
import uuid

root = pathlib.Path(__file__).resolve().parents[5]
parser = argparse.ArgumentParser()
parser.add_argument('--snapshot', required=True)
parser.add_argument('--device', required=True)
parser.add_argument('--warp', action='store_true')
args = parser.parse_args()
snapshot = (root / args.snapshot).resolve()
assert snapshot.parent == root / 'work' and snapshot.name.startswith('android-integration-')
build = json.loads((snapshot / 'build-result.json').read_text(encoding='utf-8-sig'))
assert build['exitCode'] == 0 and build['sourceStable']
lease = json.loads((root / 'ports/android/coordination/device-lease.json').read_text(encoding='utf-8-sig'))
assert lease['device'] == args.device and lease['owner_thread'] == '01a13a9b-6754-7b13-af52-422a6eb5579c' and lease['state'] == 'occupied_main_fixture_acceptance'
adb = 'C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
def command(*parts, data=None):
    return subprocess.run([adb, '-s', args.device, *parts], input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30, check=True).stdout.decode(errors='replace')
installed = command('shell', 'pm', 'path', 'org.mcrf.client').strip().removeprefix('package:')
expected = snapshot / 'ports/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
assert command('shell', 'sha256sum', installed).startswith(hashlib.sha256(expected.read_bytes()).hexdigest())
test_installed = command('shell', 'pm', 'path', 'org.mcrf.client.test').strip().removeprefix('package:')
test_expected = snapshot / 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
assert command('shell', 'sha256sum', test_installed).startswith(hashlib.sha256(test_expected.read_bytes()).hexdigest())
services = command('shell', 'dumpsys', 'activity', 'services', 'org.mcrf.client')
assert not any(marker in services for marker in ['isForeground=true', 'startRequested=true', '/.McrfVpnService', '/.ProbeService', '/.WarpTraceService']), 'User service already active; no LAN fixture started'
name = f'mcrf-lan-{uuid.uuid4()}.json'
credentials = dict(username='fixture_' + secrets.token_hex(8), password=secrets.token_hex(24))
command('shell', 'run-as', 'org.mcrf.client', 'sh', '-c', f"'umask 077; cat > cache/{name}'", data=json.dumps(dict(credentials, warp=args.warp)).encode())
process = subprocess.Popen([adb, '-s', args.device, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', 'lan-sharing', '-e', 'fixture', name, 'org.mcrf.client.test/org.mcrf.client.DeviceSmokeInstrumentation'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
events = queue.Queue()
lines = []
def read():
    for line in process.stdout:
        lines.append(line)
        events.put(line)
        if 'LAN_PROGRESS ' in line: print(line.strip(), flush=True)
threading.Thread(target=read, daemon=True).start()
checks = []
failure = None
def receive(stream, size):
    data = b''
    while len(data) < size:
        chunk = stream.recv(size - len(data))
        if not chunk:
            raise RuntimeError('Unexpected proxy EOF')
        data += chunk
    return data
try:
    ready = None
    deadline = time.monotonic() + (19 * 60 if args.warp else 30)
    while time.monotonic() < deadline and process.poll() is None and ready is None:
        try:
            line = events.get(timeout=.2)
        except queue.Empty:
            continue
        if 'LAN_READY ' in line:
            ready = json.loads(line.split('LAN_READY ', 1)[1])
    assert ready is not None, 'Phone LAN fixture did not become ready'
    address, port = ready['address'], ready['port']
    assert address == args.device.rsplit(':', 1)[0], 'Unexpected LAN interface; no other endpoint contacted'
    target = f"http://{address}:{ready['origin']}/{ready['nonce']}"
    token = base64.b64encode(f"{credentials['username']}:{credentials['password']}".encode()).decode()
    for auth, expected_status in [(None, 407), ('Basic ' + base64.b64encode(b'wrong:wrong').decode(), 407), ('Basic ' + token, 200)]:
        connection = http.client.HTTPConnection(address, port, timeout=8)
        try:
            connection.request('GET', target, headers={} if auth is None else {'Proxy-Authorization': auth})
            response = connection.getresponse()
            body = response.read(8192)
            assert response.status == expected_status
            if expected_status == 200:
                assert body.decode() == ready['nonce']
        except (ConnectionError, http.client.RemoteDisconnected):
            # sing-box may reject unauthenticated HTTP by closing the connection
            # rather than returning a 407. A valid credential must still deliver
            # the exact origin body; a reset never counts as authenticated success.
            if expected_status == 200:
                raise
        finally:
            connection.close()
    checks.append('HTTP proxy refuses absent/wrong authentication (407 or closed socket) and forwards exact body with correct authentication')
    with socket.create_connection((address, port), timeout=8) as stream:
        stream.sendall(bytes([5, 1, 0]))
        assert receive(stream, 2) == bytes([5, 255])
    try:
        with socket.create_connection((address, port), timeout=8) as stream:
            stream.sendall(bytes([5, 1, 2])); assert receive(stream, 2) == bytes([5, 2])
            user, wrong = credentials['username'].encode(), b'fixture-wrong-password'
            stream.sendall(bytes([1, len(user)]) + user + bytes([len(wrong)]) + wrong)
            assert receive(stream, 2)[1] != 0
    except ConnectionError:
        pass  # A closed connection is also rejection of this deliberately wrong password.
    with socket.create_connection((address, port), timeout=8) as stream:
        stream.sendall(bytes([5, 1, 2])); assert receive(stream, 2) == bytes([5, 2])
        user, password = credentials['username'].encode(), credentials['password'].encode()
        stream.sendall(bytes([1, len(user)]) + user + bytes([len(password)]) + password)
        assert receive(stream, 2) == bytes([1, 0])
        stream.sendall(bytes([5, 1, 0, 1]) + socket.inet_aton(address) + int(ready['origin']).to_bytes(2, 'big'))
        head = receive(stream, 4); assert head[:2] == bytes([5, 0])
        address_size = receive(stream, 1)[0] if head[3] == 3 else {1: 4, 4: 16}[head[3]]
        receive(stream, address_size + 2)
        stream.sendall(f"GET /{ready['nonce']} HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n".encode())
        response = b''
        while True:
            part = stream.recv(4096)
            if not part: break
            response += part
            assert len(response) <= 8192
        assert response.split(b'\r\n\r\n', 1)[1].decode() == ready['nonce']
    checks.append('SOCKS5 refuses no-auth/wrong password and forwards exact body after username/password authentication')
    if args.warp:
        connection = http.client.HTTPSConnection(address, port, timeout=25, context=ssl.create_default_context())
        try:
            connection.set_tunnel('www.cloudflare.com', 443, headers={'Proxy-Authorization': 'Basic ' + token})
            connection.request('GET', '/cdn-cgi/trace')
            response = connection.getresponse(); assert response.status == 200
            fields = dict(line.split('=', 1) for line in response.read(8192).decode().splitlines() if '=' in line)
            assert fields.get('warp') in ('on', 'plus') and fields.get('loc') not in (None, 'RU') and fields.get('colo') not in (None, 'DME')
            assert re.fullmatch('[A-Z]{2}', fields['loc']) and re.fullmatch('[A-Z]{3}', fields['colo'])
            checks.append('Actual second-device HTTPS CONNECT over authenticated LAN proxy proves WARP exit ' + fields['loc'] + '/' + fields['colo'])
        finally:
            connection.close()
except Exception as error:
    failure = type(error).__name__ + ': ' + str(error)
finally:
    command('shell', 'run-as', 'org.mcrf.client', 'touch', f'cache/{name}.stop')
    process.wait(timeout=120)
    command('shell', 'run-as', 'org.mcrf.client', 'rm', '-f', f'cache/{name}', f'cache/{name}.stop')
output = ''.join(lines)
phone_ok = process.returncode == 0 and 'INSTRUMENTATION_CODE: -1' in output and 'FAIL ' not in output and 'passed=2' in output
if phone_ok and ready is not None:
    try:
        with socket.create_connection((ready['address'], ready['port']), timeout=1):
            failure = 'LAN listener still accepts connections after owned cleanup'
    except OSError:
        checks.append('Owned LAN proxy port no longer accepts connections after cleanup acknowledgement')
evidence = root / 'work/ports-test-2026-10-14' / ('integration-' + snapshot.name[20:24] + '-device')
evidence.mkdir(parents=True, exist_ok=True)
stem = 'lan-sharing-warp' if args.warp else 'lan-sharing'
archive = str(int(time.time() * 1000)) + '-' + uuid.uuid4().hex[:8]
for suffix in ('log', 'json'):
    previous = evidence / (stem + '.' + suffix)
    if previous.exists():
        previous.replace(evidence / (stem + '-' + archive + '-previous.' + suffix))
(evidence / (stem + '.log')).write_text(output, encoding='utf-8')
receipt = dict(passed=failure is None and phone_ok, checks=checks, failure=failure, phone_fixture_pass=phone_ok, snapshot=str(snapshot), scope='Actual second-device authenticated HTTP/SOCKS5 proxy and owned cleanup; WARP HTTPS egress only when explicitly enabled, never transparent hotspot VPN', account_registration=False, existing_warp_account=args.warp, public_servers=args.warp)
(evidence / (stem + '.json')).write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps(receipt, indent=2))
raise SystemExit(0 if receipt['passed'] else 1)
