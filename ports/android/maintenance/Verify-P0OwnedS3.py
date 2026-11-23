"""Existing Android S3 native pair against owned loopback HTTPS/SigV4 storage.
No compiler, public bucket/account, SSH, deployment, system trust change or phone.
The fixture CA is trusted only by the two owned child processes, never via -k.
"""
import base64
import datetime as dt
import hashlib
import hmac
import http.client
import http.server
import io
import ipaddress
import json
import pathlib
import re
import secrets
import shlex
import socket
import ssl
import sys
import subprocess
import threading
import time
import urllib.parse as url
import uuid
import xml.etree.ElementTree as ET
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

ROOT = pathlib.Path('D:/MCRF/Projects/MCRF')
ADB = 'C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5586'
PKG = 'org.mcrf.client'
LIMIT = 2 * 1024 * 1024
NS = 'http://s3.amazonaws.com/doc/2006-03-01/'


def adb(*args, check=True, timeout=30):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('Owned S3 ADB operation failed: ' + args[0])
    return result.stdout.decode('utf-8', 'replace').strip()


def idle():
    for name in (PKG, 'org.steer.prototype'):
        if re.search(r'isForeground=true|startRequested=true|startForegroundCount=[1-9]', adb('shell', 'dumpsys', 'activity', 'services', name)):
            raise RuntimeError('Existing user service: do not interrupt')
    if re.search(r'Active vpn type: [01]\b', adb('shell', 'dumpsys', 'vpn_management')):
        raise RuntimeError('Existing VPN: do not interrupt')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def protected(stage, name):
    output = adb('shell', 'am', 'instrument', '-w', '-r',
                 'org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    (stage / (name + '.log')).write_text(output, encoding='utf-8')
    match = re.search(r'INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n', output, re.S)
    if 'INSTRUMENTATION_RESULT: passed=true' not in output or 'INSTRUMENTATION_RESULT: stable=true' not in output or not match:
        raise RuntimeError('Owned S3 protected checkpoint failed')
    return match[1].strip()


def modules():
    result = {}
    for row in adb('shell', 'pm', 'list', 'packages', 'org.mcrf.engines.').splitlines():
        if not re.fullmatch(r'package:org\.mcrf\.engines\.[a-z0-9]+', row):
            raise RuntimeError('Unexpected prior module inventory')
        name = row[8:]
        path = adb('shell', 'pm', 'path', name)
        if not re.fullmatch(r'package:/data/app/\S+/base.apk', path):
            raise RuntimeError('Unexpected prior module APK path')
        result[name] = adb('shell', 'sha256sum', path[8:]).split()[0]
    return result


def receive(sock, size):
    data = bytearray()
    while len(data) < size:
        block = sock.recv(size - len(data))
        if not block:
            raise EOFError('Owned proxy closed early')
        data.extend(block)
    return bytes(data)


class Store:
    def __init__(self):
        self.lock = threading.RLock()
        self.objects = {}
        self.access = 'P0OWNED'
        self.secret = secrets.token_hex(24)
        self.bucket = 'p0-owned-bucket'
        self.prefix = 'p0/' + uuid.uuid4().hex + '/'
        self.counts = {'signed': 0, 'rejected': 0, 'PUT': 0, 'GET': 0, 'LIST': 0, 'DELETE': 0}
        self.written = self.read = 0
        self.lifecycle = None


def storage_handler(store):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *_):
            pass  # never log Authorization, object keys, payloads or credentials

        def reply(self, status, data=b'', kind='application/xml', extra=None):
            self.send_response(status)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(data)))
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(data)

        def error(self, code, name):
            root = ET.Element('Error')
            ET.SubElement(root, 'Code').text = name
            self.reply(code, ET.tostring(root))

        def signed(self):
            value = self.headers.get('Authorization', '')
            match = re.fullmatch(r'AWS4-HMAC-SHA256 Credential=([^/]+)/([0-9]{8})/([^/]+)/s3/aws4_request, SignedHeaders=([a-z0-9;-]+), Signature=([0-9a-f]{64})', value)
            if not match or match[1] != store.access:
                return False
            parsed = url.urlsplit(self.path)
            query = '&'.join(url.quote(k, safe='-_.~') + '=' + url.quote(v, safe='-_.~')
                             for k, v in sorted(url.parse_qsl(parsed.query, keep_blank_values=True)))
            headers = ''
            for name in match[4].split(';'):
                values = self.headers.get_all(name)
                if values is None:
                    return False
                headers += name + ':' + ','.join(' '.join(v.split()) for v in values) + '\n'
            content_sha = self.headers.get('x-amz-content-sha256', '')
            request = '\n'.join((self.command, parsed.path, query, headers, match[4], content_sha))
            scope = match[2] + '/' + match[3] + '/s3/aws4_request'
            string = 'AWS4-HMAC-SHA256\n' + self.headers.get('x-amz-date', '') + '\n' + scope + '\n' + hashlib.sha256(request.encode()).hexdigest()
            key = ('AWS4' + store.secret).encode()
            for item in (match[2], match[3], 's3', 'aws4_request'):
                key = hmac.new(key, item.encode(), hashlib.sha256).digest()
            return hmac.compare_digest(hmac.new(key, string.encode(), hashlib.sha256).hexdigest(), match[5])

        @staticmethod
        def chunks(stream):
            result = bytearray()
            while True:
                line = stream.readline(256)
                if not re.fullmatch(b'[0-9a-fA-F]+(?:;[^\r\n]*)?\r\n', line):
                    raise ValueError('Invalid owned chunk framing')
                size = int(line.split(b';', 1)[0].strip(), 16)
                if size == 0:
                    for _ in range(16):
                        trailer = stream.readline(512)
                        if trailer == b'\r\n':
                            return bytes(result)
                        if not trailer.endswith(b'\r\n'):
                            break
                    raise ValueError('Invalid owned trailer framing')
                if len(result) + size > LIMIT:
                    raise ValueError('Owned object limit')
                block = stream.read(size)
                if len(block) != size or stream.read(2) != b'\r\n':
                    raise ValueError('Short owned object')
                result.extend(block)

        def body(self):
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                value = self.chunks(self.rfile)
            else:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= LIMIT:
                    raise ValueError('Owned request limit')
                value = self.rfile.read(length)
                if len(value) != length:
                    raise ValueError('Short owned request')
            if 'aws-chunked' in self.headers.get('Content-Encoding', '').lower():
                value = self.chunks(io.BytesIO(value))
                if len(value) != int(self.headers['x-amz-decoded-content-length']):
                    raise ValueError('Owned decoded length mismatch')
            digest = self.headers.get('x-amz-content-sha256', '')
            if re.fullmatch('[0-9a-f]{64}', digest) and hashlib.sha256(value).hexdigest() != digest:
                raise ValueError('Owned payload signature mismatch')
            return value

        def handle_request(self):
            with store.lock:
                if not self.signed():
                    store.counts['rejected'] += 1
                    self.error(403, 'SignatureDoesNotMatch')
                    self.close_connection = True
                    return
                store.counts['signed'] += 1
            parsed = url.urlsplit(self.path)
            path = url.unquote(parsed.path)
            bucket = '/' + store.bucket
            if path != bucket and not path.startswith(bucket + '/'):
                self.error(404, 'NoSuchBucket'); return
            key = path[len(bucket):].lstrip('/')
            query = dict(url.parse_qsl(parsed.query, keep_blank_values=True))
            data = self.body() if self.command in ('PUT', 'POST') else b''
            with store.lock:
                if 'lifecycle' in query:
                    if self.command == 'GET':
                        if store.lifecycle is None:
                            self.error(404, 'NoSuchLifecycleConfiguration')
                        else:
                            self.reply(200, store.lifecycle)
                    elif self.command == 'PUT':
                        store.lifecycle = data; self.reply(200)
                    else:
                        self.error(405, 'MethodNotAllowed')
                elif self.command == 'HEAD' and not key:
                    self.reply(200, extra={'x-amz-bucket-region': 'us-east-1'})
                elif self.command == 'GET' and query.get('list-type') == '2':
                    prefix, delimiter = query.get('prefix', ''), query.get('delimiter', '')
                    if not prefix.startswith(store.prefix):
                        self.error(403, 'AccessDenied'); return
                    root = ET.Element('ListBucketResult', xmlns=NS)
                    ET.SubElement(root, 'Name').text = store.bucket
                    ET.SubElement(root, 'Prefix').text = prefix
                    ET.SubElement(root, 'IsTruncated').text = 'false'
                    groups = set()
                    for name, (payload, modified) in sorted(store.objects.items()):
                        if not name.startswith(prefix):
                            continue
                        rest = name[len(prefix):]
                        if delimiter and delimiter in rest:
                            groups.add(prefix + rest.split(delimiter, 1)[0] + delimiter); continue
                        row = ET.SubElement(root, 'Contents')
                        for field, value in (('Key', name), ('Size', str(len(payload))), ('LastModified', modified), ('ETag', '"' + hashlib.md5(payload).hexdigest() + '"')):
                            ET.SubElement(row, field).text = value
                    for name in sorted(groups):
                        ET.SubElement(ET.SubElement(root, 'CommonPrefixes'), 'Prefix').text = name
                    store.counts['LIST'] += 1
                    self.reply(200, ET.tostring(root))
                elif not key.startswith(store.prefix) or len(key) > 512:
                    self.error(403, 'AccessDenied')
                elif self.command == 'PUT':
                    if key not in store.objects and len(store.objects) >= 1024:
                        self.error(503, 'SlowDown'); return
                    store.objects[key] = (data, dt.datetime.now(dt.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'))
                    store.counts['PUT'] += 1; store.written += len(data)
                    self.reply(200, extra={'ETag': '"' + hashlib.md5(data).hexdigest() + '"'})
                elif self.command in ('GET', 'HEAD'):
                    if key not in store.objects:
                        self.error(404, 'NoSuchKey'); return
                    payload, _ = store.objects[key]
                    store.counts['GET'] += 1; store.read += len(payload)
                    self.reply(200, payload, 'application/octet-stream')
                elif self.command == 'DELETE':
                    store.objects.pop(key, None); store.counts['DELETE'] += 1; self.reply(204)
                else:
                    self.error(405, 'MethodNotAllowed')

        def do_GET(self): self.handle_request()
        def do_HEAD(self): self.handle_request()
        def do_PUT(self): self.handle_request()
        def do_DELETE(self): self.handle_request()
    return Handler


def main():
    if sys.argv[1:] not in ([], ['--udp-only']):
        raise RuntimeError('Unknown fixture arguments; no device action')
    udp_only = sys.argv[1:] == ['--udp-only']
    coordination = ROOT / 'ports/android/coordination'
    current = json.loads((coordination / 'current.json').read_text(encoding='utf-8-sig'))
    batch = json.loads((coordination / 'p0-progress-2026-10-17.json').read_text(encoding='utf-8-sig'))
    lease_path = coordination / 'emulator-lease.json'
    lease = json.loads(lease_path.read_text(encoding='utf-8-sig'))
    if current.get('activeBatch') != 'p0-progress-2026-10-17.json' or batch.get('readyForBuild') is not True:
        raise RuntimeError('Whole P0 source readiness required; no isolated micro-run')
    if lease.get('device') != SERIAL or lease.get('state') not in ('occupied_p0_r8_remaining_ui', 'occupied_p0_common_acceptance') or lease.get('owner_thread') != '01a13a9b-6754-7b13-af52-422a6eb5579c':
        raise RuntimeError('Existing sole-main owned emulator required')
    command = subprocess.run(['powershell', '-NoProfile', '-Command', f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(lease['pid'])}').CommandLine"], capture_output=True, text=True, check=True).stdout
    if not all(token in command for token in ('mcrf-prerelease-clean', '-read-only', '-no-snapshot-save', '-port 5586')):
        raise RuntimeError('Owned emulator process mismatch')
    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('Never target physical phone')
    idle()
    stage = pathlib.Path(lease['launchReceipt']).parent / ('s3-owned-' + uuid.uuid4().hex)
    stage.mkdir()
    result = {'state': 'STARTED', 'scope': 'Existing Android x86_64 S3 native pair/SigV4/HTTPS/owned TCP traffic only; NOT real provider, TUN, all ABI or AutoDeploy acceptance', 'physicalPhoneTouched': False, 'trustStoreChanged': False, 'processes': []}
    if udp_only:
        result['scope'] = 'Existing Android x86_64 S3 native pair/SigV4/HTTPS/owned SOCKS UDP only; NOT main service/TUN/real provider/all ABI. Closed TCP case is NOT repeated.'
    processes, servers, logs = [], [], []
    udp_origin = None
    before = prior_modules = None
    forward = None
    remote = 'cache/p0-s3-' + uuid.uuid4().hex
    store = Store()
    try:
        before = protected(stage, 'protected-before')
        prior_modules = modules()
        frozen = ROOT / 'work/android-integration-a6c52ec6f97149f4835a629f1a450f43'
        proof = json.loads((frozen / 'p0-artifacts.json').read_text())
        debug = frozen / 'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk'
        module = frozen / 'ports/android/engines/build/outputs/apk/s3bypass/release/engines-s3bypass-x86_64-release.apk'
        result['packageActions'] = []
        for package, path, row in ((PKG, debug, next(x for x in proof['files'] if x['kind'] == 'app-debug')), ('org.mcrf.engines.s3bypass', module, next(x for x in proof['files'] if x.get('file') == module.name))):
            if sha(path) != row['sha256']:
                raise RuntimeError('Existing P0 artifact SHA mismatch')
            installed = adb('shell', 'pm', 'path', package, check=False)
            already_exact = bool(re.fullmatch(r'package:/data/app/\S+/base.apk', installed)) and adb('shell', 'sha256sum', installed[8:]).split()[0] == row['sha256']
            if not already_exact:
                output = adb('install', '-r', '-t', str(path))
                (stage / (package + '-install.log')).write_text(output, encoding='utf-8')
                if not re.search(r'(?m)^Success\s*$', output):
                    raise RuntimeError('Same-signer owned package change refused; never uninstall')
                installed = adb('shell', 'pm', 'path', package)
                if not re.fullmatch(r'package:/data/app/\S+/base.apk', installed) or adb('shell', 'sha256sum', installed[8:]).split()[0] != row['sha256']:
                    raise RuntimeError('Installed APK differs from verified bytes')
            result['packageActions'].append({'package': package, 'alreadyExact': already_exact, 'installedThisAttempt': not already_exact})
        result['existingDebugAndS3Installed'] = True
        dump = adb('shell', 'dumpsys', 'package', 'org.mcrf.engines.s3bypass')
        match = re.search(r'\blegacyNativeLibraryDir=(/data/app/[^\s]+/lib)', dump)
        if not match or not re.search(r'\bprimaryCpuAbi=x86_64\b', dump):
            raise RuntimeError('Installed external x86_64 native library missing')
        binary = match[1] + '/x86_64/libs3bypass.so'
        expected_native = '3dc7cd17b7306606af982efa3ff6015656d74bb355cbdc8a1a753bc46a12f967'
        if adb('shell', 'sha256sum', binary).split()[0] != expected_native:
            raise RuntimeError('Installed S3 native differs from frozen ELF')
        result['nativeSha256'] = expected_native
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        now = dt.datetime.now(dt.timezone.utc)
        guest_now = dt.datetime.strptime(adb('shell', 'date', '-u', '+%Y-%m-%dT%H:%M:%SZ'), '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=dt.timezone.utc)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'P0 owned S3 fixture')])
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(min(now, guest_now) - dt.timedelta(minutes=5)).not_valid_after(max(now, guest_now) + dt.timedelta(days=1))
                .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(x)) for x in ('10.0.2.2', '127.0.0.1')]), critical=False)
                .sign(key, hashes.SHA256()))
        (stage / 'ca.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (stage / 'tls-key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(stage / 'ca.pem', stage / 'tls-key.pem')
        storage = http.server.ThreadingHTTPServer(('127.0.0.1', 0), storage_handler(store))
        storage.socket = tls.wrap_socket(storage.socket, server_side=True)
        servers.append(storage)
        threading.Thread(target=storage.serve_forever, daemon=True).start()
        nonce = secrets.token_hex(24)
        payload = secrets.token_bytes(1024 if udp_only else 65537)
        origin_hits = []
        class Origin(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                length = int(self.headers.get('Content-Length', '0'))
                data = self.rfile.read(length) if 0 <= length <= LIMIT else b''
                ok = self.path == '/' + nonce and data == payload
                origin_hits.append(ok)
                body = hashlib.sha256(data).hexdigest().encode() if ok else b'wrong owned origin request'
                self.send_response(200 if ok else 403)
                self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
        origin = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Origin)
        servers.append(origin)
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        origin_port = origin.server_port
        if udp_only:
            udp_origin = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp_origin.bind(('127.0.0.1', 0))
            udp_origin.settimeout(30)
            origin_port = udp_origin.getsockname()[1]
            def echo_owned_datagram():
                try:
                    data, address = udp_origin.recvfrom(4096)
                    origin_hits.append(data == payload)
                    if data == payload:
                        udp_origin.sendto(data, address)
                except OSError:
                    pass
            threading.Thread(target=echo_owned_datagram, daemon=True).start()
        port = 40000 + secrets.randbelow(10000)
        if f'{port:04X}' in adb('shell', 'cat', '/proc/net/tcp'):
            raise RuntimeError('Chosen owned port already present; do not bind over it')
        base = {'type': 's3', 'bucket': store.bucket, 'endpoint': f'https://10.0.2.2:{storage.server_port}', 'region': 'us-east-1', 'accessKey': store.access, 'secretKey': store.secret, 'sessionsDir': 'sessions'}
        tuning = {'pollIntervalMs': 30, 'writeIntervalMs': 5, 'idleTimeoutSec': 30, 'maxFileSizeBytes': 16384}
        client = {'log': {'loglevel': 'warning'}, 'inbounds': [{'listen': '127.0.0.1', 'port': port, 'protocol': 'socks', 'settings': {'auth': 'password', 'accounts': [{'user': 'owned', 'pass': nonce}], 'udp': udp_only}}], 'outbounds': [{'protocol': 'fedarisha', 'settings': {'storage': dict(base, prefix=store.prefix + 'fixture-user/'), 'tuning': tuning}}]}
        peer = {'log': {'loglevel': 'warning'}, 'inbounds': [{'tag': 'owned-peer', 'protocol': 'fedarisha', 'settings': {'storage': dict(base, prefix=store.prefix), 'tuning': tuning, 'clients': [{'id': 'fixture-user'}], 'webhook': {'enabled': False, 'autoSetup': False}}}], 'outbounds': [{'tag': 'deny', 'protocol': 'blackhole'}, {'tag': 'owned-origin', 'protocol': 'freedom'}], 'routing': {'rules': [{'type': 'field', 'ip': ['10.0.2.2/32'], 'port': str(origin_port), 'outboundTag': 'owned-origin'}]}}
        adb('shell', 'run-as', PKG, 'mkdir', remote)
        for role, config in (('client', client), ('peer', peer)):
            (stage / (role + '.json')).write_text(json.dumps(config), encoding='utf-8')
        for file in ('client.json', 'peer.json', 'ca.pem'):
            temp = '/data/local/tmp/' + stage.name + '-' + file
            adb('push', str(stage / file), temp)
            adb('shell', 'run-as', PKG, 'cp', temp, remote + '/' + file)
        for role in ('peer', 'client'):
            log = (stage / (role + '.log')).open('wb'); logs.append(log)
            # Android's env treats the '=' characters in randomized /data/app
            # paths as assignments. Set/export the fixture CA in the shell,
            # then exec the exact verified native path without env's parser.
            script = f'echo $$ > {remote}/{role}.pid; SSL_CERT_FILE=/data/user/0/{PKG}/{remote}/ca.pem; export SSL_CERT_FILE; exec {shlex.quote(binary)} run -config {remote}/{role}.json'
            process = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'run-as ' + PKG + ' sh -c ' + shlex.quote(script)], stdout=log, stderr=subprocess.STDOUT)
            processes.append((role, process))
        forward = int(adb('forward', 'tcp:0', 'tcp:' + str(port)))
        deadline = time.monotonic() + 20
        while True:
            if time.monotonic() >= deadline:
                raise RuntimeError('Owned SOCKS listener readiness deadline')
            if any(process.poll() is not None for _, process in processes):
                raise RuntimeError('Owned native process exited before handshake; inspect exact logs, no automatic restart')
            try:
                sock = socket.create_connection(('127.0.0.1', forward), timeout=1)
                sock.settimeout(5); sock.sendall(b'\x05\x01\x02')
                if receive(sock, 2) == b'\x05\x02':
                    sock.close(); break
                sock.close()
            except (OSError, EOFError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Owned SOCKS listener did not become ready')
                time.sleep(.1)
        print('P0_S3_OWNED_NATIVE_LISTENER_READY', flush=True)
        def socks(password):
            connection = socket.create_connection(('127.0.0.1', forward), timeout=5)
            connection.settimeout(60); connection.sendall(b'\x05\x01\x02')
            if receive(connection, 2) != b'\x05\x02': raise RuntimeError('Owned SOCKS auth method mismatch')
            connection.sendall(b'\x01\x05owned' + bytes([len(password)]) + password)
            return connection, receive(connection, 2)
        if not udp_only:
            wrong, reply = socks(b'wrong')
            wrong.close()
            if reply[0] != 1 or reply[1] == 0 or origin_hits:
                raise RuntimeError('Wrong SOCKS credential did not fail before origin access')
        connection, reply = socks(nonce.encode())
        if reply != b'\x01\x00': raise RuntimeError('Owned SOCKS credentials rejected')
        with connection:
            connection.sendall((b'\x05\x03\x00\x01' + b'\x00' * 6) if udp_only else (b'\x05\x01\x00\x01' + socket.inet_aton('10.0.2.2') + origin.server_port.to_bytes(2, 'big')))
            header = receive(connection, 4)
            if header[:3] != b'\x05\x00\x00' or header[3] not in (1, 4): raise RuntimeError('Owned S3 CONNECT failed')
            bound = receive(connection, (4 if header[3] == 1 else 16) + 2)
            if udp_only:
                relay_port = int.from_bytes(bound[-2:], 'big')
                packet = b'\x00\x00\x00\x01' + socket.inet_aton('10.0.2.2') + origin_port.to_bytes(2, 'big') + payload
                (stage / 'udp-request.bin').write_bytes(packet)
                temp = '/data/local/tmp/' + stage.name + '-udp-request.bin'
                adb('push', str(stage / 'udp-request.bin'), temp)
                adb('shell', 'run-as', PKG, 'cp', temp, remote + '/udp-request.bin')
                command = f'/system/bin/toybox nc -u -W 8 -q 10 127.0.0.1 {relay_port} < {remote}/udp-request.bin > {remote}/udp-response.bin'
                adb('shell', 'run-as ' + PKG + ' sh -c ' + shlex.quote(command), timeout=20)
                data = subprocess.run([ADB, '-s', SERIAL, 'exec-out', 'run-as', PKG, 'cat', remote + '/udp-response.bin'], capture_output=True, check=True, timeout=10).stdout
                if len(data) != len(packet) or data[:10] != packet[:10] or data[10:] != payload or origin_hits != [True]:
                    result['udpResponseBytes'] = len(data)
                    result['originDatagrams'] = len(origin_hits)
                    raise RuntimeError('Actual owned S3 SOCKS UDP datagram/hash mismatch')
                result['udpRoundtrip'] = True
            else:
                request = f'POST /{nonce} HTTP/1.1\r\nHost: 10.0.2.2:{origin.server_port}\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n\r\n'.encode()
                connection.sendall(request + payload)
                response = http.client.HTTPResponse(connection); response.begin(); data = response.read(LIMIT)
                if response.status != 200 or data != hashlib.sha256(payload).hexdigest().encode() or origin_hits != [True]:
                    raise RuntimeError('Actual S3 bidirectional payload/hash mismatch')
        if store.counts['rejected'] or not all(store.counts[name] > 0 for name in ('signed', 'PUT', 'GET', 'LIST')) or store.written <= len(payload):
            raise RuntimeError('Owned storage lacks signed object transfer proof')
        result.update(state='PASS', payloadBytes=len(payload), payloadSha256=hashlib.sha256(payload).hexdigest(), originRequests=len(origin_hits), sigV4Verified=True, tlsCertificateVerified=True, counts=dict(store.counts), objectBytesWritten=store.written, objectBytesRead=store.read)
        print('P0_S3_OWNED_PAYLOAD_PASS;BYTES=' + str(len(payload)), flush=True)
    except BaseException as error:
        result.update(state='FAILED_PRESERVED', error=type(error).__name__ + ': ' + str(error))
        raise
    finally:
        for role, process in reversed(processes):
            pid, stopped = '', False
            try:
                pid = adb('shell', 'run-as', PKG, 'cat', remote + '/' + role + '.pid', check=False)
                if re.fullmatch('[1-9][0-9]*', pid):
                    command = adb('shell', 'run-as', PKG, 'cat', '/proc/' + pid + '/cmdline', check=False)
                    if not command:
                        stopped = process.poll() is not None
                    elif binary in command and remote + '/' + role + '.json' in command:
                        adb('shell', 'run-as', PKG, 'kill', '-TERM', pid)
                        try: process.wait(timeout=15)
                        except subprocess.TimeoutExpired: pass
                        # An ADB shell exiting is not evidence that its native child died.
                        stat = adb('shell', 'run-as', PKG, 'cat', '/proc/' + pid + '/stat', check=False)
                        stopped = not stat or bool(re.match(r'^[0-9]+ \([^)]*\) Z ', stat))
                elif process.poll() is not None:
                    stopped = True  # exited before the owned PID marker could be created
            except Exception as cleanup_error:
                result.setdefault('cleanupErrors', []).append({'role': role, 'error': type(cleanup_error).__name__})
            result['processes'].append({'role': role, 'pid': pid if pid.isdigit() else None, 'exited': stopped})
        if forward is not None:
            adb('forward', '--remove', 'tcp:' + str(forward), check=False)
        for server in servers:
            server.shutdown(); server.server_close()
        if udp_origin is not None:
            udp_origin.close()
        for log in logs: log.close()
        if any(not row['exited'] for row in result['processes']):
            result['state'] = 'FAILED_OWNED_PROCESS_STILL_RUNNING'
        elif before is not None:
            try:
                result['protectedUnchanged'] = protected(stage, 'protected-after') == before
                after_modules = modules()
                result['priorModulesUnchanged'] = prior_modules is not None and all(after_modules.get(name) == value for name, value in prior_modules.items())
                if not result['protectedUnchanged'] or not result['priorModulesUnchanged']:
                    result['state'] = 'FAILED_PROTECTED_STATE_CHANGED'
            except Exception as checkpoint_error:
                result['state'] = 'FAILED_FINAL_CHECKPOINT'
                result['checkpointError'] = type(checkpoint_error).__name__
        result['finishedUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (stage / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print('P0_S3_OWNED_RESULT=' + result['state'] + ';RECEIPT=' + str(stage / 'receipt.json'), flush=True)


if __name__ == '__main__':
    main()
