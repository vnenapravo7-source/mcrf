"""Run from a separate, on-link computer while the strict LAN fixture is ready.

Supply the fixture password via stdin. This client neither invokes ADB nor
uses existing credentials. The Android fixture independently counts egress.
"""
import argparse
import base64
import http.client
import ipaddress
import json
import re
import socket
import sys


def exact(stream, count):
    data = bytearray()
    while len(data) < count:
        part = stream.recv(count - len(data))
        if not part:
            raise ConnectionResetError("Proxy closed the connection")
        data.extend(part)
    return bytes(data)


def check_remote(address, port, nonce, password):
    destination = "198.18.0.3"
    user = b"p1-owned"
    password = password.encode("ascii")
    url = f"http://{destination}/{nonce}-http"
    valid = "Basic " + base64.b64encode(user + b":" + password).decode()
    wrong = "Basic " + base64.b64encode(user + b":wrong").decode()
    for credential in (None, wrong, valid):
        connection = http.client.HTTPConnection(address, port, timeout=5)
        try:
            try:
                headers = {} if credential is None else {"Proxy-Authorization": credential}
                connection.request("GET", url, headers=headers)
                response = connection.getresponse()
                assert response.status == (200 if credential == valid else 407)
                if credential == valid:
                    assert response.read(128) == nonce.encode("ascii")
            except (ConnectionResetError, BrokenPipeError, http.client.RemoteDisconnected):
                assert credential != valid
        finally:
            connection.close()

    with socket.create_connection((address, port), timeout=5) as proxy:
        proxy.sendall(bytes([5, 1, 0]))
        assert exact(proxy, 2) == bytes([5, 255])
    for secret in (b"wrong", password):
        with socket.create_connection((address, port), timeout=5) as proxy:
            try:
                proxy.sendall(bytes([5, 1, 2]))
                assert exact(proxy, 2) == bytes([5, 2])
                proxy.sendall(bytes([1, len(user)]) + user + bytes([len(secret)]) + secret)
                status = exact(proxy, 2)
                assert status[0] == 1
                if secret != password:
                    assert status[1] != 0
                    continue
                assert status[1] == 0
            except (ConnectionResetError, BrokenPipeError):
                if secret == password:
                    raise
                continue
            proxy.sendall(bytes([5, 1, 0, 1]) + socket.inet_aton(destination) + b"\x00\x50")
            reply = exact(proxy, 4)
            assert reply[:3] == bytes([5, 0, 0])
            length = exact(proxy, 1)[0] if reply[3] == 3 else {1: 4, 4: 16}[reply[3]]
            exact(proxy, length + 2)
            request = f"GET /{nonce}-socks HTTP/1.1\r\nHost: {destination}\r\nConnection: close\r\n\r\n"
            proxy.sendall(request.encode("ascii"))
            response = bytearray()
            while True:
                part = proxy.recv(1024)
                if not part:
                    break
                response.extend(part)
                assert len(response) <= 8192
            head, body = response.split(b"\r\n\r\n", 1)
            assert head.startswith(b"HTTP/1.1 200 ") and body == nonce.encode("ascii")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True, help="IPv4 of the owned Android device")
    parser.add_argument("--ready", required=True, help="JSON after the STRICT_LAN_READY marker")
    args = parser.parse_args()
    ready = json.loads(args.ready)
    address = ipaddress.IPv4Address(args.address)
    assert ready["address"] == str(address) and address.is_private and not address.is_loopback
    assert ready["target"] == "198.18.0.3" and ready["targetPort"] == 80
    assert 1024 <= ready["port"] <= 65535 and re.fullmatch("[0-9a-f]{32}", ready["nonce"])
    password = sys.stdin.readline().strip()
    assert re.fullmatch("[0-9a-f]{48}", password)
    check_remote(str(address), ready["port"], ready["nonce"], password)
    print("Remote HTTP and SOCKS5 nonce accepted with generated credentials; absent and wrong credentials refused.")


if __name__ == "__main__":
    main()
