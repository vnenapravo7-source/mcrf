#!/usr/bin/env python3
"""Creates a Yandex board open to guests as editors (what the app does from the Boards page) and prints its link.

    python yboard.py [--accounts accounts.json] [--name NAME]

The sign-in is the app's saved Yandex one. No proxy, IPv4 only: on a network whose IPv6 path is broken a dial to a Yandex host
otherwise waits out its timeout.
"""
import argparse, base64, http.client, json, os, re, socket, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"

_orig = socket.getaddrinfo
socket.getaddrinfo = lambda host, port, family=0, *a, **k: _orig(host, port, socket.AF_INET, *a, **k)
# Straight out: urllib on Windows otherwise takes the system proxy from the registry.
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--accounts", default=os.path.join(os.environ.get("APPDATA", ""), "OpenFlux", "accounts.json"))
    ap.add_argument("--name", default="OpenFlux")
    a = ap.parse_args()
    jar = next(x["cookies"] for x in json.load(open(a.accounts, encoding="utf-8")) if x["kind"] == "Yandex")
    cookie = "; ".join("%s=%s" % kv for kv in jar.items())
    h = {"User-Agent": UA, "Cookie": cookie}
    # One connection for every call: boards.yandex.ru drops an address's new connections that come in quick
    # succession (measured: the first goes through, the next few seconds' time out).
    conn = http.client.HTTPSConnection("boards.yandex.ru", 443, timeout=30)

    def req(method, path, body=None, extra=None):
        conn.request(method, path, body, dict(h, **(extra or {})))
        r = conn.getresponse()
        return r.status, r.read()
    st, raw = req("GET", "/cabinet/")
    html = raw.decode("utf-8", "replace")

    def tok(name):
        m = re.search(name + r"""\s*[=:]\s*["']([^"']+)""", html)
        return m.group(1) if m else ""
    state = {"csrf": tok("whiteboard_csrf_token"), "token": tok("whiteboard_client_token")}
    if not state["csrf"]:
        raise SystemExit("no whiteboard_csrf_token on the cabinet page (signed out?)")

    def call(action, body):
        if state["token"]:
            body["token"] = state["token"]
        body["csrf"] = state["csrf"]
        content = base64.b64encode(json.dumps(body, ensure_ascii=False).encode()).decode()
        st, raw = req("POST", "/api", json.dumps({"action": action, "content": content}).encode(),
                      {"Content-Type": "application/json", "Origin": "https://boards.yandex.ru", "Referer": "https://boards.yandex.ru/cabinet/"})
        j = json.loads(raw or b"{}")
        if j.get("csrf"):
            state["csrf"] = j["csrf"]
        if j.get("error"):
            raise SystemExit("%s: %s" % (action, j["error"]))
        return j
    board = call("make-whiteboard", {"name": a.name, "access_type": 10})
    if not board.get("hash"):
        raise SystemExit("make-whiteboard: no hash")
    props = dict(board.get("properties") or {}, guest_mode=True, guest_role=1)
    if call("update-dashboard-presentation", {"media": board["hash"], "properties": props}).get("result") is not True:
        raise SystemExit("could not open the board to guests as editors")
    print("https://boards.yandex.ru/whiteboard/?hash=" + board["hash"])


if __name__ == "__main__":
    main()
