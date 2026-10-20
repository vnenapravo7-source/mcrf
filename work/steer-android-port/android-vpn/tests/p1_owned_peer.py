"""SOURCE ONLY. Bounded P1 VLESS/DNS peer; never forwards or connects out.

Unlike the closed schema1 fixture, accepts only nonce-bound DNS and fixed
synthetic IPv4/IPv6 HTTP targets. A coordinator must join these independently
observed exchanges to Android evidence. Merely starting this peer is no PASS.
"""
import argparse
import concurrent.futures
import hashlib
import hmac
import ipaddress
import json
import pathlib
import secrets
import socket
import struct
import threading
import time
import uuid

TARGET4 = ipaddress.ip_address('198.18.0.2')
TARGET6 = ipaddress.ip_address('fd77::2')
HELPER4 = ipaddress.ip_address('198.18.0.3')
HELPER6 = ipaddress.ip_address('fd77::3')
DNS = {'198.18.0.254': 'global', '198.18.0.253': 'rule'}
LIMIT = 64
MAX_ACTIVE = 16


def admit(active, stream):
    # Eight legitimate persistent DNS streams must leave room for the HTTP
    # alias checks. Caller holds the same lock used by handler retirement.
    if len(active) >= MAX_ACTIVE:
        return False
    active.add(stream)
    return True


class Refused(Exception):
    pass


class Reader:
    def __init__(self, stream, until):
        self.stream, self.until = stream, until

    def exact(self, count):
        if not 0 <= count <= 4096:
            raise Refused('SIZE')
        result = bytearray()
        while len(result) < count:
            remaining = self.until - time.monotonic()
            if remaining <= 0:
                raise Refused('DEADLINE')
            self.stream.settimeout(min(remaining, 8))
            chunk = self.stream.recv(count - len(result))
            if not chunk:
                raise Refused('EOF')
            result.extend(chunk)
        return bytes(result)


def dns_reply(query, nonce, channel):
    name = f'{nonce}.{channel}.owned.invalid'
    question = b''.join(bytes([len(label)]) + label.encode('ascii') for label in name.split('.')) + b'\0'
    if len(query) != 12 + len(question) + 4 or query[2:12] != bytes.fromhex('01000001000000000000'):
        raise Refused('DNS_HEADER')
    if query[12:-4] != question or query[-2:] != b'\0\1':
        raise Refused('DNS_QUESTION')
    kind = struct.unpack('!H', query[-4:-2])[0]
    if kind not in (1, 28):
        raise Refused('DNS_TYPE')
    address = TARGET4.packed if kind == 1 else TARGET6.packed
    answer = b'\xc0\x0c' + struct.pack('!HHIH', kind, 1, 30, len(address)) + address
    return query[:2] + bytes.fromhex('81800001000100000000') + query[12:] + answer, kind


def exchange(stream, identity, nonce, payload, until, record):
    reader = Reader(stream, until)
    if reader.exact(1) != b'\0' or not hmac.compare_digest(reader.exact(16), identity.bytes) or reader.exact(1) != b'\0':
        raise Refused('IDENTITY')
    command, port, kind = struct.unpack('!BHB', reader.exact(4))
    if kind not in (1, 3):
        raise Refused('ADDRESS_TYPE')
    address = ipaddress.ip_address(reader.exact(4 if kind == 1 else 16))
    if command == 2 and port == 53 and str(address) in DNS:
        stream.sendall(b'\0\0')
        # A reused VLESS UDP stream is bounded by BOTH query count and the same
        # absolute run deadline. Each answer is independently nonce validated.
        for _ in range(16):
            length = struct.unpack('!H', reader.exact(2))[0]
            query = reader.exact(length)
            answer, qtype = dns_reply(query, nonce, DNS[str(address)])
            stream.sendall(struct.pack('!H', len(answer)) + answer)
            record({'kind': 'DNS', 'channel': DNS[str(address)], 'qtype': qtype,
                    'response_sha256': hashlib.sha256(answer).hexdigest()})
        return
    if command != 1 or port != 80 or address not in (TARGET4, TARGET6, HELPER4, HELPER6):
        raise Refused('DESTINATION')
    header = bytearray()
    while not header.endswith(b'\r\n\r\n'):
        if len(header) >= 4096:
            raise Refused('HTTP_SIZE')
        header.extend(reader.exact(1))
    text = header.decode('ascii')
    host = str(address) if address.version == 4 else '[' + str(address) + ']'
    lines = text[:-4].split('\r\n')
    request = lines[0].split(' ')
    if len(request) != 3 or request[0] not in ('GET', 'POST') or request[1:] != [f'/owned/{nonce}', 'HTTP/1.1']:
        raise Refused('HTTP_REQUEST')
    fields = {}
    for line in lines[1:]:
        if ':' not in line or line[:1].isspace():
            raise Refused('HTTP_FIELDS')
        key, value = line.split(':', 1)
        key = key.lower()
        if key in fields or key not in ('host', 'content-length', 'connection'):
            raise Refused('HTTP_FIELDS')  # Proxy credentials MUST NOT reach peer.
        fields[key] = value.strip()
    method = request[0]
    size = 0 if method == 'GET' else len(payload)
    if fields != {'host': host, 'content-length': str(size), 'connection': 'close'} or size and not hmac.compare_digest(reader.exact(size), payload):
        raise Refused('HTTP_REQUEST')
    body = b'' if method == 'GET' else payload
    status = b'204 No Content' if method == 'GET' else b'200 OK'
    reply = b'\0\0HTTP/1.1 ' + status + b'\r\nContent-Length: ' + str(len(body)).encode() + b'\r\nConnection: close\r\n\r\n' + body
    stream.sendall(reply)
    record({'kind': 'HTTP', 'method': method, 'family': address.version, 'target': str(address),
            'request_body_bytes': size, 'response_sha256': hashlib.sha256(reply).hexdigest()})


def serve(args):
    host = ipaddress.ip_address(args.listen_ip)
    ranges = [ipaddress.ip_network(value) for value in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    if host.version != 4 or not any(host in network for network in ranges):
        raise Refused('EXPLICIT_PRIVATE_IPV4_REQUIRED')
    root = pathlib.Path(args.private_dir).absolute()
    parent = pathlib.Path(__file__).resolve().parent / 'runtime' / 'p1-owned'
    if root.parent.resolve() != parent.resolve() or root.name != args.run_id or not len(args.run_id) == 32 or any(c not in '0123456789abcdef' for c in args.run_id):
        raise Refused('PRIVATE_RUN_PATH')
    parent.mkdir(parents=True, exist_ok=True)
    root.mkdir(exist_ok=False)
    identity, nonce, payload = uuid.uuid4(), secrets.token_hex(16), secrets.token_bytes(96)
    report = {'schema': 2, 'accepted': [], 'refused': [], 'forwarded_connections': 0,
              'listener_closed': False, 'all_sockets_closed': False}
    lock, active = threading.Lock(), set()
    until = time.monotonic() + 120

    def record(value):
        with lock:
            if len(report['accepted']) >= 1024:
                raise Refused('REPORT_LIMIT')
            report['accepted'].append(value)

    def run(stream):
        try:
            exchange(stream, identity, nonce, payload, until, record)
        except (Refused, OSError, UnicodeError, ValueError) as error:
            with lock:
                # No arbitrary exception text: input can contain private URI data.
                report['refused'].append(str(error) if isinstance(error, Refused) else type(error).__name__)
        finally:
            stream.close()
            with lock:
                active.discard(stream)

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=MAX_ACTIVE)
    try:
        listener.bind((str(host), 0)); listener.listen(MAX_ACTIVE); listener.settimeout(.25)
        port = listener.getsockname()[1]
        private = {'schema': 2, 'uri': f'vless://{identity}@{host}:{port}?security=none&type=tcp&encryption=none',
                   'listenIpv4': str(host), 'listenPort': port, 'targetIpv4': str(TARGET4), 'targetPort': 80,
                   'nonce': nonce, 'path': '/owned/' + nonce, 'payloadHex': payload.hex(),
                   'deadlineSeconds': 120, 'maxConnections': LIMIT}
        (root / 'fixture-private.json').write_text(json.dumps(private), encoding='utf-8')
        count = 0
        while count < LIMIT and time.monotonic() < until and not (root / 'stop').exists():
            try:
                stream, _ = listener.accept()
            except socket.timeout:
                continue
            count += 1
            with lock:
                admitted = admit(active, stream)
            if admitted:
                pool.submit(run, stream)
            else:
                stream.close()
    finally:
        listener.close()
        with lock:
            sockets = list(active)
        for stream in sockets:
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()
        # Every admitted handler must execute its finally/discard, including a
        # thread which had not begun when the stop file appeared. No orphaned
        # queued socket can be declared retired merely by cancelling a Future.
        pool.shutdown(wait=True)
        report['listener_closed'] = True
        report['all_sockets_closed'] = not active
        (root / 'peer-receipt.json').write_text(json.dumps(report), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--listen-ip', required=True)
    parser.add_argument('--private-dir', required=True)
    parser.add_argument('--run-id', required=True)
    serve(parser.parse_args())
