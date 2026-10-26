#!/usr/bin/env python3
"""Endless soak test of the OpenFlux carriers.

For each transport it starts an exit and a client of the core (both on this machine, so the shared server is left alone),
downloads big files through the client's SOCKS5 port in a loop for ever, and prints every second, per transport: the
speed, the average over the last minute, the volume so far, the download errors and what the core's logs say (429s,
4004 cuts, websocket errors, carrier rotations, session restarts). Everything is also written to a CSV and to per-transport
logs under the work directory. Stop it with Ctrl-C (or by creating a file named STOP in the work directory).

    python soak.py                       # cupsonline, avito, mailru
    python soak.py --transports cupsonline,mailru
    python soak.py --extra extra.json    # more transports with ready-made URLs (see below)

Mail.ru and Yandex need a signed-in account: the sign-in saved by the app is read from --accounts (never printed or copied
anywhere but the work directory's cookie stores). A Mail.ru document is made for the run through Cloud's own web API.

extra.json: {"vyandex": {"kind": "doc", "type": "vyandex", "url": "https://docs.yandex.ru/edit/d/...", "account": "Yandex"}}
"""
import argparse, collections, csv, json, os, re, signal, socket, struct, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CORE = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_ACCOUNTS = os.path.abspath(os.path.join(CORE, "..", "devdata", "OpenFlux", "accounts.json"))
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"

# What the downloader fetches through the tunnel: (host, port, path). By default a server of this script's own on
# 127.0.0.1 (the exit runs on this machine, so it reaches it): public speed-test servers throttle, stall and move files
# and made the carriers look worse than they are. --source internet uses big public files instead.
SOURCES = []
PUBLIC_SOURCES = [
    ("speedtest.tele2.net", 80, "/1GB.zip"),
    ("ipv4.download.thinkbroadband.com", 80, "/1GB.zip"),
    ("speedtest.belwue.net", 80, "/1G"),
]


def start_local_source():
    """An HTTP server that answers any GET with a gigabyte of fresh random bytes (nothing for a carrier to compress)."""
    import http.server, socketserver

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            total = 1 << 30
            self.send_response(200)
            self.send_header("Content-Length", str(total))
            self.send_header("Connection", "close")
            self.end_headers()
            sent = 0
            try:
                while sent < total:
                    block = os.urandom(65536)
                    self.wfile.write(block)
                    sent += len(block)
            except OSError:
                pass

        def log_message(self, *a):
            pass

    class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    # The exit refuses loopback (and, on a PC, private) addresses, so the server listens on this machine's main address;
    # it answers only this machine.
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("203.0.113.1", 9))
        me = probe.getsockname()[0]
    finally:
        probe.close()
    srv = Server((me, 0), Handler)
    base_verify = srv.verify_request
    srv.verify_request = lambda request, client_address: client_address[0] in (me, "127.0.0.1") and base_verify(request, client_address)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return me, srv.server_address[1]

LOG_PATTERNS = {
    "429": re.compile(r"HTTP 429|HTTP 503|too many connections|ограничивает подключения|status 429"),
    "4004": re.compile(r"disconnectReason|code 4004|4004.*(deny|cut|budget)"),
    "wserr": re.compile(r"ws error|WebSocket dial|websocket: |Write error|read: connection|use of closed"),
    "rot": re.compile(r"rotating carrier|rotation"),
    "stall": re.compile(r"silent|no Session handshake|handshaking again|took over"),
    "retry": re.compile(r"retrying|reconnect"),
    "fail": re.compile(r"FAILED|fatal|panic:|login required|status 40[13]|cannot open"),
}


class Counter:
    def __init__(self):
        self.bytes_total = 0
        self.window = collections.deque()  # (t, bytes) per second
        self.cur = 0
        self.errors = 0
        self.last_error = ""
        self.last_error_t = 0.0
        self.downloads = 0
        self.logpath = None
        self.lock = threading.Lock()

    def add(self, n):
        with self.lock:
            self.cur += n
            self.bytes_total += n

    def error(self, msg, stream=0):
        with self.lock:
            self.errors += 1
            self.last_error = msg
            self.last_error_t = time.time()
            if self.logpath:
                try:
                    with open(self.logpath, "a", encoding="utf-8") as f:
                        f.write("%s stream %d  %s" % (time.strftime("%H:%M:%S"), stream, msg))
                        f.write(chr(10))
                except OSError:
                    pass

    def tick(self):
        with self.lock:
            n, self.cur = self.cur, 0
        now = time.time()
        self.window.append((now, n))
        while self.window and now - self.window[0][0] > 60:
            self.window.popleft()
        avg = sum(b for _, b in self.window) / max(1, len(self.window))
        return n, avg


def socks_connect(port, host, dport, timeout=30):
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    s.sendall(b"\x05\x01\x00")
    if s.recv(2) != b"\x05\x00":
        raise IOError("socks: no auth reply")
    h = host.encode()
    s.sendall(b"\x05\x01\x00\x03" + bytes([len(h)]) + h + struct.pack(">H", dport))
    rep = s.recv(10)
    if len(rep) < 2 or rep[1] != 0:
        raise IOError("socks connect refused (code %s)" % (rep[1] if len(rep) > 1 else "?"))
    return s


def downloader(port, ctr, stop, stall_s=20, stream=0):
    i = stream  # streams start on different sources
    while not stop.is_set():
        host, dport, path = SOURCES[i % len(SOURCES)]
        try:
            s = socks_connect(port, host, dport)
            s.settimeout(stall_s)
            s.sendall(("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: curl/8\r\nConnection: close\r\n\r\n" % (path, host)).encode())
            head = b""
            while b"\r\n\r\n" not in head:
                chunk = s.recv(4096)
                if not chunk:
                    raise IOError("closed before headers")
                head += chunk
                if len(head) > 65536:
                    raise IOError("bad headers")
            status = head.split(b"\r\n", 1)[0].decode(errors="replace")
            if " 200" not in status and " 206" not in status:
                raise IOError("source answered: " + status[:60])
            ctr.add(len(head.split(b"\r\n\r\n", 1)[1]))
            while not stop.is_set():
                chunk = s.recv(65536)
                if not chunk:
                    break
                ctr.add(len(chunk))
            ctr.downloads += 1
            s.close()
        except socket.timeout:
            ctr.error("stall: no data for %ds (%s)" % (stall_s, host), stream)
        except Exception as e:  # noqa
            ctr.error(("%s: %s (%s)" % (type(e).__name__, e, host))[:110], stream)
        i += 1
        time.sleep(1)


class Proc:
    """A core process with its log; counts log events live."""
    def __init__(self, name, args, workdir, log, env=None):
        self.name, self.args, self.workdir = name, args, workdir
        self.env = dict(os.environ, **(env or {}))
        self.logpath = os.path.join(workdir, log)
        self.counts = collections.Counter()
        self.last_line = ""
        self.restarts = -1
        self.proc = None
        self.lines = []  # kept only until the list line is read
        self._start()

    def _start(self):
        self.restarts += 1
        self.logf = open(self.logpath, "ab", buffering=0)
        self.proc = subprocess.Popen(self.args, cwd=self.workdir, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=self._pump, args=(self.proc,), daemon=True).start()

    def _pump(self, p):
        for raw in iter(p.stdout.readline, b""):
            self.logf.write(raw)
            line = raw.decode("utf-8", "replace").rstrip()
            self.last_line = line[-120:]
            if len(self.lines) < 400:
                self.lines.append(line)
            for k, rx in LOG_PATTERNS.items():
                if rx.search(line):
                    self.counts[k] += 1

    def alive(self):
        return self.proc.poll() is None

    def ensure(self):
        if not self.alive():
            self._start()

    def stop(self):
        try:
            self.proc.kill()
        except Exception:
            pass


def write_conf(path, role, port, key, ctx, store, transports, mode=None):
    lines = ["Role = %s" % role]
    if role == "client":
        lines += ["Inbound = socks5", "Socks5 = 127.0.0.1:%d" % port]
    else:
        lines += ["Mode = l4"]
    lines += ["EncryptionKeyFile = %s" % key, "CookieStore = %s" % store]
    if ctx:
        lines += ["URL = %s" % ctx]
    for ty, url in transports:
        lines += ["", "[Transport %s]" % ty, "Type = %s" % ty, "Priority = 90"]
        if url:
            lines += ["URL = %s" % url]
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


def account_cookies(accounts_path, kind):
    d = json.load(open(accounts_path, encoding="utf-8"))
    for a in d:
        if a["kind"] == kind and a.get("cookies"):
            return a["cookies"]
    raise SystemExit("no saved %s sign-in in %s: sign in in Settings -> Сервисы first" % (kind, accounts_path))


def mailru_document(cookies):
    """Makes a published, editable-by-link docx in Cloud (the calls the app's own script makes) and returns its URL."""
    ck = "; ".join("%s=%s" % kv for kv in cookies.items())

    def call(url, body=None, form=None, token=None, method="POST"):
        h = {"Accept": "application/json", "User-Agent": UA, "Cookie": ck, "Referer": "https://cloud.mail.ru/home/",
             "Origin": "https://cloud.mail.ru", "X-Api-Version": "4"}
        if token:
            h["X-CSRF-Token"] = token
        data = None
        if body is not None:
            h["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        elif form is not None:
            data = urllib.parse.urlencode(dict(form, token=token or "")).encode()
        try:
            r = urllib.request.urlopen(urllib.request.Request(url, data, h, method=method), timeout=30)
            return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    s, b = call("https://cloud.mail.ru/api/v2/tokens/csrf")
    token = json.loads(b)["body"]["token"]
    call("https://cloud.mail.ru/api/v2/folder/add", form={"home": "/openflux-soak", "conflict": "strict"}, token=token)
    path = "/openflux-soak/soak-%d.docx" % int(time.time())
    s, b = call("https://cloud.mail.ru/api/v4/document/create", body={"path": path}, token=token)
    if s >= 300:
        raise SystemExit("mail.ru: document create failed (%s)" % s)
    s, b = call("https://cloud.mail.ru/api/v2/file/publish", form={"home": path}, token=token)
    weblink = json.loads(b).get("body")
    if not isinstance(weblink, str):
        raise SystemExit("mail.ru: publish failed")
    s, b = call("https://cloud.mail.ru/api/v2/weblinks/writable", form={"weblink": weblink}, token=token)
    if s >= 300:
        raise SystemExit("mail.ru: switching the link to editing failed (%s)" % s)
    s, b = call("https://cloud.mail.ru/api/v4/r7/edit", body={"x-email": "anonym", "public": "/" + weblink, "platform": "desktop_web"}, token=token)
    perms = (json.loads(b).get("document") or {}).get("permissions") or {}
    if not perms.get("edit"):
        raise SystemExit("mail.ru: the new document does not open for editing")
    return "https://cloud.mail.ru/public/" + weblink


def start_pair(name, spec, work, exe, key, base_port, accounts, dbg, fresh=False):
    d = os.path.join(work, name)
    os.makedirs(d, exist_ok=True)
    store_exit, store_client = os.path.join(d, "cookies_exit.json"), os.path.join(d, "cookies_client.json")
    ty, url, ctx = spec["type"], spec.get("url"), spec.get("url")
    rooms_file = os.path.join(d, "rooms.txt")
    reuse = ty in ("cupsonline", "avito") and not url and os.path.exists(rooms_file) and not fresh
    if reuse:  # the services limit how fast an address may make new rooms: a soak keeps the ones it has
        url = open(rooms_file).read().strip() or None
    if spec.get("account"):
        jar = account_cookies(accounts, spec["account"])
        if not url and spec["account"] == "Mailru":
            url = ctx = mailru_document(jar)
        for st in (store_exit, store_client):
            json.dump({url: jar}, open(st, "w"))
    else:
        for st in (store_exit, store_client):
            open(st, "w").write("{}")
    port = base_port
    exit_conf, client_conf = os.path.join(d, "exit.conf"), os.path.join(d, "client.conf")
    write_conf(exit_conf, "exit", port, key, ctx, store_exit, [(ty, url)])
    ex = Proc(name + ":exit", [exe, "--config", exit_conf] + dbg, d, "exit.log", spec.get("env"))
    if not url:  # the exit makes its rooms and prints them
        t0 = time.time()
        while time.time() - t0 < 420 and not url:
            for i, l in enumerate(ex.lines):
                if "COPY THIS TO CLIENT" in l and i + 1 < len(ex.lines):
                    url = ex.lines[i + 1].strip()
            time.sleep(1)
        if not url:
            raise SystemExit("%s: the exit printed no room list" % name)
        open(rooms_file, "w").write(url)
        # Both sides must share one primary KDF context, otherwise the exit
        # (placeholder) and the client (room list) handshake under different
        # contexts and race with the classic fallback. Restart the exit with
        # the room list so it rejoins the same rooms under the same context.
        ex.stop()
        write_conf(exit_conf, "exit", port, key, None, store_exit, [(ty, url)])
        ex = Proc(name + ":exit", [exe, "--config", exit_conf] + dbg, d, "exit.log", spec.get("env"))
        time.sleep(8)  # let the exit rejoin its rooms before the client dials
        ctx = None
    write_conf(client_conf, "client", port, key, ctx, store_client, [(ty, url)])
    cl = Proc(name + ":client", [exe, "--config", client_conf] + dbg, d, "client.log", spec.get("env"))
    return ex, cl, port


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--transports", default="cupsonline,avito,mailru")
    ap.add_argument("--extra", help="json file with more transports")
    ap.add_argument("--accounts", default=DEFAULT_ACCOUNTS)
    ap.add_argument("--work", default=os.path.join(os.environ.get("TEMP", "/tmp"), "openflux-soak"))
    ap.add_argument("--streams", type=int, default=1, help="parallel downloads per transport")
    ap.add_argument("--port-base", type=int, default=21100, help="first SOCKS port (a second soak needs another range)")
    ap.add_argument("--source", choices=["local", "internet"], default="local",
                    help="what to download through the tunnel: a server of this script (default) or public speed-test files")
    ap.add_argument("--fresh-rooms", action="store_true", help="make new cups.online / Avito rooms instead of reusing the saved ones")
    ap.add_argument("--duration", type=int, default=0, help="stop after this many seconds (0 = never)")
    ap.add_argument("--debug", action="store_true", help="run the core at debug level (very chatty: every packet)")
    ap.add_argument("--build", action="store_true", help="rebuild the core first")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    stopfile = os.path.join(a.work, "STOP")
    if os.path.exists(stopfile):
        os.remove(stopfile)
    exe = os.path.join(a.work, "openflux-soak.exe" if os.name == "nt" else "openflux-soak")
    if a.build or not os.path.exists(exe):
        print("building the core ...")
        subprocess.check_call(["go", "build", "-o", exe, "."], cwd=CORE, env=dict(os.environ, CGO_ENABLED="0"))
    key = os.path.join(a.work, "key")
    if not os.path.exists(key):
        open(key, "w").write(os.urandom(32).hex())

    specs = {
        "cupsonline": {"type": "cupsonline"},
        "avito": {"type": "avito"},
        "mailru": {"type": "mailru", "account": "Mailru"},
    }
    if a.extra:
        specs.update(json.load(open(a.extra, encoding="utf-8")))
    names = [n for n in a.transports.split(",") if n]
    for n in names:
        if n not in specs and n.split("-")[0] not in ("mailru",):
            raise SystemExit("unknown transport %s (known: %s)" % (n, ", ".join(specs)))

    if a.source == "local":
        host, lport = start_local_source()
        SOURCES[:] = [(host, lport, "/big")]
    else:
        SOURCES[:] = PUBLIC_SOURCES
    stop = threading.Event()
    pairs, ctrs = {}, {}
    for i, n in enumerate(names):
        specs[n].setdefault("type", n.split("-")[0])
        if n.split("-")[0] == "mailru":
            specs[n].setdefault("account", "Mailru")
        print("starting %s ..." % n)
        try:
            ex, cl, port = start_pair(n, specs[n], a.work, exe, key, a.port_base + i, a.accounts, ["-d"] if a.debug else [], a.fresh_rooms)
        except SystemExit as e:
            print("  skipped %s: %s" % (n, e))
            continue
        pairs[n] = (ex, cl)
        ctrs[n] = Counter()
        ctrs[n].logpath = os.path.join(a.work, n, "errors.log")
        for k in range(a.streams):
            threading.Thread(target=downloader, args=(port, ctrs[n], stop, 20, k), daemon=True).start()
    if not pairs:
        raise SystemExit("nothing to run")

    csvf = open(os.path.join(a.work, "soak.csv"), "a", newline="")
    cw = csv.writer(csvf)
    if csvf.tell() == 0:
        cw.writerow(["time", "transport", "bytes_s", "avg60_bytes_s", "total_mb", "dl_errors", "restarts"] + list(LOG_PATTERNS))
    os.system("")  # enable ANSI on Windows terminals
    started = time.time()
    lines_printed = 0

    def shutdown(*_):
        stop.set()

    signal.signal(signal.SIGINT, shutdown)
    try:
        while not stop.is_set() and not os.path.exists(stopfile) and not (a.duration and time.time() - started > a.duration):
            time.sleep(1)
            out = ["OpenFlux soak  %s  up %ds   (Ctrl-C or a file STOP in %s ends it)" % (time.strftime("%H:%M:%S"), time.time() - started, a.work),
                   "%-14s %9s %9s %9s %6s %4s | %s" % ("transport", "now MB/s", "avg60", "total MB", "dlErr", "rst", "429 4004 wserr rot stall retry fail   last download error / last core line")]
            for n, (ex, cl) in pairs.items():
                ex.ensure(); cl.ensure()
                now, avg = ctrs[n].tick()
                c = collections.Counter()
                for p in (ex, cl):
                    c.update(p.counts)
                err = ctrs[n].last_error
                age = int(time.time() - ctrs[n].last_error_t) if ctrs[n].last_error else 0
                out.append("%-14s %9.3f %9.3f %9.0f %6d %4d | %4d %4d %5d %3d %5d %5d %4d   %s" % (
                    n, now / 1e6, avg / 1e6, ctrs[n].bytes_total / 1e6, ctrs[n].errors, ex.restarts + cl.restarts,
                    c["429"], c["4004"], c["wserr"], c["rot"], c["stall"], c["retry"], c["fail"],
                    ("%s (%ds ago)" % (err, age)) if err else ""))
                out.append("%-14s %s" % ("", ("core: " + (cl.last_line or ex.last_line))[:150]))
                cw.writerow([time.strftime("%H:%M:%S"), n, now, int(avg), round(ctrs[n].bytes_total / 1e6, 1), ctrs[n].errors,
                             ex.restarts + cl.restarts] + [c[k] for k in LOG_PATTERNS])
            csvf.flush()
            if lines_printed:
                sys.stdout.write("\x1b[%dA" % lines_printed)
            sys.stdout.write("\n".join(l + "\x1b[K" for l in out) + "\n")
            sys.stdout.flush()
            lines_printed = len(out)
    finally:
        stop.set()
        for ex, cl in pairs.values():
            cl.stop(); ex.stop()
        print("\nstopped; logs and soak.csv are in", a.work)


if __name__ == "__main__":
    main()
