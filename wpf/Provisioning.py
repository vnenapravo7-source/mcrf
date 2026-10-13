"""Initial profiles through the pinned panels' public APIs. No database edits.

Runs on the server over a trusted SSH channel. TLS is pinned to the certificate
already read by the panel readiness check. Mutating requests are never retried.
"""
import base64
import hashlib
import http.client
import http.cookies
import json
import re
import secrets
import socket
import ssl
import time
import urllib.parse
import uuid


class ProvisionError(Exception):
    pass


class PanelAPI:
    def __init__(self, panel_url, fingerprint, bearer="", timeout=20):
        self.url = urllib.parse.urlsplit(panel_url)
        self.fingerprint = fingerprint
        self.bearer = bearer
        self.timeout = timeout
        self.cookies = {}

    def listener_ready(self, port):
        try:
            with socket.create_connection(("127.0.0.1", port), 2):
                return True
        except OSError:
            return False

    def request(self, method, path, body=None, form=False, action=False):
        if not path.startswith("/") or ".." in path:
            raise ProvisionError("Неподходящий путь API панели.")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection("127.0.0.1", self.url.port, timeout=self.timeout, context=ctx)
        try:
            # Connect locally, but preserve the panel's SNI and verify the SSH-pinned certificate.
            tcp = socket.create_connection(("127.0.0.1", self.url.port), self.timeout)
            try:
                connection.sock = ctx.wrap_socket(tcp, server_hostname=self.url.hostname)
            except BaseException:
                tcp.close()
                raise
            if hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest() != self.fingerprint:
                raise ProvisionError("Сертификат панели изменился во время настройки.")
            headers = {"Host": self.url.netloc, "Accept": "application/json"}
            if self.bearer:
                headers["Authorization"] = "Bearer " + self.bearer
            if action:
                headers["X-OpenFlux-Action"] = "1"
            if self.cookies:
                headers["Cookie"] = "; ".join(k + "=" + v for k, v in self.cookies.items())
            payload = None
            if body is not None:
                payload = (urllib.parse.urlencode(body) if form else json.dumps(body, separators=(",", ":"))).encode()
                headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
            connection.request(method, self.url.path.rstrip("/") + path, payload, headers)
            response = connection.getresponse()
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024 or not 200 <= response.status < 300:
                # Do not expose response bodies: a panel can echo passwords, keys or call links.
                raise ProvisionError("API панели: HTTP " + str(response.status) + ". Запрос не повторялся.")
            for name, value in response.getheaders():
                if name.lower() == "set-cookie":
                    cookie = http.cookies.SimpleCookie()
                    cookie.load(value)
                    self.cookies.update({k: v.value for k, v in cookie.items()})
            try:
                return json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                raise ProvisionError("API панели вернул неподходящий результат.")
        except (OSError, http.client.HTTPException):
            raise ProvisionError("Связь с API панели прервалась. Создание не повторялось; проверьте панель.")
        finally:
            connection.close()


def envelope(value, swg=False):
    if not isinstance(value, dict) or value.get("ok" if swg else "success") is not True:
        raise ProvisionError("Панель отклонила настройку подключения. Проверьте её журнал.")
    return value.get("data" if swg else "obj")


def authority(host):
    return "[" + host.strip("[]") + "]" if ":" in host else host


def checked_port(value):
    if not isinstance(value, int) or isinstance(value, bool) or not 1024 <= value <= 65535:
        raise ProvisionError("Неподходящий порт подключения.")
    return value


def provision_3xui(api, options, host, links, sleep=time.sleep):
    modes = [(mode, checked_port(options[mode + "_port"])) for mode in ("tcp", "grpc") if options.get(mode)]
    if not modes or len({p for _, p in modes}) != len(modes):
        raise ProvisionError("Выберите TCP/gRPC и разные порты.")
    sni = options.get("sni", "pinterest.com")
    if not re.fullmatch(r"[a-zA-Z0-9.-]{1,253}", sni):
        raise ProvisionError("Неподходящий домен REALITY.")
    existing = envelope(api.request("GET", "/panel/api/inbounds/list"))
    if not isinstance(existing, list) or any(row.get("port") in {p for _, p in modes} for row in existing):
        raise ProvisionError("Порт подключения уже занят в панели. Существующие профили сохранены.")
    for mode, port in modes:
        print("MCRF_STEP|Создаём VLESS · " + mode.upper(), flush=True)
        pair = envelope(api.request("GET", "/panel/api/server/getNewX25519Cert"))
        if not isinstance(pair, dict) or any(not re.fullmatch(r"[A-Za-z0-9_-]{43}", pair.get(k, "")) for k in ("privateKey", "publicKey")):
            raise ProvisionError("Панель не выдала новую пару ключей REALITY.")
        identity, tag, short = str(uuid.uuid4()), "mcrf-" + uuid.uuid4().hex, secrets.token_hex(8)
        # New REALITY servers require a hybrid key share. Edge's legacy hello
        # cannot supply it; our pinned client supports Chrome and Safari.
        fp = "chrome" if mode == "tcp" else "safari"
        settings = {"clients": [{"id": identity, "flow": "", "email": tag, "enable": True, "limitIp": 0,
                                 "totalGB": 0, "expiryTime": 0, "subId": secrets.token_hex(8), "reset": 0}], "decryption": "none"}
        stream = {"network": mode, "security": "reality", "realitySettings": {
            "show": False, "xver": 0, "target": sni + ":443", "serverNames": [sni],
            "privateKey": pair["privateKey"], "shortIds": [short],
            "settings": {"publicKey": pair["publicKey"], "fingerprint": fp, "serverName": "", "spiderX": "/"}}}
        if mode == "tcp":
            stream["tcpSettings"] = {"acceptProxyProtocol": False, "header": {"type": "none"}}
        else:
            stream["grpcSettings"] = {"serviceName": options.get("grpc_service", ""), "multiMode": False}
        inbound = {"up": 0, "down": 0, "total": 0, "remark": "MCRF · VLESS " + mode.upper(), "enable": True,
                   "expiryTime": 0, "listen": "", "port": port, "protocol": "vless", "tag": tag,
                   "settings": json.dumps(settings), "streamSettings": json.dumps(stream),
                   "sniffing": json.dumps({"enabled": True, "destOverride": ["http", "tls", "quic"], "metadataOnly": False, "routeOnly": False})}
        envelope(api.request("POST", "/panel/api/inbounds/add", inbound, form=True))
        confirmed = False
        for attempt in range(15):
            rows = envelope(api.request("GET", "/panel/api/inbounds/list"))
            for row in rows:
                if row.get("enable") and row.get("port") == port:
                    clients = row.get("settings") or {}
                    if isinstance(clients, str):
                        clients = json.loads(clients)
                    if not isinstance(clients, dict):
                        raise ProvisionError("Панель вернула неподходящие настройки VLESS.")
                    confirmed = any(isinstance(c, dict) and c.get("id") == identity and c.get("enable") for c in clients.get("clients", []))
            if confirmed and api.listener_ready(port):
                break
            confirmed = False
            sleep(1)
        if not confirmed:
            raise ProvisionError("Панель не подтвердила созданный VLESS-профиль.")
        query = {"encryption": "none", "security": "reality", "type": mode, "sni": sni,
                 "fp": fp, "pbk": pair["publicKey"], "sid": short, "spx": "/"}
        if mode == "grpc":
            query["serviceName"] = options.get("grpc_service", "")
        links.append("vless://" + identity + "@" + authority(host) + ":" + str(port) + "?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
                     + "#" + urllib.parse.quote("MCRF · VLESS " + mode.upper()))


def provision_openflux(api, options, host, links, username, password, sleep=time.sleep):
    api.request("POST", "/api/login", {"username": username, "password": password}, action=True)
    kind = options.get("transport")
    if kind not in ("yandex", "vyandex", "boards", "telemost", "mailru", "cupsonline", "direct"):
        raise ProvisionError("Неподдерживаемый транспорт OpenFlux.")
    config = {"name": "MCRF · " + kind, "enabled": True, "transport": kind,
              "url": options.get("carrier_url", "") if kind not in ("direct", "cupsonline") else "",
              "mode": "l4", "codec": "batched", "debug": False, "auto_update": False,
              "negotiate": False, "encryption_key_file": "",
              "transports": [{"type": kind, "url": options.get("carrier_url", "") if kind not in ("direct", "cupsonline") else "", "priority": 100}]}
    if kind == "direct":
        config["direct_listen"] = "0.0.0.0:" + str(checked_port(options["direct_port"]))
    print("MCRF_STEP|Создаём OpenFlux · " + kind, flush=True)
    created = api.request("POST", "/api/connections", config, action=True)
    cid = created.get("id", "") if isinstance(created, dict) else ""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", cid):
        raise ProvisionError("OpenFlux не вернул созданное подключение.")
    for attempt in range(60):
        state = api.request("GET", "/api/state")
        row = next((r for r in state.get("connections", []) if r.get("id") == cid), None)
        if row and row.get("yandex_auth_required"):
            raise ProvisionError("Подключение создано, но требуется вход Yandex в панели OpenFlux.")
        if row and row.get("running") and (kind != "cupsonline" or row.get("client_code")):
            if kind != "direct" or api.listener_ready(options["direct_port"]):
                break
        sleep(1)
    else:
        raise ProvisionError("OpenFlux-профиль создан, но его движок не подтвердил запуск. Проверьте панель.")
    shared = api.request("POST", "/api/connections/" + cid + "/share", {"host": host}, action=True)
    link = shared.get("link", "") if isinstance(shared, dict) else ""
    if not re.fullmatch(r"openflux://v1/[A-Za-z0-9_-]{10,65536}", link):
        raise ProvisionError("OpenFlux не выдал корректную ссылку подключения.")
    links.append(link)


def provision_swg(api, options, host, links, username, password, sleep=time.sleep):
    kind = options.get("relay_kind")
    if kind not in ("wdtt", "csqtt", "qwdtt"):
        raise ProvisionError("Неподдерживаемый вариант SWG.")
    calls = options.get("vk_calls") or [options.get("vk_call", "")]
    if not isinstance(calls, list) or not 1 <= len(calls) <= 32 or len(set(calls)) != len(calls) or any(not isinstance(call, str) or not re.fullmatch(r"https://vk\.(?:ru|com)/call/join/[A-Za-z0-9_=-]{16,512}", call) for call in calls):
        raise ProvisionError("Нужна корректная ссылка звонка VK.")
    envelope(api.request("POST", "/api/login", {"username": username, "password": password}), True)
    state = None
    for attempt in range(60):
        state = envelope(api.request("GET", "/api/state"), True)
        nodes = state.get("nodes", [])
        if nodes:
            break
        sleep(1)
    if not state or len(nodes) != 1:
        raise ProvisionError("SWG должен зарегистрировать один новый локальный узел; выберите узел вручную в панели.")
    node = nodes[0].get("id")
    if not node:
        raise ProvisionError("SWG не вернул идентификатор узла.")
    iface = "mf" + kind + secrets.token_hex(3)
    group = "csqtt" if kind == "csqtt" else "wdtt"
    listen_port = checked_port(options["relay_port"])
    wg_port = checked_port(options["wg_port"]) if group == "wdtt" else None
    if wg_port is not None and listen_port == wg_port:
        raise ProvisionError("Порты TURN и WireGuard должны различаться.")
    body = {"node": node, "iface": iface, "fork": "wdttplus" if kind == "wdtt" else kind,
            "listen": "0.0.0.0:" + str(listen_port), "stopped": False, "title": "MCRF · " + kind.upper(), "raw": False}
    if group == "wdtt":
        body.update({"wg_addr": "10.88.0.1/24", "wg_port": wg_port})
    else:
        body["tun_addr"] = "10.88.0.1/24"
    print("MCRF_STEP|Создаём SWG · " + kind.upper(), flush=True)
    envelope(api.request("POST", "/api/" + group + "/set", body), True)
    user = envelope(api.request("POST", "/api/users/create", {"name": "MCRF · " + secrets.token_hex(3)}), True)
    uid = user.get("id") if isinstance(user, dict) else None
    if not uid:
        raise ProvisionError("SWG не создал пользователя подключения.")
    envelope(api.request("POST", "/api/users/update", {"id": uid, "vk_link": calls[0], "vk_links": calls}), True)
    peer = envelope(api.request("POST", "/api/peers/create", {"user_id": uid, "title": "MCRF",
                    "targets": [{"node": node, "iface": iface, "type": group}]}), True)
    secret = peer.get(group + "_password", "") if isinstance(peer, dict) else ""
    if not secret or len(secret) > 1024 or any(c in secret for c in "\r\n\0"):
        raise ProvisionError("SWG не выдал пароль нового подключения.")
    for attempt in range(150):
        state = envelope(api.request("GET", "/api/state"), True)
        records = state.get("snapshots", {}).get(node, {}).get(group, [])
        record = next((r for r in records if r.get("iface") == iface), None)
        if record and record.get("active") == "active" and secret in record.get("passwords", {}):
            break
        sleep(2)
    else:
        raise ProvisionError("SWG сохранил профиль, но узел не подтвердил запуск и пароль. Проверьте панель.")
    vk_hash = ",".join(call.rsplit("/", 1)[1] for call in calls)
    if kind == "qwdtt":
        query = {"name": "MCRF · QWDTT", "peer": authority(host) + ":" + str(listen_port), "hashes": vk_hash, "pass": secret}
        link = "qwdtt://config?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
    else:
        query = {"v": 2 if kind == "csqtt" else 1, "name": "MCRF · " + kind.upper(), "host": host,
                 "peer" if kind == "csqtt" else "dtls": listen_port, "password": secret, "hashes": vk_hash}
        if kind == "wdtt":
            query.update({"wg": wg_port, "local": 9000})
        link = kind + "://connect?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
    links.append(link)


def configure_initial(result, kind, options, host, api=None, sleep=time.sleep):
    result["provision_requested"] = bool(options.get("create"))
    result["provision_complete"] = False
    result["provision_error"] = ""
    links = result.setdefault("connections", [])
    if not options.get("create"):
        return result
    try:
        if api is None:
            import os
            api = PanelAPI(result["panel_url"], result["certificate_sha256"], os.environ.get("MCRF_PANEL_API_TOKEN", ""))
        if kind == "3xui":
            provision_3xui(api, options, host, links, sleep)
        elif kind == "openflux":
            provision_openflux(api, options, host, links, result["username"], result["password"], sleep)
        elif kind == "swg":
            provision_swg(api, options, host, links, result["username"], result["password"], sleep)
        else:
            raise ProvisionError("Неизвестная панель.")
        result["provision_complete"] = True
    except ProvisionError as failure:
        result["provision_error"] = str(failure)
    except Exception:
        result["provision_error"] = "Настройка не завершена. Создание не повторялось; проверьте панель и её журнал."
    return result
