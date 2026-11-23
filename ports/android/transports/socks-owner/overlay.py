"""Apply exact upstream anchors to private Android SOCKS producer inputs."""
import argparse
import hashlib
import json
from pathlib import Path


SOURCE = Path(__file__).resolve().parent
SING_PINS = {
    "protocol/socks/client.go": "85015fbfec7c5d7783c07c435bc71f4b38ac46c44dc4f1678c94f841b9ad7e01",
    "protocol/socks/handshake.go": "519634a01cba3041477aac4c5a767424b9167708a96af90bdf746a970aad3be3",
}


def replace(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Pinned SOCKS anchor differs: {old[:100]!r}")
    return text.replace(old, new)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(root, path, pin=None):
    file = root / path
    if file.is_symlink():
        raise ValueError(f"Refused linked SOCKS input: {path}")
    raw = file.read_bytes()
    if pin is not None and sha(raw.replace(b"\r\n", b"\n")) != pin:
        raise ValueError(f"Pinned SOCKS input differs: {path}")
    return raw.decode("utf-8").replace("\r\n", "\n")


def sing_client(root):
    client, handshake = SING_PINS
    code = read(root, client, SING_PINS[client])
    code = replace(code, "\tcase Version5:\n\t\tvar response socks5.Response", """\tcase Version5:
		if command == socks5.CommandUDPAssociate && c.serverAddr.Addr.IsLoopback() && c.username != "" {
			packetConn, pinnedErr := c.mcrfPinnedUDP(tcpConn, address)
			if pinnedErr != nil {
				tcpConn.Close()
				return nil, pinnedErr
			}
			return packetConn, nil
		}
		var response socks5.Response""")
    auth = read(root, handshake, SING_PINS[handshake])
    declaration = "func ClientHandshake5(conn io.ReadWriter, command byte, destination M.Socksaddr, username string, password string) (socks5.Response, error) {"
    auth = replace(auth, declaration, declaration + """
	return clientHandshake5(conn, command, destination, username, password, false)
}

func clientHandshake5(conn io.ReadWriter, command byte, destination M.Socksaddr, username string, password string, pinned bool) (socks5.Response, error) {""")
    auth = replace(auth, "\tif authResponse.Method == socks5.AuthTypeUsernamePassword {",
                   """\tif pinned && authResponse.Method != socks5.AuthTypeUsernamePassword {
		return socks5.Response{}, E.New("socks5: private UDP authentication required")
	}
	if authResponse.Method == socks5.AuthTypeUsernamePassword {""")
    auth = replace(auth, "\tif command == socks5.CommandUDPAssociate {",
                   "\tif command == socks5.CommandUDPAssociate && !pinned {")
    return {
        client: code,
        handshake: auth,
        "protocol/socks/mcrf_pinned_udp.go": (SOURCE / "mcrf_pinned_udp.go").read_text(encoding="utf-8"),
        "protocol/socks/mcrf_pinned_udp_test.go": (SOURCE / "mcrf_pinned_udp_test.go").read_text(encoding="utf-8"),
    }


def xray_worker(root, variant):
    protocol = "proxy/socks/protocol.go"
    udp = "proxy/socks/temp_udp_listen.go"
    pin = {
        "xray": "40f68c35d50c64662121180f315e84d3713e079b64e8b0944c91c4bce1c848b4",
        "s3": "3b6723cb8c358da8ca26c7f4672b578a3e9de5bd30c291e7ee5da30df5195cbd",
    }[variant]
    code = read(root, protocol, pin)
    code = replace(code, "\t\tudpHub, err := internet.ListenSystemPacket", f"""\t\tcontrolPeer, tcp := writer.RemoteAddr().(*net.TCPAddr)
		if username == "" || !tcp || !controlPeer.IP.IsLoopback() ||
			request.Address.Family().IsDomain() || request.Port == 0 ||
			!request.Address.IP().Equal(controlPeer.IP) ||
			responseAddress.Family().IsDomain() || !responseAddress.IP().IsLoopback() {{
			_ = writeSocks5Response(writer, 0x02, net.AnyIP, net.Port(0))
			return nil, nil, errors.New("MCRF_SOCKS_UDP_SOURCE_V1_{'s3bypass' if variant == 's3' else 'xray'}: authenticated loopback source tuple required")
		}}
		expectedRemote := &gonet.UDPAddr{{IP: controlPeer.IP, Port: int(request.Port)}}
		udpHub, err := internet.ListenSystemPacket""")
    code = replace(code, """\t\texpectedRemote := &gonet.UDPAddr{}
		// UDP Associate should not specify a domain as source IP
		if request.Address.Family().IsDomain() || request.Address.IP().IsUnspecified() {
			expectedRemote.IP = writer.RemoteAddr().(*net.TCPAddr).IP // unix?
		} else {
			expectedRemote.IP = request.Address.IP()
			expectedRemote.Port = int(request.Port) // 0 is allowed
		}
""", "")
    reader = read(root, udp, "14ce91f83c617fce7c35b6cb668c2443a544c8b760bafb3b13a336caff06b34f")
    reader = replace(reader, "// TempUDPConn wait for the first packet to determine the remote address",
                     "// TempUDPConn admits only the tuple declared on the authenticated TCP control.")
    reader = replace(reader, """\t\tif remote.IP.Equal(expected.IP) {
			if remote.Port == expected.Port {
				c.Timer.Update()
				return
			}
			if expected.Port == 0 {
				c.ExpectedRemote.Store(remote)
				c.Timer.Update()
				return
			}
		}""", """\t\tif remote.IP.Equal(expected.IP) && remote.Port == expected.Port && expected.Port != 0 {
			c.Timer.Update()
			return
		}""")
    reader = replace(reader, "\tc.Timer.SetTimeout(0)",
                     "\tif c.Timer != nil {\n\t\tc.Timer.SetTimeout(0)\n\t}")
    return {protocol: code, udp: reader}


def qwdtt_worker(root):
    relative = "bridge_socks.go"
    text = read(root, relative)
    text = replace(text, "_ = socksUDPAssociation(ctx, c, dial)", """if user == "" || pass == "" {
					_ = socksReply(c, 2, "")
					return
				}
				_ = socksUDPAssociation(ctx, c, dial, address)""")
    text = replace(text, "func socksUDPAssociation(parent context.Context, control net.Conn, dial bridgeDial) error {",
                   """func socksUDPAssociation(parent context.Context, control net.Conn, dial bridgeDial, address string) error {
	host, port, splitErr := net.SplitHostPort(address)
	clientIP := net.ParseIP(host)
	clientPort, portErr := strconv.Atoi(port)
	controlPeer, tcp := control.RemoteAddr().(*net.TCPAddr)
	if splitErr != nil || portErr != nil || clientPort < 1 || clientPort > 65535 ||
		clientIP == nil || !clientIP.IsLoopback() || !tcp || !clientIP.Equal(controlPeer.IP) {
		_ = socksReply(control, 2, "")
		return fmt.Errorf("MCRF_SOCKS_UDP_SOURCE_V1_qwdtt: authenticated loopback source tuple required")
	}
	client := &net.UDPAddr{IP: clientIP, Port: clientPort}""")
    if '"strconv"' not in text:
        text = replace(text, '\t"net"\n', '\t"net"\n\t"strconv"\n')
    text = replace(text, "\tvar client *net.UDPAddr\n\tcontrolIP := control.RemoteAddr().(*net.TCPAddr).IP\n", "")
    text = replace(text, "!source.IP.Equal(controlIP) || n < 4",
                   "!source.IP.Equal(client.IP) || source.Port != client.Port || n < 4")
    text = replace(text, """\t\tif client == nil {
			client = source
		}
		if source.String() != client.String() {
			continue
		}
""", "")
    test = fixture("main", '_ = socksUDPAssociation(context.Background(), control, mcrfUDPFixtureDialer{}.DialContext, address)')
    return {relative: text, "mcrf_socks_udp_owner_test.go": test}


def wdtt_worker(root):
    relative = "socks5_server.go"
    text = read(root, relative)
    start = "\trequestedIP := net.ParseIP(request.host)"
    end = "\n\trelayIP := net.IPv4(127, 0, 0, 1)"
    position = text.index("func (server *socks5Server) serveUDPAssociation(")
    beginning = text.index(start, position)
    finish = text.index(end, beginning)
    old = text[beginning:finish]
    if "requestedIP.Equal(net.ParseIP(controlHost))" not in old:
        raise ValueError("Pinned WDTT owner check differs")
    text = text[:beginning] + """\trequestedIP := net.ParseIP(request.host)
	if !server.authEnabled || request.port == 0 || requestedIP == nil || !requestedIP.IsLoopback() ||
		!requestedIP.Equal(net.ParseIP(controlHost)) {
		_ = writeSocksReply(control, socksReplyNotAllowed, nil)
		return fmt.Errorf("MCRF_SOCKS_UDP_SOURCE_V1_wdtt: authenticated loopback source tuple required")
	}
	clientAddress := &net.UDPAddr{IP: requestedIP, Port: int(request.port)}
""" + text[finish:]
    text = replace(text, "\tvar clientAddress *net.UDPAddr\n", "")
    text = replace(text, """\trelayIP := net.IPv4(127, 0, 0, 1)
	if server.access.listenIP.IsValid() {
		relayIP = net.IP(server.access.listenIP.AsSlice())
	}
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: relayIP})""", """\tlocal, tcp := control.LocalAddr().(*net.TCPAddr)
	if !tcp || !local.IP.IsLoopback() {
		_ = writeSocksReply(control, socksReplyNotAllowed, nil)
		return fmt.Errorf("WDTT UDP relay requires a loopback control")
	}
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: local.IP})""")
    text = replace(text, """\t\tif !source.IP.Equal(net.ParseIP(controlHost)) {
			continue
		}
		if clientAddress == nil {
			if request.port != 0 && int(request.port) != source.Port {
				continue
			}
			clientAddress = source
		} else if !source.IP.Equal(clientAddress.IP) || source.Port != clientAddress.Port {
			continue
		}""", """\t\tif !source.IP.Equal(clientAddress.IP) || source.Port != clientAddress.Port {
			continue
		}""")
    test = fixture("main", """_ = address
		worker := &socks5Server{dialer: mcrfUDPFixtureDialer{}, authEnabled: true}
		_ = worker.serveUDPAssociation(context.Background(), control, socksRequest{host: "127.0.0.1", port: uint16(sourcePort)})""")
    return {relative: text, "mcrf_socks_udp_owner_test.go": test}


def masterdns_worker(root):
    relative = "internal/client/socks_manager.go"
    text = read(root, relative, "1afaf109b01cdfa4e009d4d37e423b31349734e5a374bc0b9eafd1e4195154fa")
    start = text.index("\treplyIP := net.ParseIP(c.cfg.ListenIP)", text.index("func (c *Client) handleSocksUDPAssociate("))
    finish = text.index("\tif err != nil {", text.index('udpConn, err := net.ListenUDP("udp", bindAddr)', start))
    text = text[:start] + """\tclientIP := net.ParseIP(clientAddr)
	controlPeer, tcpPeer := conn.RemoteAddr().(*net.TCPAddr)
	local, tcpLocal := conn.LocalAddr().(*net.TCPAddr)
	if !c.cfg.SOCKS5Auth || atyp != SOCKS5_ATYP_IPV4 || clientPort == 0 || clientIP == nil ||
		!clientIP.IsLoopback() || !tcpPeer || !clientIP.Equal(controlPeer.IP) ||
		!tcpLocal || !local.IP.IsLoopback() {
		if c.log != nil {
			c.log.Debugf("MCRF_SOCKS_UDP_SOURCE_V1_masterdnsvpn: authenticated loopback source tuple required")
		}
		_ = c.sendSocksReply(conn, SOCKS5_REPLY_RULESET_DENIED, SOCKS5_ATYP_IPV4, net.IPv4zero, 0)
		return
	}
	expected := &net.UDPAddr{IP: clientIP, Port: int(clientPort)}
	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: local.IP})
""" + text[finish:]
    text = replace(text, "SOCKS5_REPLY_SUCCESS, replyATYP, boundAddr.IP, uint16(boundAddr.Port)",
                   "SOCKS5_REPLY_SUCCESS, SOCKS5_ATYP_IPV4, boundAddr.IP, uint16(boundAddr.Port)")
    text = replace(text, "\tbuf := make([]byte, 4096)\n\tfor {",
                   """\tassociation, cancel := context.WithCancel(ctx)
	controlDone := make(chan struct{})
	go func() {
		defer close(controlDone)
		var extra [1]byte
		_, _ = conn.Read(extra[:])
		cancel()
		_ = udpConn.Close()
	}()
	stop := context.AfterFunc(association, func() { _ = udpConn.Close() })
	defer func() {
		cancel()
		stop()
		_ = conn.Close()
		<-controlDone
	}()
	buf := make([]byte, 4096)
	deadline := time.Now().Add(c.cfg.SOCKSUDPAssociateReadTimeout())
	for {""")
    text = replace(text, "\t\t_ = udpConn.SetReadDeadline(time.Now().Add(c.cfg.SOCKSUDPAssociateReadTimeout()))",
                   "\t\t_ = udpConn.SetReadDeadline(deadline)")
    text = replace(text, "\t\tif n < 6 {",
                   "\t\tif association.Err() != nil || !peerAddr.IP.Equal(expected.IP) || peerAddr.Port != expected.Port || n < 6 {")
    text = replace(text, "\t\tif buf[2] != 0x00 {", "\t\tif buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {")
    text = replace(text, "\t\tdnsQuery := buf[payloadOffset:n]",
                   "\t\tdeadline = time.Now().Add(c.cfg.SOCKSUDPAssociateReadTimeout())\n\t\tdnsQuery := buf[payloadOffset:n]")
    text = replace(text, "\t\t\t_, _ = udpConn.WriteToUDP(fullResp, peerAddr)",
                   "\t\t\tif association.Err() == nil {\n\t\t\t\t_, _ = udpConn.WriteToUDP(fullResp, expected)\n\t\t\t}")
    return {
        relative: text,
        "internal/client/mcrf_socks_udp_owner_test.go": (SOURCE / "masterdns_test.go.txt").read_text(encoding="utf-8"),
    }


def openflux_worker(root):
    relative = "socks5/socks5.go"
    text = read(root, relative, "56fee34b4fec7fd67cac0c6fd407e676a9f0c5845f70bf2d0028a16aa946a02b")
    start = text.index("\trequestedHost, requestedService", text.index("func (s *SOCKS5Server) handleUDPAssociate("))
    end = text.index("\tudpConn, err :=", start)
    text = text[:start] + """\trequestedHost, requestedService, parseErr := net.SplitHostPort(requestedAddr)
	requestedIP := net.ParseIP(requestedHost)
	requestedPort, portErr := strconv.Atoi(requestedService)
	peer, tcpPeer := control.RemoteAddr().(*net.TCPAddr)
	local, tcpLocal := control.LocalAddr().(*net.TCPAddr)
	if s.username == "" || s.password == "" || parseErr != nil || portErr != nil ||
		requestedPort < 1 || requestedPort > 65535 || requestedIP == nil ||
		!requestedIP.IsLoopback() || !tcpPeer || !requestedIP.Equal(peer.IP) ||
		!tcpLocal || !local.IP.IsLoopback() {
		utils.Debugf("MCRF_SOCKS_UDP_SOURCE_V1_openflux: authenticated loopback source tuple required")
		_ = writeReply(control, 0x02, nil)
		return
	}
	clientAddr := &net.UDPAddr{IP: requestedIP, Port: requestedPort}
	bindIP := local.IP
""" + text[end:]
    if '"strconv"' not in text:
        text = replace(text, '\t"net"\n', '\t"net"\n\t"strconv"\n')
    start = text.index("\tvar clientAddr *net.UDPAddr", start)
    finish = text.index("\tif err := writeReply(control, 0x00, udpConn.LocalAddr());", start)
    text = text[:start] + text[finish:]
    start = text.index("\t\t\tif !from.IP.Equal(expectedIP)", start)
    finish = text.index("\t\t\tdest, payload, err :=", start)
    text = text[:start] + """\t\t\tif !from.IP.Equal(clientAddr.IP) || from.Port != clientAddr.Port {
				continue
			}
""" + text[finish:]
    text = replace(text, "\t\t\tclientMu.Lock()\n\t\t\tclientAddr = from\n\t\t\tclientMu.Unlock()\n", "")
    text = replace(text, """\t\t\t\t\t\tclientMu.RLock()
						to := clientAddr
						clientMu.RUnlock()
						if to != nil {
							_ = udpConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
							_, _ = udpConn.WriteToUDP(packet, to)
						}""", """\t\t\t\t\t\t_ = udpConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
						_, _ = udpConn.WriteToUDP(packet, clientAddr)""")
    test = fixture("socks5", """worker := NewSOCKS5Server("127.0.0.1:0", mcrfUDPFixtureDialer{})
		worker.SetAuth("owned", "fixture")
		worker.handleUDPAssociate(control, address)""")
    return {
        relative: text,
        "socks5/mcrf_socks_udp_owner_test.go": test,
    }


def fixture(package, serve):
    return (SOURCE / "worker_test.go.txt").read_text(encoding="utf-8").replace(
        "package PACKAGE", f"package {package}").replace("SERVE_ASSOCIATION", serve)


def tls_worker(root):
    relative = "protocol/socks/inbound.go"
    text = read(root, relative, "475610eedac63c427660e1869c442bd5fd814bd024799128436d780e2783480e")
    text = replace(text, '\t"github.com/sagernet/sing-box/common/uot"\n', "")
    text = replace(text, "router:        uot.NewRouter(router, logger),", "router:        router,")
    text = replace(text, "h.streamUserPacketConnection), h.listener, h.udpTimeout,",
                   "h.streamUserPacketConnection), mcrfTCPOnlySOCKS{}, h.udpTimeout,")
    text += """
// The authenticated TLS worker has a TCP-only ingress contract.
type mcrfTCPOnlySOCKS struct{}

func (mcrfTCPOnlySOCKS) ListenPacket(net.ListenConfig, context.Context, string, string) (net.PacketConn, error) {
	return nil, E.New("MCRF_SOCKS_UDP_SOURCE_V1_tls-core: UDP ASSOCIATE disabled on the TCP-only worker")
}
"""
    return {relative: text}


WORKERS = {
    "sing-client": sing_client,
    "xray": lambda root: xray_worker(root, "xray"),
    "s3": lambda root: xray_worker(root, "s3"),
    "qwdtt": qwdtt_worker,
    "wdtt": wdtt_worker,
    "masterdns": masterdns_worker,
    "openflux": openflux_worker,
    "tls": tls_worker,
}


def apply(mode, root):
    files = WORKERS[mode](root)
    manifest = {}
    before = {}
    for relative, text in files.items():
        path = root / relative
        if path.is_symlink():
            raise ValueError(f"Refused linked SOCKS output: {relative}")
        data = text.encode("utf-8")
        if path.is_file():
            before[relative] = sha(path.read_bytes())
            path.chmod(0o600)
        path.write_bytes(data)
        manifest[relative] = sha(data)
    return {"contract": 1, "mode": mode, "inputs": before, "files": manifest,
            "overlaySha256": sha(Path(__file__).read_bytes())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=WORKERS)
    parser.add_argument("stage", type=Path)
    options = parser.parse_args()
    print(json.dumps(apply(options.mode, options.stage.resolve()), sort_keys=True))
