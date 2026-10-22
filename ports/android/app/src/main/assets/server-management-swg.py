"""Bounded SWG management over the already authenticated SSH host. No installer or shell input."""
import base64
import hashlib
import http.client
import http.cookies
import json
import re
import socket
import ssl
import sys
import urllib.parse


def has_control(value):
    # Match Kotlin Char.isISOControl, including DEL and the C1 range.
    return any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)


def interface_targets(state):
    """Project only explicit online WG/AWG interfaces. Never expose mesh or secrets."""
    if not isinstance(state, dict):
        raise ValueError("INTERFACE_STATE")
    nodes, describe = state.get("nodes"), state.get("describe")
    if not isinstance(nodes, list) or len(nodes) > 256 or not isinstance(describe, dict):
        raise ValueError("INTERFACE_STATE")
    result, seen = [], set()
    for node in nodes:
        if not isinstance(node, dict):
            raise ValueError("INTERFACE_NODE")
        ident = node.get("id")
        if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", ident) or ident in seen:
            raise ValueError("INTERFACE_NODE")
        seen.add(ident)
        if node.get("status") != "online" or any(node.get(key) for key in ("migrating", "removing", "uninstalled", "proc_status")):
            continue
        name = node.get("name") or ident
        if not isinstance(name, str) or len(name) > 96 or has_control(name):
            raise ValueError("INTERFACE_NAME")
        interfaces = describe.get(ident, {})
        if not isinstance(interfaces, dict) or len(interfaces) > 128:
            raise ValueError("INTERFACE_STATE")
        for iface, meta in interfaces.items():
            if not isinstance(iface, str) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}", iface) or not isinstance(meta, dict):
                raise ValueError("INTERFACE_NAME")
            system = meta.get("system", False)
            if type(system) is not bool:
                raise ValueError("INTERFACE_STATE")
            if system or iface.startswith("swg_") or meta.get("tool") not in ("wg", "awg"):
                continue
            result.append({"node": ident, "node_name": name, "iface": iface, "tool": meta["tool"]})
            if len(result) > 1024:
                raise ValueError("INTERFACE_LIMIT")
    return sorted(result, key=lambda row: (row["node_name"].casefold(), row["node"], row["iface"]))


def execute(source):
    allowed = {"request_id", "action", "parameters", "confirmed", "panel_url", "certificate_sha256", "username", "password"}
    if set(source) != allowed:
        raise ValueError("REQUEST_FIELDS")
    if not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", source["request_id"]):
        raise ValueError("REQUEST_ID")
    panel_url = source["panel_url"]
    # urlsplit strips tabs/newlines: validate raw input before that normalization.
    if not isinstance(panel_url, str) or len(panel_url) > 2048 or has_control(panel_url):
        raise ValueError("PANEL_URL")
    url = urllib.parse.urlsplit(panel_url)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("PANEL_URL")
    port = url.port or 443
    if not 1 <= port <= 65535 or has_control(url.netloc + url.path):
        raise ValueError("PANEL_URL")
    fingerprint = source["certificate_sha256"]
    if not isinstance(fingerprint, str) or not re.fullmatch(r"[a-f0-9]{64}", fingerprint):
        raise ValueError("CERTIFICATE_PIN")
    for key in ("username", "password"):
        if not isinstance(source[key], str) or not 1 <= len(source[key]) <= 8192:
            raise ValueError("CREDENTIALS")
    action, parameters = source["action"], source["parameters"]
    fields = {"status": set(), "clients": set(), "interfaces": set(), "add-client": {"name"},
              "disable-client": {"client_id"}, "restart": {"node", "iface"}}
    if action not in fields or not isinstance(parameters, dict) or set(parameters) != fields[action]:
        raise ValueError("ACTION_FIELDS")
    if action not in ("status", "clients", "interfaces") and source["confirmed"] is not True:
        raise ValueError("CONFIRMATION_REQUIRED")
    if action == "restart":
        if not isinstance(parameters["node"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", parameters["node"]):
            raise ValueError("INTERFACE_NODE")
        if not isinstance(parameters["iface"], str) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}", parameters["iface"]):
            raise ValueError("INTERFACE_NAME")
    if "client_id" in parameters and not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", parameters["client_id"]):
        raise ValueError("CLIENT_ID")
    if "name" in parameters:
        name = parameters["name"]
        if not isinstance(name, str) or not name.strip() or len(name) > 96 or has_control(name):
            raise ValueError("CLIENT_NAME")
    if "expires_at" in parameters:
        expiry = parameters["expires_at"]
        if type(expiry) is not int or not 0 <= expiry <= 253402300799:
            raise ValueError("CLIENT_EXPIRY")
    cookies = {}

    def request(method, path, data=None, envelope=True):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # exact SSH-pinned certificate is checked before credentials
        connection = http.client.HTTPSConnection("127.0.0.1", port, timeout=15, context=context)
        tcp = socket.create_connection(("127.0.0.1", port), 15)
        try:
            connection.sock = context.wrap_socket(tcp, server_hostname=url.hostname)
            if hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest() != fingerprint:
                raise ValueError("TLS_PIN")
            headers = {"Host": url.netloc, "Accept": "application/json"}
            if cookies:
                headers["Cookie"] = "; ".join(k + "=" + v for k, v in cookies.items())
            payload = None if data is None else json.dumps(data).encode("utf-8")
            if payload is not None:
                headers["Content-Type"] = "application/json"
            connection.request(method, url.path.rstrip("/") + path, payload, headers)
            response = connection.getresponse()
            raw = response.read(4 * 1024 * 1024 + 1)
            if response.status in (401, 403, 404):
                raise ValueError("PANEL_HTTP_" + str(response.status))
            if len(raw) > 4 * 1024 * 1024 or response.status != 200:
                raise ValueError("PANEL_HTTP")
            for key, value in response.getheaders():
                if key.lower() == "set-cookie":
                    parsed = http.cookies.SimpleCookie()
                    parsed.load(value)
                    cookies.update({k: v.value for k, v in parsed.items()})
            decoded = json.loads(raw)
            if not isinstance(decoded, dict) or envelope and decoded.get("ok") is not True:
                raise ValueError("PANEL_REJECTED")
            return decoded.get("data") if envelope else decoded
        finally:
            connection.close()
            tcp.close()

    request("POST", "/api/login", {"username": source["username"], "password": source["password"]})
    if action in ("interfaces", "restart"):
        targets = interface_targets(request("GET", "/api/state"))
        if action == "interfaces":
            return {"interfaces": targets}
        if not any(row["node"] == parameters["node"] and row["iface"] == parameters["iface"] for row in targets):
            raise ValueError("RESTART_TARGET")
        ack = request("POST", "/api/iface/restart", {"node": parameters["node"], "iface": parameters["iface"]})
        if not isinstance(ack, dict) or ack.get("node") != parameters["node"] or ack.get("iface") != parameters["iface"]:
            raise ValueError("RESTART_ACK")
        return {"restart_queued": True, "node": parameters["node"], "iface": parameters["iface"]}
    if action == "status":
        summary = request("GET", "/api/v1/summary", envelope=False)
        rates = summary.get("throughput") or {}
        version = summary.get("version")
        if not isinstance(version, str) or len(version) > 96 or has_control(version):
            raise ValueError("STATUS_RESULT")
        result = {"version": version}
        for key in ("rx_bytes_per_sec", "tx_bytes_per_sec"):
            value = rates.get(key)
            if type(value) is not int or not 0 <= value <= 2**63 - 1:
                raise ValueError("STATUS_RESULT")
            result[key] = value
        return result
    if action == "clients":
        state = request("GET", "/api/state")
        if not isinstance(state, dict):
            raise ValueError("PANEL_STATE")
        users = (state.get("roster") or {}).get("users")
        if not isinstance(users, dict) or len(users) > 2048:
            raise ValueError("CLIENT_LIST")
        result = []
        for ident, user in users.items():
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", ident) or not isinstance(user, dict):
                raise ValueError("CLIENT_LIST")
            name = user.get("name")
            if not isinstance(name, str) or not name.strip() or len(name) > 96 or has_control(name):
                raise ValueError("CLIENT_LIST")
            disabled, expiry = user.get("disabled", False), user.get("expiry", 0)
            if type(disabled) is not bool or type(expiry) is not int or not 0 <= expiry <= 253402300799:
                raise ValueError("CLIENT_LIST")
            result.append({"id": ident, "name": name, "disabled": disabled, "expires_at": expiry})
        return {"clients": result}
    if action == "add-client":
        result = request("POST", "/api/users/create", {"name": parameters["name"]})
        ident = result.get("id") if isinstance(result, dict) else None
        if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", ident):
            raise ValueError("CLIENT_RESULT")
        return {"client_id": ident}
    if action == "disable-client":
        ack = request("POST", "/api/user/block", {"user_id": parameters["client_id"]})
        if not isinstance(ack, dict) or ack.get("id") != parameters["client_id"] or ack.get("disabled") is not True:
            raise ValueError("CLIENT_DISABLE_ACK")
        return {"client_id": parameters["client_id"]}
    # The reviewed /api/users/update rebuild drops disabled/disabled_at.
    # No safe expiry mutation contract is established; never substitute that call.
    raise ValueError("ACTION_FIELDS")


if __name__ == "__main__":
    try:
        raw = sys.stdin.buffer.read(131073)
        if len(raw) > 131072:
            raise ValueError("REQUEST_SIZE")
        source = json.loads(raw)
        result = execute(source)
        output = {"ok": True, "request_id": source["request_id"], "data": result}
        print("MCRF_MANAGEMENT|" + base64.b64encode(json.dumps(output).encode("utf-8")).decode("ascii"))
    except Exception as error:
        # Never echo exceptions, credentials, provider bodies or uncertain mutation results.
        allowed = {"TLS_PIN", "PANEL_HTTP_401", "PANEL_HTTP_403", "PANEL_HTTP_404", "PANEL_HTTP",
                   "PANEL_REJECTED", "STATUS_RESULT", "CLIENT_LIST", "INTERFACE_STATE"}
        code = str(error) if isinstance(error, ValueError) and str(error) in allowed else "PANEL_RESPONSE_INVALID"
        print("MCRF_MANAGEMENT_ERROR|" + code)
        sys.exit(1)
