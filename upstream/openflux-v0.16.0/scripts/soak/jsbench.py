#!/usr/bin/env python3
"""One carrier, measured: does it work, how fast, how steady.

Starts an exit and a client of the core on this machine with ONE transport (a native type or a JS package) and
runs, through the client's SOCKS5 port, against a server of this script's own (random bytes, nothing to compress):

  connect     seconds from the client's start to the first answered request
  ttfb        median of 5 small requests (ms)
  down / up   one stream, a body sized to ~15 s at the probed rate, checked by sha256
  par4        4 downloads at once, each checked
  soak        --soak seconds of non-stop download: average, worst 10 s window, errors, stalls, core restarts,
              and a small request every 5 s on another stream (its success rate and p50/p95 under load)

Prints one JSON object per run and appends it to <work>/results.jsonl.

    python jsbench.py --name cups-js --flux dist/cupsonline-1.1.0.flux --pubkey <hex>
    python jsbench.py --name cups-native --type cupsonline
    python jsbench.py --name yandex-js --flux dist/yandex-1.1.0.flux --pubkey <hex> --url https://docs.yandex.ru/... --account Yandex
    python jsbench.py ... --exit-native cupsonline        # native exit, JS client (wire parity)

A sign-in (--account Yandex|Mailru) is read from the app's accounts.json and put in both sides' cookie stores, filtered
by the package's auth.cookies when it declares them.
"""
import argparse, base64, hashlib, json, os, random, socket, statistics, subprocess, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import soak  # noqa: E402  (Proc, Counter, socks_connect, account_cookies, mailru_document, LOG_PATTERNS)

CORE = os.path.abspath(os.path.join(HERE, "..", ".."))


# ---------------------------------------------------------------- the server the tunnel fetches from

def fixed_bytes(n, seed):
    return random.Random(seed).randbytes(n)


def start_server():
    import http.server, socketserver, urllib.parse

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _send(self, code, body, ctype="application/octet-stream"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            q = dict(urllib.parse.parse_qsl(u.query))
            try:
                if u.path == "/ping":
                    self._send(200, b"pong" + os.urandom(16))
                elif u.path == "/fixed":
                    self._send(200, fixed_bytes(int(q["n"]), q.get("seed", "x")))
                elif u.path == "/big":
                    total = 1 << 30
                    self.send_response(200)
                    self.send_header("Content-Length", str(total))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    sent = 0
                    while sent < total:
                        self.wfile.write(os.urandom(65536))
                        sent += 65536
                else:
                    self._send(404, b"no")
            except OSError:
                pass

        def do_POST(self):
            n = int(self.headers.get("Content-Length", "0"))
            h, got = hashlib.sha256(), 0
            try:
                while got < n:
                    b = self.rfile.read(min(65536, n - got))
                    if not b:
                        break
                    h.update(b)
                    got += len(b)
                self._send(200, json.dumps({"n": got, "sha256": h.hexdigest()}).encode(), "application/json")
            except OSError:
                pass

        def log_message(self, *a):
            pass

    class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("203.0.113.1", 9))
        me = probe.getsockname()[0]
    finally:
        probe.close()
    srv = Server((me, 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return me, srv.server_address[1]


# ---------------------------------------------------------------- requests through the tunnel

def request(port, host, hport, method, path, body=b"", timeout=60, on_bytes=None):
    """One HTTP/1.1 request over the client's SOCKS5 port; returns (status, body bytes, seconds to first byte)."""
    t0 = time.time()
    s = soak.socks_connect(port, host, hport, timeout=timeout)
    try:
        s.settimeout(timeout)
        head = "%s %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\nContent-Length: %d\r\n\r\n" % (method, path, host, len(body))
        s.sendall(head.encode())
        view, off = memoryview(body), 0
        while off < len(body):
            k = s.send(view[off:off + 65536])
            off += k
        buf, first = b"", None
        while b"\r\n\r\n" not in buf:
            c = s.recv(65536)
            if not c:
                raise IOError("closed before headers")
            if first is None:
                first = time.time() - t0
            buf += c
        hdr, rest = buf.split(b"\r\n\r\n", 1)
        status = int(hdr.split(b" ", 2)[1])
        length = None
        for line in hdr.split(b"\r\n")[1:]:
            k, _, v = line.partition(b":")
            if k.strip().lower() == b"content-length":
                length = int(v.strip())
        chunks = [rest]
        got = len(rest)
        if on_bytes:
            on_bytes(len(rest))
        while length is None or got < length:
            c = s.recv(262144)
            if not c:
                break
            chunks.append(c)
            got += len(c)
            if on_bytes:
                on_bytes(len(c))
        data = b"".join(chunks)
        if length is not None and len(data) < length:
            raise IOError("short body: %d of %d" % (len(data), length))
        return status, data, first
    finally:
        s.close()


def download(port, srv, n, seed):
    t0 = time.time()
    st, data, _ = request(port, srv[0], srv[1], "GET", "/fixed?n=%d&seed=%s" % (n, seed), timeout=120)
    dt = time.time() - t0
    ok = st == 200 and hashlib.sha256(data).digest() == hashlib.sha256(fixed_bytes(n, seed)).digest()
    return n / dt, ok, dt


def upload(port, srv, n):
    body = os.urandom(n)
    t0 = time.time()
    st, data, _ = request(port, srv[0], srv[1], "POST", "/sink", body=body, timeout=120)
    dt = time.time() - t0
    ok = st == 200 and json.loads(data).get("sha256") == hashlib.sha256(body).hexdigest()
    return n / dt, ok, dt


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


# ---------------------------------------------------------------- the pair of cores

NATIVE_SCRIPTS = [""]


def write_conf(path, role, port, key, store, spec, url, ctx):
    lines = ["Role = %s" % role]
    if NATIVE_SCRIPTS[0]:
        lines += ["NativeScripts = %s" % NATIVE_SCRIPTS[0]]
    lines += ["Inbound = socks5", "Socks5 = 127.0.0.1:%d" % port] if role == "client" else ["Mode = l4"]
    lines += ["EncryptionKeyFile = %s" % key, "CookieStore = %s" % store]
    if ctx:
        lines += ["URL = %s" % ctx]
    lines += ["", "[Transport t]", "Priority = 90"]
    if spec.get("flux"):
        lines += ["Type = script", "Path = %s" % spec["flux"], "Pubkey = %s" % spec["pubkey"], "Name = %s" % spec["id"]]
        params = dict(spec.get("params") or {})
        params.update(spec.get(role + "_params") or {})
        if params:
            enc = base64.urlsafe_b64encode(json.dumps({k: str(v) for k, v in params.items()}).encode()).decode().rstrip("=")
            lines += ["Params = %s" % enc]
    else:
        lines += ["Type = %s" % spec["type"]]
        for k, v in (spec.get(role + "_conf") or {}).items():
            lines += ["%s = %s" % (k, v)]
    if url:
        lines += ["URL = %s" % url]
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


def inspect_auth(exe, flux):
    """The package's declared sign-in (None without one)."""
    try:
        out = subprocess.run([exe, "--inspect-script", "--data=" + flux], capture_output=True, text=True, timeout=60).stdout
        return json.loads(out.strip().splitlines()[-1]).get("auth")
    except Exception:  # noqa
        return None


def cookie_key(spec, url):
    if spec.get("flux"):
        return "script:%s %s" % (spec["id"], url or "")
    return url or ""


def wait_rooms(proc, deadline_s):
    t0 = time.time()
    while time.time() - t0 < deadline_s:
        for i, l in enumerate(proc.lines):
            if "COPY THIS TO CLIENT" in l and i + 1 < len(proc.lines):
                v = proc.lines[i + 1].strip()
                if v and not v.startswith("==="):
                    return v
        if not proc.alive():
            return None
        time.sleep(1)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True, help="label of this run (directory and result row)")
    ap.add_argument("--flux", help="JS package for both sides (unless --exit-native)")
    ap.add_argument("--pubkey", help="key the package is signed with (hex)")
    ap.add_argument("--type", help="native type for both sides (instead of --flux)")
    ap.add_argument("--exit-native", help="run the exit on this native type, the client on --flux (wire parity)")
    ap.add_argument("--client-native", help="run the client on this native type, the exit on --flux")
    ap.add_argument("--url", help="document / board / call / room list both sides open (none: the exit makes rooms)")
    ap.add_argument("--params", default="{}", help="JSON params for the script on both sides")
    ap.add_argument("--exit-params", default="{}")
    ap.add_argument("--client-params", default="{}")
    ap.add_argument("--account", help="Yandex | Mailru: the app's saved sign-in goes into both cookie stores")
    ap.add_argument("--accounts", default=os.path.join(os.environ.get("APPDATA", ""), "OpenFlux", "accounts.json"))
    ap.add_argument("--mailru-doc", action="store_true", help="make a fresh Mail.ru document for the run")
    ap.add_argument("--soak", type=int, default=600, help="seconds of non-stop load (0: skip)")
    ap.add_argument("--connect-timeout", type=int, default=240)
    ap.add_argument("--rooms-timeout", type=int, default=420)
    ap.add_argument("--work", default=os.path.join(os.environ.get("TEMP", "/tmp"), "openflux-jsbench"))
    ap.add_argument("--port", type=int, default=21300)
    ap.add_argument("--exe", help="core binary (default: built from this checkout into --work)")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--native-scripts", default="", help="NativeScripts dir for both cores (JS packages replacing --type)")
    ap.add_argument("--keep", action="store_true", help="leave both cores running at the end")
    ap.add_argument("--quick", type=int, default=0, metavar="MB",
                    help="only connect and download this many MB (sha256 checked); no up/par4/soak")
    ap.add_argument("--quick-timeout", type=int, default=600, help="give the --quick download this long, then report what came")
    a = ap.parse_args()
    NATIVE_SCRIPTS[0] = os.path.abspath(a.native_scripts) if a.native_scripts else ""

    os.makedirs(a.work, exist_ok=True)
    exe = a.exe or os.path.join(a.work, "openflux-bench.exe" if os.name == "nt" else "openflux-bench")
    if not a.exe:
        subprocess.check_call(["go", "build", "-o", exe, "."], cwd=CORE, env=dict(os.environ, CGO_ENABLED="0"))
    key = os.path.join(a.work, "key")
    if not os.path.exists(key):
        open(key, "w").write(os.urandom(32).hex())

    d = os.path.join(a.work, a.name)
    os.makedirs(d, exist_ok=True)
    for f in ("exit.log", "client.log"):
        if os.path.exists(os.path.join(d, f)):
            os.replace(os.path.join(d, f), os.path.join(d, f + ".prev"))

    js = None
    if a.flux:
        flux = os.path.abspath(a.flux)
        pkg_id = os.path.basename(flux).rsplit("-", 1)[0]
        js = {"flux": flux, "pubkey": a.pubkey, "id": pkg_id, "params": json.loads(a.params),
              "exit_params": json.loads(a.exit_params), "client_params": json.loads(a.client_params)}
    exit_spec = {"type": a.exit_native} if a.exit_native else (js or {"type": a.type})
    client_spec = {"type": a.client_native} if a.client_native else (js or {"type": a.type})

    result = {"name": a.name, "time": time.strftime("%Y-%m-%d %H:%M:%S"), "exit": exit_spec.get("id") or exit_spec.get("type"),
              "client": client_spec.get("id") or client_spec.get("type"), "js": bool(js), "url_given": bool(a.url)}

    url = a.url
    ctx = url
    jar = {}
    if a.account:
        jar = soak.account_cookies(a.accounts, a.account)
        auth = inspect_auth(exe, js["flux"]) if js else None
        if auth and auth.get("cookies"):
            jar = {k: v for k, v in jar.items() if k in set(auth["cookies"])}
        result["cookies"] = len(jar)
        if a.mailru_doc and not url:
            url = ctx = soak.mailru_document(soak.account_cookies(a.accounts, a.account))

    def stores():
        for role, spec in (("exit", exit_spec), ("client", client_spec)):
            p = os.path.join(d, "cookies_%s.json" % role)
            json.dump({cookie_key(spec, url): jar} if jar else {}, open(p, "w"))
            yield p

    store_exit, store_client = list(stores())
    dbg = ["-dd"] if a.debug else []
    exit_conf, client_conf = os.path.join(d, "exit.conf"), os.path.join(d, "client.conf")
    write_conf(exit_conf, "exit", a.port, key, store_exit, exit_spec, url, ctx)
    ex = soak.Proc(a.name + ":exit", [exe, "--config", exit_conf] + dbg, d, "exit.log")
    cl = None
    srv = start_server()
    try:
        if not url:
            url = wait_rooms(ex, a.rooms_timeout)
            if not url:
                raise SystemExit("the exit printed no room list (see %s)" % os.path.join(d, "exit.log"))
            result["rooms"] = len(url)
            ex.stop()
            store_exit, store_client = list(stores())
            write_conf(exit_conf, "exit", a.port, key, store_exit, exit_spec, url, None)
            ex = soak.Proc(a.name + ":exit", [exe, "--config", exit_conf] + dbg, d, "exit.log")
            time.sleep(8)
            ctx = None
        write_conf(client_conf, "client", a.port, key, store_client, client_spec, url, ctx)
        t_client = time.time()
        cl = soak.Proc(a.name + ":client", [exe, "--config", client_conf] + dbg, d, "client.log")

        # connect
        connected = None
        while time.time() - t_client < a.connect_timeout:
            try:
                st, _, _ = request(a.port, srv[0], srv[1], "GET", "/ping", timeout=20)
                if st == 200:
                    connected = time.time() - t_client
                    break
            except Exception as e:  # noqa
                result["connect_error"] = str(e)[:120]
            if not ex.alive() or not cl.alive():
                break
            time.sleep(2)
        result["connect_s"] = round(connected, 1) if connected else None
        if not connected:
            result["works"] = False
            raise SystemExit("no connection in %ds" % a.connect_timeout)
        result.pop("connect_error", None)

        # ttfb
        lat = []
        for _ in range(5):
            try:
                t0 = time.time()
                request(a.port, srv[0], srv[1], "GET", "/ping", timeout=30)
                lat.append((time.time() - t0) * 1000)
            except Exception:  # noqa
                pass
        result["ttfb_ms"] = round(statistics.median(lat)) if lat else None

        if a.quick:
            n = a.quick * 1000 * 1000
            got = [0]
            out = {}

            def fetch():
                t0 = time.time()
                try:
                    st, data, _ = request(a.port, srv[0], srv[1], "GET", "/fixed?n=%d&seed=quick" % n, timeout=120,
                                          on_bytes=lambda k: got.__setitem__(0, got[0] + k))
                    out["dt"] = time.time() - t0
                    out["ok"] = st == 200 and hashlib.sha256(data).digest() == hashlib.sha256(fixed_bytes(n, "quick")).digest()
                except Exception as e:  # noqa
                    out["error"] = str(e)[:120]
            th = threading.Thread(target=fetch, daemon=True)
            t0 = time.time()
            th.start()
            th.join(a.quick_timeout)
            if "dt" in out:
                result.update(down_MBps=round(n / out["dt"] / 1e6, 3), down_ok=out["ok"], down_bytes=n, down_s=round(out["dt"], 1))
            else:
                dt = time.time() - t0
                result.update(down_MBps=round(got[0] / dt / 1e6, 3), down_ok=False, down_bytes=got[0], down_s=round(dt, 1),
                              down_error=out.get("error") or "not done in %ds (%d of %d bytes)" % (a.quick_timeout, got[0], n))
            # works: connected and data flows (the whole file checked, or part of it in time)
            result["works"] = bool(result.get("down_ok") or (got[0] > 0 and "error" not in out))
            raise SystemExit(0)

        # down / up, sized from a probe
        def measure(fn, label, *args):
            try:
                rate, ok, _ = fn(*args)
                return rate, ok
            except Exception as e:  # noqa
                result[label + "_error"] = str(e)[:120]
                return 0, False

        def ramp(fn, label, *args):
            """Doubles the body until one transfer takes 8 s (a carrier ramps up; a small probe undersells it)."""
            n, allok, rate = 256 << 10, True, 0
            for _ in range(10):
                t0 = time.time()
                rate, ok = measure(fn, label, *(args + ((n, "d%d" % n) if fn is download else (n,))))
                allok = allok and ok
                if not ok or time.time() - t0 >= 8 or n >= 96 << 20:
                    break
                n = int(clamp(max(n * 2, rate * 12), n * 2, 96 << 20))
            return rate, allok, n

        r, ok, n = ramp(download, "down", a.port, srv)
        result.update(down_MBps=round(r / 1e6, 3), down_ok=ok, down_bytes=n)
        u, uok, n = ramp(upload, "up", a.port, srv)
        result.update(up_MBps=round(u / 1e6, 3), up_ok=uok, up_bytes=n)

        # 4 parallel
        n = int(clamp(r * 15 / 4, 128 << 10, 48 << 20))
        outs = [None] * 4

        def one(i):
            outs[i] = measure(download, "par%d" % i, a.port, srv, n, "q%d" % i)
        t0 = time.time()
        th = [threading.Thread(target=one, args=(i,)) for i in range(4)]
        [t.start() for t in th]
        [t.join() for t in th]
        dt = time.time() - t0
        result.update(par4_MBps=round(4 * n / dt / 1e6, 3) if all(o and o[1] for o in outs) else round(sum(n for o in outs if o and o[1]) / dt / 1e6, 3),
                      par4_ok=all(o and o[1] for o in outs))
        result["works"] = bool(result["down_ok"] and result["up_ok"])

        # soak
        if a.soak > 0:
            ctr = soak.Counter()
            ctr.logpath = os.path.join(d, "soak-errors.log")
            stop = threading.Event()
            soak.SOURCES[:] = [(srv[0], srv[1], "/big")]
            threading.Thread(target=soak.downloader, args=(a.port, ctr, stop, 20, 0), daemon=True).start()
            pings, ping_ok = [], 0
            per_s = []
            restarts0 = (ex.restarts, cl.restarts)
            t_end = time.time() + a.soak
            next_ping = time.time()
            while time.time() < t_end:
                time.sleep(1)
                ex.ensure()
                cl.ensure()
                now, _ = ctr.tick()
                per_s.append(now)
                if time.time() >= next_ping:
                    next_ping = time.time() + 5
                    try:
                        t0 = time.time()
                        request(a.port, srv[0], srv[1], "GET", "/ping", timeout=15)
                        pings.append((time.time() - t0) * 1000)
                        ping_ok += 1
                    except Exception:  # noqa
                        pings.append(None)
            stop.set()
            win = [sum(per_s[i:i + 10]) / 10 for i in range(0, max(1, len(per_s) - 9), 10)]
            good = sorted(p for p in pings if p is not None)
            logs = {k: ex.counts[k] + cl.counts[k] for k in soak.LOG_PATTERNS}
            result.update(
                soak_s=a.soak,
                soak_avg_MBps=round(sum(per_s) / max(1, len(per_s)) / 1e6, 3),
                soak_min10_MBps=round(min(win) / 1e6, 3) if win else 0,
                soak_zero_s=sum(1 for v in per_s if v == 0),
                soak_dl_errors=ctr.errors,
                soak_last_error=ctr.last_error[:120],
                soak_ping_ok=round(ping_ok / max(1, len(pings)), 3),
                soak_ping_p50_ms=round(good[len(good) // 2]) if good else None,
                soak_ping_p95_ms=round(good[int(len(good) * 0.95) - 1]) if good else None,
                core_restarts=(ex.restarts - restarts0[0]) + (cl.restarts - restarts0[1]),
                logs=logs,
            )
    except SystemExit as e:
        if str(e) not in ("0", "None"):
            result["error"] = str(e)
    finally:
        result["exit_last"] = ex.last_line[-160:]
        if cl:
            result["client_last"] = cl.last_line[-160:]
        if not a.keep:
            ex.stop()
            if cl:
                cl.stop()
        print(json.dumps(result, ensure_ascii=False))
        with open(os.path.join(a.work, "results.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(result, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
