"""Queued NEW common-P1 peer cases. Never opens a listener or contacts a site.
Run only after whole-P1 source readiness, in the common immutable batch.
"""
import json
import pathlib
import socket
import struct
import threading
import time
import unittest
import uuid

import p1_owned_peer as peer

NONCE = '0123456789abcdef0123456789abcdef'
IDENTITY = uuid.UUID('00000000-0000-4000-8000-000000000001')
PAYLOAD = b'x' * 96


def question(channel, kind):
    name = f'{NONCE}.{channel}.owned.invalid'
    labels = b''.join(bytes([len(label)]) + label.encode() for label in name.split('.')) + b'\0'
    return bytes.fromhex('123401000001000000000000') + labels + struct.pack('!HH', kind, 1)


def header(command, address, port):
    return b'\0' + IDENTITY.bytes + b'\0' + struct.pack('!BHB', command, port, 1 if address.version == 4 else 3) + address.packed


class OwnedPeerTests(unittest.TestCase):
    def test_persistent_dns_capacity_leaves_bounded_http_room(self):
        active = set()
        for _ in range(8):
            self.assertTrue(peer.admit(active, object()))
        http = object()
        self.assertTrue(peer.admit(active, http))
        self.assertIn(http, active)
        while len(active) < peer.MAX_ACTIVE:
            self.assertTrue(peer.admit(active, object()))
        self.assertEqual(16, len(active))
        self.assertFalse(peer.admit(active, object()))
        active.remove(http)
        self.assertTrue(peer.admit(active, object()))

    @classmethod
    def setUpClass(cls):
        require_source_ready()

    def exchange(self, value):
        left, right = socket.socketpair()
        accepted, failures = [], []

        def receive():
            try:
                peer.exchange(right, IDENTITY, NONCE, PAYLOAD, time.monotonic() + 2, accepted.append)
            except peer.Refused as error:
                failures.append(str(error))
            finally:
                right.close()

        worker = threading.Thread(target=receive)
        worker.start()
        try:
            left.settimeout(3); left.sendall(value); left.shutdown(socket.SHUT_WR)
            result = bytearray()
            while True:
                try:
                    chunk = left.recv(4096)
                except ConnectionResetError:
                    # Windows resets a socket closed with unread rejected data.
                    # The test below still requires NO ACK and explicit refusal.
                    break
                if not chunk:
                    break
                result.extend(chunk)
        finally:
            left.close(); worker.join(3)
        self.assertFalse(worker.is_alive())
        return bytes(result), accepted, failures

    def test_dns_four_owned_channel_family_answers_are_numeric_and_transaction_bound(self):
        for channel in ('global', 'rule'):
            for kind in (1, 28):
                query = question(channel, kind)
                answer, observed = peer.dns_reply(query, NONCE, channel)
                self.assertEqual(kind, observed)
                self.assertEqual(query[:2], answer[:2])
                self.assertEqual(query[12:], answer[12:len(query)])
                self.assertTrue(answer.endswith(peer.TARGET4.packed if kind == 1 else peer.TARGET6.packed))

    def test_dns_rejects_other_nonce_channel_compression_type_class_and_extra_data(self):
        query = question('global', 1)
        for invalid in (query.replace(NONCE.encode(), b'f' * 32), question('rule', 1),
                        query[:12] + b'\xc0\x0c' + query[-4:], question('global', 16),
                        query[:-1] + b'\2', query + b'\0', query[:2] + b'\x81\0' + query[4:]):
            with self.assertRaises(peer.Refused):
                peer.dns_reply(invalid, NONCE, 'global')

    def test_http_ipv4_and_ipv6_have_independent_real_wire_receipts(self):
        for address in (peer.TARGET4, peer.TARGET6):
            host = str(address) if address.version == 4 else '[' + str(address) + ']'
            request = f'GET /owned/{NONCE} HTTP/1.1\r\nHost: {host}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'.encode()
            response, accepted, errors = self.exchange(header(1, address, 80) + request)
            self.assertEqual([], errors)
            self.assertEqual(1, len(accepted)); self.assertEqual(address.version, accepted[0]['family'])
            self.assertEqual(peer.hashlib.sha256(response).hexdigest(), accepted[0]['response_sha256'])
            self.assertEqual(b'\0\0HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n', response)

    def test_post_body_is_owned_and_exact_not_arbitrary_forwarding(self):
        request = f'POST /owned/{NONCE} HTTP/1.1\r\nHost: 198.18.0.2\r\nContent-Length: 96\r\nConnection: close\r\n\r\n'.encode()
        response, accepted, errors = self.exchange(header(1, peer.TARGET4, 80) + request + PAYLOAD)
        self.assertEqual([], errors); self.assertEqual(96, accepted[0]['request_body_bytes']); self.assertTrue(response.endswith(PAYLOAD))
        response, accepted, errors = self.exchange(header(1, peer.TARGET4, 80) + request + b'y' * 96)
        self.assertEqual(b'', response); self.assertEqual([], accepted); self.assertEqual(['HTTP_REQUEST'], errors)

    def test_proxy_origin_headers_are_case_insensitive_but_credentials_never_reach_peer(self):
        request = f'GET /owned/{NONCE} HTTP/1.1\r\nhost: 198.18.0.3\r\ncontent-length: 0\r\nconnection: close\r\n\r\n'.encode()
        response, accepted, errors = self.exchange(header(1, peer.HELPER4, 80) + request)
        self.assertEqual([], errors); self.assertEqual('198.18.0.3', accepted[0]['target'])
        leaked = request.replace(b'connection: close', b'proxy-authorization: Basic forbidden\r\nconnection: close')
        response, accepted, errors = self.exchange(header(1, peer.HELPER4, 80) + leaked)
        self.assertEqual(b'', response); self.assertEqual([], accepted); self.assertEqual(['HTTP_FIELDS'], errors)

    def test_unowned_identity_address_protocol_or_port_never_gets_vless_ack(self):
        import ipaddress
        values = (header(1, ipaddress.ip_address('1.1.1.1'), 80), header(1, peer.TARGET6, 443),
                  header(2, peer.TARGET4, 53), header(3, peer.TARGET4, 80),
                  b'\0' + bytes(16) + header(1, peer.TARGET4, 80)[17:])
        for value in values:
            response, accepted, errors = self.exchange(value)
            self.assertEqual(b'', response); self.assertEqual([], accepted); self.assertEqual(1, len(errors))

    def test_vless_udp_reuses_one_stream_for_exact_two_families(self):
        import ipaddress
        first, second = question('rule', 1), question('rule', 28)
        wire = header(2, ipaddress.ip_address('198.18.0.253'), 53)
        wire += struct.pack('!H', len(first)) + first + struct.pack('!H', len(second)) + second
        response, accepted, errors = self.exchange(wire)
        self.assertEqual(['EOF'], errors); self.assertEqual([1, 28], [row['qtype'] for row in accepted])
        expected = b'\0\0'
        for query in (first, second):
            answer, _ = peer.dns_reply(query, NONCE, 'rule'); expected += struct.pack('!H', len(answer)) + answer
        self.assertEqual(expected, response)

    def test_reader_rejects_size_and_absolute_deadline_before_io(self):
        left,right=socket.socketpair()
        with left as stream,right:
            reader = peer.Reader(stream, time.monotonic() - 1)
            for count in (4097, -1, 1):
                with self.assertRaises(peer.Refused):
                    reader.exact(count)


def require_source_ready():
    # Same-priority source gate, not a microtest loophole. Canonical root or a
    # fully frozen root has this identical ledger. No environment bypass switch.
    root = pathlib.Path(__file__).resolve().parents[4]
    current = json.loads((root / 'ports/android/coordination/current.json').read_text(encoding='utf-8-sig'))
    ready = json.loads((root / 'ports/android/coordination/p1-progress-2026-10-17.json').read_text(encoding='utf-8-sig'))
    if current.get('activeBatch') != 'p1-progress-2026-10-17.json' or ready.get('verificationAuthority') != 'human-20261019-p1-whole-source-before-common-verification' or not all(ready.get(key) is True for key in ('sourceReady', 'readyForBuild', 'allowCommonVerification')):
        raise SystemExit('Whole P1 source is not ready; do not run an isolated peer test')


if __name__ == '__main__':
    require_source_ready()
    unittest.main()
