"""Inactive owned P2 SOCKS/TLS wire peer, never a provider or phone controller.

Requires the exact OPEN common P2 gate. Listens ONLY on loopback, serves one
nonce-authenticated SOCKS CONNECT to a synthetic address, and terminates TLS
on that SAME connection with fixture-supplied certificates. It never forwards
to an Internet target, installs a root, touches a TUN/phone or disables trust.
Its receipt is peer-side evidence only, not native/Android acceptance.
"""
import argparse
import hashlib
import hmac
import ipaddress
import json
import os
import pathlib
import re
import socket
import ssl
import time


def ordinary(path, maximum):
    path = pathlib.Path(path).absolute()
    if path.resolve() != path or any(parent.is_symlink() for parent in (path, *path.parents)):
        raise ValueError('Ordinary nonredirected fixture input required')
    if not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError('Bounded fixture input required')
    return path


def gate(path, digest):
    source = ordinary(path, 262144)
    raw = source.read_bytes()
    if source.name != 'p2-progress-2026-10-20.json' or not re.fullmatch(r'[0-9a-f]{64}', digest) or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError('Exact common P2 source gate required')
    data = json.loads(raw.decode('utf-8-sig'))
    if data.get('priority') != 2 or data.get('verificationAuthority') != 'human-20261020-p2-whole-source-before-common-verification':
        raise ValueError('P2 source authority required')
    if any(data.get(flag) is not True for flag in ('sourceReady', 'readyForBuild', 'allowCommonVerification', 'nativeSourceReady', 'allowNativeCandidateBuild')):
        raise ValueError('Finish ALL P2 source before wire fixtures')
    if sorted(item.get('id', -1) for item in data.get('items', [])) != [2, 6, 7, 11, 20] or any(item.get('sourceReady') is not True for item in data['items']):
        raise ValueError('ALL named P2 items must be source-ready')
    return source


def exact(stream, count, deadline):
    data = bytearray()
    while len(data) < count:
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError('Owned absolute deadline')
        stream.settimeout(min(5, left))
        part = stream.recv(count - len(data))
        if not part:
            raise EOFError('Owned stream closed')
        data.extend(part)
    return bytes(data)


def exclusive_json(path, value):
    # Paths belong to a caller-created empty fixture directory. Never overwrite.
    with path.open('x', encoding='utf-8') as output:
        json.dump(value, output, indent=2)
        output.write('\n')
    os.chmod(path, 0o600)


def exchange(stream, context, nonce, case, family, evidence, request_event=None):
    deadline = time.monotonic() + 12
    read = lambda count: exact(stream, count, deadline)
    version, count = read(2)
    if version != 5 or count not in range(1, 17) or 2 not in read(count):
        raise ValueError('Owned SOCKS auth required')
    stream.sendall(b'\x05\x02')
    if read(1) != b'\x01':
        raise ValueError('Owned auth version')
    user = read(read(1)[0])
    password = read(read(1)[0])
    if user != b'owned' or not hmac.compare_digest(password, nonce.encode('ascii')):
        raise ValueError('Owned nonce mismatch')
    stream.sendall(b'\x01\x00')
    version, command, reserved, kind = read(4)
    if (version, command, reserved) != (5, 1, 0) or kind != (1 if family == 4 else 4):
        raise ValueError('Only owned CONNECT admitted')
    address = ipaddress.ip_address(read(4 if family == 4 else 16))
    if str(address) != ('198.18.0.2' if family == 4 else 'fd89::2') or read(2) != b'\x01\xbb':
        raise ValueError('No Internet/alternate target admitted')
    evidence['socks_connect'] = True
    stream.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x01')
    with context.wrap_socket(stream, server_side=True) as secured:
        if secured.version() != 'TLSv1.3':
            raise ValueError('Real TLS13 required')
        evidence['tls13'] = True
        request = bytearray()
        while not request.endswith(b'\r\n\r\n'):
            request.extend(exact(secured, 1, deadline))
            if len(request) > 4096:
                raise ValueError('Owned HTTP request too large')
        if not request.startswith(b'GET /owned HTTP/1.1\r\n') or b'\r\nHost: owned.example.test\r\n' not in request:
            raise ValueError('Owned HTTP request mismatch')
        evidence['https_request'] = True
        if request_event:
            request_event()
        response = {
            'ok': b'HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n',
            'redirect': b'HTTP/1.1 302 Found\r\nLocation: https://never-follow.example.test/\r\nContent-Length: 0\r\n\r\n',
            'truncated': b'HTTP/1.1 204 No Content\r\n',
            'body': b'HTTP/1.1 204 No Content\r\nContent-Length: 1\r\n\r\nx',
            'malformed': b'HTTP/1.1 204 No Content\r\nBad Header: x\r\n\r\n',
            'trickle': b'HTTP/1.1 204 No Content\r\nX-Owned: deliberately-slow\r\n\r\n',
        }.get(case)
        if response is None:
            raise ValueError('Negative certificate case reached HTTP unexpectedly')
        if case == 'trickle':
            for value in response:
                if time.monotonic() >= deadline:
                    raise TimeoutError('Owned absolute deadline')
                secured.sendall(bytes((value,)))
                time.sleep(0.08)
        else:
            secured.sendall(response)
        evidence['response_sent'] = True


def main():
    parser = argparse.ArgumentParser()
    for name in ('source-ready-receipt', 'source-ready-sha256', 'certificate', 'private-key', 'nonce', 'output-directory'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--case', choices=('ok', 'wrong-root', 'wrong-host', 'redirect', 'truncated', 'body', 'malformed', 'trickle'), required=True)
    parser.add_argument('--family', type=int, choices=(4, 6), default=4)
    parser.add_argument('--ready-stdout', action='store_true')
    parser.add_argument('--events-stdout', action='store_true')
    args = parser.parse_args()
    ready = gate(args.source_ready_receipt, args.source_ready_sha256)
    if not re.fullmatch(r'[0-9a-f]{32}', args.nonce):
        raise ValueError('Fresh owned nonce32 required')
    certificate, key = ordinary(args.certificate, 65536), ordinary(args.private_key, 65536)
    output = pathlib.Path(args.output_directory).absolute()
    if output.resolve() != output or not output.is_dir() or any(parent.is_symlink() for parent in (output, *output.parents)) or any(output.iterdir()):
        raise ValueError('Caller-created empty ordinary fixture output directory required')
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(str(certificate), str(key))
    context.set_alpn_protocols(['http/1.1'])
    evidence = dict(state='peer_finished_not_native_acceptance', case=args.case, family=args.family,
                    nonceSha256=hashlib.sha256(args.nonce.encode('ascii')).hexdigest(),
                    certificateSha256=hashlib.sha256(certificate.read_bytes()).hexdigest(),
                    sourceReadySha256=args.source_ready_sha256, socks_connect=False, tls13=False,
                    https_request=False, response_sent=False, sni_matches=False, peer_closed=False)
    context.set_servername_callback(lambda stream, name, ctx: evidence.update(sni_matches=name == 'owned.example.test'))
    # One bounded connection only. No persistent server, background polling,
    # arbitrary destination DNS or forwarding to a provider.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(20)
            exclusive_json(output / 'ready.json', dict(host='127.0.0.1', port=listener.getsockname()[1],
                case=args.case, family=args.family, sourceReadySha256=args.source_ready_sha256))
            if args.ready_stdout:
                print(json.dumps(dict(host='127.0.0.1', port=listener.getsockname()[1],
                    case=args.case, family=args.family, sourceReadySha256=args.source_ready_sha256)), flush=True)
            stream, remote = listener.accept()
            with stream:
                stream.settimeout(5)
                if remote[0] != '127.0.0.1':
                    raise ValueError('Loopback fixture only')
                try:
                    exchange(stream, context, args.nonce, args.case, args.family, evidence,
                        (lambda: print('P2_PEER_TLS_REQUEST', flush=True)) if args.events_stdout else None)
                except (ssl.SSLError, EOFError, ConnectionError, TimeoutError) as error:
                    # Closed TLS is expected for negative inputs. Never label
                    # it a passing native refusal without that process's result.
                    evidence['stream_end'] = type(error).__name__
    finally:
        evidence['peer_closed'] = True
        evidence['sourceStable'] = hashlib.sha256(ready.read_bytes()).hexdigest() == args.source_ready_sha256
        exclusive_json(output / 'peer-receipt.json', evidence)


if __name__ == '__main__':
    main()
