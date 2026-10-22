package org.mcrf.client

import java.util.Base64

/** Server scripts preserve the existing MCRF deployment API behavior; no Python runs in the Android app. */
object AndroidDeployScripts {
    private fun q(v: String)=AndroidDeployPlan.quote(v)
    /** Provisioning only: never downloads/runs an installer or changes the panel certificate. */
    fun addTransport(recipe:AndroidDeployRecipe,t:AndroidDeployTarget,saved:org.json.JSONObject):String {
        require(recipe.id in setOf("3xui","swg","openflux")){"Добавление транспорта этой панели пока не поддерживается"}
        AndroidDeployPlan.validate(recipe.id,t)
        require(t.options.optBoolean("create",true)){"Выберите параметры нового подключения"}
        AndroidDeployPlan.validateResult(saved,t)
        val s=StringBuilder("set -eu\numask 077\n")
        fun env(key:String,value:String){s.append("export ").append(key).append('=').append(q(value)).append('\n')}
        env("MCRF_PANEL_URL",saved.getString("panel_url"))
        env("MCRF_PANEL_USER",saved.getString("username"));env("MCRF_PANEL_PASS",saved.getString("password"))
        env("MCRF_EXPECTED_CERT",saved.getString("certificate_sha256").lowercase())
        env("MCRF_PANEL_PORT",t.panelPort.toString());env("MCRF_PROVISION_KIND",recipe.id);env("MCRF_PROVISION_HOST",t.host)
        env("MCRF_PROVISION_OPTIONS",Base64.getEncoder().encodeToString(AndroidDeployPlan.normalizedOptions(t).toString().toByteArray()))
        // Read the existing token as data, never source a remote shell file.
        val token=if(recipe.id=="3xui") """
import os,shlex
with open('/etc/x-ui/install-result.env',encoding='utf-8') as config:
    for line in config:
        words=shlex.split(line,comments=True)
        if words and words[0]=='export': words=words[1:]
        if len(words)==1 and words[0].startswith('XUI_API_TOKEN='):
            os.environ['MCRF_PANEL_API_TOKEN']=words[0].split('=',1)[1]
if not os.environ.get('MCRF_PANEL_API_TOKEN'):
    raise RuntimeError('Existing panel API token unavailable; no installation attempted')
""".trimIndent() else ""
        s.append("python3 - <<'MCRF_RESULT_PY'\n").append(token).append('\n').append(provisioning).append('\n').append(probe).append("\nMCRF_RESULT_PY\n")
        return s.toString()
    }
    fun build(recipe: AndroidDeployRecipe,t: AndroidDeployTarget,dir: String): String {
        if(recipe.id==S3BypassProfile.KIND)return AndroidDeployS3.build(t,dir)
        require(Regex("/tmp/mcrf-deploy-[a-f0-9]{32}").matches(dir))
        AndroidDeployPlan.validate(recipe.id,t)
        val s=StringBuilder("set -eu\numask 077\necho $$ > "+q("$dir/pid")+"\ncd "+q(dir)+"\n")
        fun env(k: String,v: String) { s.append("export ").append(k).append('=').append(q(v)).append('\n') }
        val host=t.domain.ifBlank { t.host };val authority=if(host.contains(':')) "[${host.trim('[',']')}]" else host
        when(recipe.id) {
            "3xui" -> {
                env("XUI_NONINTERACTIVE","1");env("XUI_ENABLE_FAIL2BAN","false")
                env("XUI_USERNAME",t.panelUser);env("XUI_PASSWORD",t.panelPassword);env("XUI_PANEL_PORT",t.panelPort.toString())
                env("XUI_WEB_BASE_PATH",AndroidDeployPlan.secret().take(18));env("XUI_SSL_MODE",if(t.domain.isEmpty()) "none" else "domain")
                env("XUI_DOMAIN",t.domain);env("XUI_SERVER_IP",t.host)
                s.append("(umask 022; bash installer.sh "+q(recipe.version)+")\nsource /etc/x-ui/install-result.env\n")
                if(t.domain.isEmpty()) {
                    val ip=t.host.contains(':') || Regex("[0-9.]+").matches(t.host)
                    s.append("install -d -m 700 /etc/x-ui/mcrf-tls\nopenssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 365 -keyout /etc/x-ui/mcrf-tls/key.pem -out /etc/x-ui/mcrf-tls/cert.pem -subj '/CN=MCRF-panel' -addext "+q("subjectAltName="+(if(ip) "IP:" else "DNS:")+t.host.trim('[',']'))+" >/dev/null 2>&1\nchmod 600 /etc/x-ui/mcrf-tls/key.pem /etc/x-ui/mcrf-tls/cert.pem\n/usr/local/x-ui/x-ui cert -webCert /etc/x-ui/mcrf-tls/cert.pem -webCertKey /etc/x-ui/mcrf-tls/key.pem\nsystemctl restart x-ui\n")
                }
                s.append("export MCRF_PANEL_URL="+q("https://$authority:${t.panelPort}/")+"\"\${XUI_WEB_BASE_PATH}\"\nexport MCRF_PANEL_USER=\"\$XUI_USERNAME\"\nexport MCRF_PANEL_PASS=\"\$XUI_PASSWORD\"\nexport MCRF_PANEL_API_TOKEN=\"\$XUI_API_TOKEN\"\nsystemctl is-active --quiet x-ui\n")
            }
            "openflux" -> {
                env("OPENFLUX_ADMIN_USER",t.panelUser);env("OPENFLUX_ADMIN_PASSWORD",t.panelPassword);env("OPENFLUX_DEPLOY_REVISION",recipe.commit);env("OPENFLUX_PORT",t.panelPort.toString())
                s.append("(umask 022; bash installer.sh --mode systemd --port ${t.panelPort})\nsystemctl is-active --quiet openflux-panel\n")
                env("MCRF_PANEL_URL","https://$authority:${t.panelPort}");env("MCRF_PANEL_USER",t.panelUser);env("MCRF_PANEL_PASS",t.panelPassword)
            }
            "swg" -> {
                env("SWG_REF",recipe.commit);env("BASIC_USER",t.panelUser);env("BASIC_PASS",t.panelPassword);env("PANEL_DOMAIN","$authority:${t.panelPort}")
                env("PORT",t.panelPort.toString());env("TLS_MODE","selfsigned");env("SERVE_MODE","internal");env("MANAGE_IFACES","")
                s.append("(umask 022; bash installer.sh master)\nsystemctl is-active --quiet swg-panel-server\n")
                env("MCRF_PANEL_URL","https://$authority:${t.panelPort}/");s.append("export MCRF_PANEL_USER=\$(cut -d: -f1 /etc/swg-panel/auth)\n");env("MCRF_PANEL_PASS",t.panelPassword)
            }
        }
        env("MCRF_PANEL_PORT",t.panelPort.toString());env("MCRF_PROVISION_KIND",recipe.id);env("MCRF_PROVISION_HOST",t.host)
        env("MCRF_PROVISION_OPTIONS",Base64.getEncoder().encodeToString(AndroidDeployPlan.normalizedOptions(t).toString().toByteArray()))
        s.append("python3 - <<'MCRF_RESULT_PY'\n").append(provisioning).append('\n').append(probe).append("\nMCRF_RESULT_PY\n")
        return s.toString()
    }
    fun pin(recipe: AndroidDeployRecipe,source: String): String {
        if(recipe.id!="openflux") return source
        val before="  DEPLOY_REVISION=\"\$(git ls-remote \"https://github.com/\${REPO}.git\" \"refs/heads/\${REF}\" 2>/dev/null | awk '{print \$1}' || true)\""
        val archive="  archive_ref=\"\${DEPLOY_REVISION:-refs/heads/\${REF}}\""
        val clone="  git clone --quiet --depth 1 --single-branch --branch main \"\$UPSTREAM_REPO\" \"\$upstream_src\""
        require(source.contains(before) && source.contains(archive) && source.contains(clone)) { "Не удалось закрепить OpenFlux; SSH ещё не изменён" }
        val match=Regex("(?ms)^install_go\\(\\)\\{\\r?\\n.*?^\\}\\r?$").find(source) ?: error("Не найден установщик Go")
        require(match.value.contains("rm -rf /usr/local/go"))
        return source.replaceRange(match.range,goInstaller+"\n").replace(before,"  DEPLOY_REVISION="+q(recipe.commit))
            .replace(archive,"  archive_ref=\"\$DEPLOY_REVISION\"")
            .replace(clone,"  git init --quiet \"\$upstream_src\"\n  git -C \"\$upstream_src\" remote add origin \"\$UPSTREAM_REPO\"\n  git -C \"\$upstream_src\" fetch --quiet --depth 1 origin 'edcf77a1848a72370cfba9f346d3b897b61e7e7d'\n  git -C \"\$upstream_src\" checkout --quiet --detach FETCH_HEAD")
    }
    internal val provisioning = """
import base64
import hashlib
import hmac
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


def panel_api_error(method, path, status, raw):
    reason = ""
    try:
        value = json.loads(raw)
        error = str(value.get("error", ""))[:2048].lower() if isinstance(value, dict) else ""
        if "subnet" in error or "overlap" in error or "tun_addr" in error:
            reason = "Подсеть подключения занята либо имеет неверный формат."
        elif "port" in error and ("used" in error or "conflict" in error):
            reason = "Порт подключения уже занят на этом сервере."
        elif "interface name" in error:
            reason = "Панель отклонила имя сетевого интерфейса."
        elif "fork" in error:
            reason = "Панель не поддерживает выбранный вариант протокола."
        elif "csqtt" in error and ("version" in error or "update" in error):
            reason = "Узел не поддерживает выбранное поколение CSQTT; обновите панель и узел."
    except (ValueError, UnicodeDecodeError):
        pass
    return "API панели: " + method + " " + path + ", HTTP " + str(status) + ". " + reason + " Запрос не повторялся."


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
            if len(raw) > 4 * 1024 * 1024:
                raise ProvisionError("API панели: ответ больше 4 МБ. Запрос не повторялся.")
            if not 200 <= response.status < 300:
                # Interpret known validation categories, never echo an untrusted body
                # which may contain panel passwords, keys or private call links.
                raise ProvisionError(panel_api_error(method, path, response.status, raw))
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


def valid_swg_calls(calls):
    return (isinstance(calls, list) and bool(calls)
            and all(isinstance(call, str) and re.fullmatch(r"https://vk\.(?:ru|com)/call/join/[A-Za-z0-9_=-]{16,512}", call) for call in calls)
            and len(set(calls)) == len(calls)
            and len(json.dumps(calls, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) <= 65536)


def csqtt25_encoder():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        raise ProvisionError("Для ссылки CSQTT 2.5 на сервере нужен python3-cryptography. Подключение не создано.")
    key = hashlib.sha256(b"csqtt-chiper-key").digest()
    def encode(payload):
        plain = payload.encode("utf-8")
        iv = hmac.new(key, plain, hashlib.sha256).digest()[:12]
        return "csqtt://" + base64.urlsafe_b64encode(iv + AESGCM(key).encrypt(iv, plain, None)).decode("ascii").rstrip("=")
    return encode


def require_swg25(api, username, password):
    csqtt25_encoder()  # Validate export support before the first deployment mutation.
    envelope(api.request("POST", "/api/login", {"username": username, "password": password}), True)
    state = envelope(api.request("GET", "/api/state"), True)
    nodes = state.get("nodes", [])
    if len(nodes) != 1:
        raise ProvisionError("Для CSQTT 2.5 выберите один зарегистрированный локальный узел SWG.")
    snapshot = state.get("snapshots", {}).get(nodes[0].get("id"), {})
    if "2.5" not in snapshot.get("csqtt_lines", []):
        raise ProvisionError("Этот узел SWG ещё не поддерживает CSQTT 2.5. Обновите панель и узел; CSQTT 2.1 не будет создан вместо 2.5.")


def provision_swg(api, options, host, links, username, password, sleep=time.sleep):
    if "swg_profiles" not in options:
        return provision_swg_single(api, options, host, links, username, password, sleep)
    profiles = options["swg_profiles"]
    if not isinstance(profiles, list) or not 1 <= len(profiles) <= 4:
        raise ProvisionError("Выберите от одного до четырёх вариантов SWG.")
    seen, ports, used_calls = set(), set(), set()
    prepared = []
    for profile in profiles:
        kind = profile.get("kind")
        if kind not in ("qwdtt", "wdtt", "csqtt", "csqtt25") or kind in seen or profile.get("enabled") is not True:
            raise ProvisionError("Повторяющийся или неизвестный протокол SWG.")
        seen.add(kind)
        calls = profile.get("vk_calls")
        if not valid_swg_calls(calls):
            raise ProvisionError("У каждого протокола должны быть свои звонки VK.")
        for call in calls:
            if not isinstance(call, str) or not re.fullmatch(r"https://vk\.(?:ru|com)/call/join/[A-Za-z0-9_=-]{16,512}", call):
                raise ProvisionError("Нужна корректная ссылка звонка VK.")
            call_hash = call.rsplit("/", 1)[1]
            if call_hash in used_calls:
                raise ProvisionError("Для разных протоколов нужны отдельные звонки VK.")
            used_calls.add(call_hash)
        row = dict(profile, relay_kind=kind)
        for key in ("relay_port",) if kind in ("csqtt", "csqtt25") else ("relay_port", "wg_port"):
            port = checked_port(row[key])
            if port in ports:
                raise ProvisionError("Порты протоколов SWG должны различаться.")
            ports.add(port)
            row[key] = port
        prepared.append(row)
    # Validate the whole selection before the first mutating panel request.
    if any(row["relay_kind"] == "csqtt25" for row in prepared):
        require_swg25(api, username, password)
    for row in prepared:
        subnet = {"qwdtt": "10.88.0.1/24", "wdtt": "10.89.0.1/24", "csqtt": "10.90.0.1/24", "csqtt25": "10.91.0.1/24"}[row["relay_kind"]]
        provision_swg_single(api, row, host, links, username, password, sleep, subnet)


def free_swg_subnet(state, preferred):
    import ipaddress
    occupied = []
    for node in state.get("nodes", []):
        if not isinstance(node, dict):
            raise ProvisionError("SWG вернул неподходящие данные узла; создание отменено.")
        for group, fields in (("wdtt_cfg", ("wg_addr", "raw_addr")), ("csqtt_cfg", ("tun_addr",)), ("interfaces", ("address", "addr"))):
            rows = node.get(group, {})
            for record in rows.values() if isinstance(rows, dict) else rows if isinstance(rows, list) else []:
                if not isinstance(record, dict):
                    continue
                for field in fields:
                    raw = record.get(field)
                    for value in raw if isinstance(raw, list) else [raw]:
                        if not value:
                            continue
                        try:
                            occupied.append(ipaddress.ip_network(str(value), strict=False))
                        except ValueError:
                            raise ProvisionError("SWG вернул неверную занятую подсеть; создание отменено.")
        mesh = node.get("mesh_subnet")
        if mesh:
            try:
                occupied.append(ipaddress.ip_network(str(mesh), strict=False))
            except ValueError:
                raise ProvisionError("SWG вернул неверную подсеть mesh; создание отменено.")
    candidates = [preferred] + ["10." + str(second) + "." + str(third) + ".1/24" for second in range(88, 96) for third in range(256)]
    for candidate in candidates:
        net = ipaddress.ip_network(candidate, strict=False)
        if not any(net.version == other.version and net.overlaps(other) for other in occupied):
            return candidate
    raise ProvisionError("Нет свободной подсети для нового SWG-подключения. Старые подключения не изменены.")


def provision_swg_single(api, options, host, links, username, password, sleep=time.sleep, subnet=None):
    kind = options.get("relay_kind")
    if kind not in ("wdtt", "csqtt", "csqtt25", "qwdtt"):
        raise ProvisionError("Неподдерживаемый вариант SWG.")
    modern = kind == "csqtt25"
    if modern:
        require_swg25(api, username, password)
        encode_modern = csqtt25_encoder()
    calls = options.get("vk_calls") or [options.get("vk_call", "")]
    if not valid_swg_calls(calls):
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
    preferred = subnet or {"qwdtt": "10.88.0.1/24", "wdtt": "10.89.0.1/24", "csqtt": "10.90.0.1/24", "csqtt25": "10.91.0.1/24"}[kind]
    subnet = free_swg_subnet(state, preferred)
    iface = "mf" + kind + secrets.token_hex(3)
    group = "csqtt" if kind in ("csqtt", "csqtt25") else "wdtt"
    listen_port = checked_port(options["relay_port"])
    wg_port = checked_port(options["wg_port"]) if group == "wdtt" else None
    if wg_port is not None and listen_port == wg_port:
        raise ProvisionError("Порты TURN и WireGuard должны различаться.")
    body = {"node": node, "iface": iface, "fork": "wdttplus" if kind == "wdtt" else "csqtt" if modern else kind,
            "listen": "0.0.0.0:" + str(listen_port), "stopped": False, "title": "MCRF · " + kind.upper(), "raw": False}
    if group == "wdtt":
        body.update({"wg_addr": subnet, "wg_port": wg_port})
    else:
        body["tun_addr"] = subnet
        if modern:
            body["line"] = "2.5"
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
        if record and record.get("active") == "active" and secret in record.get("passwords", {}) and (not modern or record.get("line") == "2.5"):
            break
        sleep(2)
    else:
        raise ProvisionError("SWG сохранил профиль, но узел не подтвердил запуск и пароль. Проверьте панель.")
    call_hashes = [call.rsplit("/", 1)[1] for call in calls]
    # Original QWDTT uses decoded newlines; CSQTT splits raw '+' before decoding.
    vk_hash = ("\n" if kind == "qwdtt" else ",").join(call_hashes)
    if modern:
        query = {"v": 2, "host": host, "peer": listen_port, "password": secret}
        payload = "connect?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
        payload += "&hashes=" + "+".join(urllib.parse.quote(value, safe="") for value in call_hashes)
        link = encode_modern(payload)
    elif kind == "qwdtt":
        query = {"name": "MCRF · QWDTT", "peer": authority(host) + ":" + str(listen_port), "hashes": vk_hash, "pass": secret}
        link = "qwdtt://config?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
    else:
        query = {"v": 2 if kind == "csqtt" else 1, "name": "MCRF · " + kind.upper(), "host": host,
                 "peer" if kind == "csqtt" else "dtls": listen_port, "password": secret, "hashes": vk_hash}
        if kind == "wdtt":
            query.update({"wg": wg_port, "local": 9000})
        if kind == "csqtt":
            del query["hashes"]
            link = kind + "://connect?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
            link += "&hashes=" + "+".join(urllib.parse.quote(value, safe="") for value in call_hashes)
        else:
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

""".trimIndent()
    internal val probe = """
import os,json,base64,ssl,socket,time,hashlib,urllib.parse
u=urllib.parse.urlsplit(os.environ['MCRF_PANEL_URL'])
port=int(os.environ['MCRF_PANEL_PORT'])
if u.scheme!='https' or u.port!=port or not u.hostname or u.username:
    raise RuntimeError('Panel returned invalid HTTPS address')
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.check_hostname=False
ctx.verify_mode=ssl.CERT_NONE
last=None
for attempt in range(25):
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=3) as tcp:
            with ctx.wrap_socket(tcp,server_hostname=u.hostname) as tls:
                cert=tls.getpeercert(binary_form=True)
                path=urllib.parse.quote(u.path or '/',safe='/')
                request='GET '+path+' HTTP/1.1\r\nHost: '+u.netloc+'\r\nConnection: close\r\n\r\n'
                tls.sendall(request.encode('ascii'))
                status=tls.recv(1024).split(b'\r\n',1)[0].split()
                if not cert or len(status)<2 or not status[0].startswith(b'HTTP/') or not 200<=int(status[1])<500:
                    raise RuntimeError('Panel HTTPS health check failed')
                fingerprint=hashlib.sha256(cert).hexdigest()
                break
    except (OSError,ValueError,RuntimeError) as e:
        last=e
        time.sleep(1)
else:
    raise RuntimeError('Panel HTTPS did not become ready') from last
expected=os.environ.get('MCRF_EXPECTED_CERT','')
if expected and fingerprint != expected:
    raise RuntimeError('Saved panel certificate changed; no transport creation attempted')
r={'panel_url':os.environ['MCRF_PANEL_URL'],'username':os.environ['MCRF_PANEL_USER'],'password':os.environ['MCRF_PANEL_PASS'],'certificate_sha256':fingerprint,'ready':True,'connections':[]}
if os.environ.get('MCRF_PROVISION_KIND'):
    options=json.loads(base64.b64decode(os.environ['MCRF_PROVISION_OPTIONS']))
    r=configure_initial(r,os.environ['MCRF_PROVISION_KIND'],options,os.environ['MCRF_PROVISION_HOST'])
print('MCRF_RESULT|'+base64.b64encode(json.dumps(r).encode()).decode())
""".trimIndent()
    internal val goInstaller = """
install_go(){
  local version arch json sha file tmp toolchain
  version="${'$'}(awk '/^go /{print ${'$'}2;exit}' "${'$'}{1:-${'$'}PREFIX/source}/go.mod")"
  [[ "${'$'}version" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?${'$'} ]] || fail "некорректная версия Go"
  [[ "${'$'}version" =~ ^[0-9]+\.[0-9]+${'$'} ]] && version="${'$'}version.0"
  arch="${'$'}(uname -m)"
  case "${'$'}arch" in x86_64) arch=amd64;; aarch64|arm64) arch=arm64;; *) fail "архитектура не поддерживается";; esac
  toolchain="${'$'}PREFIX/toolchains/go${'$'}version-linux-${'$'}arch"
  if [ -x "${'$'}toolchain/bin/go" ]; then
    "${'$'}toolchain/bin/go" version | grep -Fq "go${'$'}version " || fail "повреждён toolchain Go"
    export PATH="${'$'}toolchain/bin:${'$'}PATH"
    return
  fi
  [ ! -e "${'$'}toolchain" ] || fail "каталог toolchain уже существует"
  install -d -m 755 "${'$'}PREFIX/toolchains"
  tmp="${'$'}(mktemp -d "${'$'}PREFIX/toolchains/.go-stage.XXXXXX")"
  file="go${'$'}version.linux-${'$'}arch.tar.gz"
  json="${'$'}(curl -fsSL --connect-timeout 15 --max-time 90 'https://go.dev/dl/?mode=json&include=all')"
  sha="${'$'}(printf '%s' "${'$'}json" | python3 -c 'import json,sys; data=json.load(sys.stdin); name=sys.argv[1]; matches=[f["sha256"] for r in data for f in r["files"] if f["filename"]==name]; assert len(matches)==1; print(matches[0])' "${'$'}file")"
  [[ "${'$'}sha" =~ ^[0-9a-f]{64}${'$'} ]] || fail "не найден SHA-256 Go"
  curl -fsSL --retry 3 --connect-timeout 15 --max-time 300 "https://go.dev/dl/${'$'}file" -o "${'$'}tmp/${'$'}file"
  printf '%s  %s\n' "${'$'}sha" "${'$'}tmp/${'$'}file" | sha256sum -c - >/dev/null
  tar --no-same-owner -C "${'$'}tmp" -xzf "${'$'}tmp/${'$'}file"
  [ -x "${'$'}tmp/go/bin/go" ] || fail "неполный архив Go"
  mv -- "${'$'}tmp/go" "${'$'}toolchain"
  rm -f -- "${'$'}tmp/${'$'}file"
  rmdir -- "${'$'}tmp"
  export PATH="${'$'}toolchain/bin:${'$'}PATH"
}
""".trimIndent()
}
