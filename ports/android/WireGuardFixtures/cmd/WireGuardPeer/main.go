// Test-only local peer for the coordinator's Android libbox/device traffic gate.
package main

import (
	"bufio"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

func run() error {
	host := flag.String("endpoint", "127.0.0.1", "Literal IP reachable by test client; no public/VPS endpoint")
	version := flag.String("version", "wg", "Exact owned wg, awg1, awg2, awg3 or awg3_1 fixture")
	output := flag.String("output", "", "Existing private output directory; writes client.conf with generated fixture keys")
	listenPort := flag.Int("listen-port", 0, "Optional fixed high UDP port for a narrowly authorized firewall rule")
	publicDNS := flag.String("public-dns", "none", "Explicit fixed test DNS relay: none, dot or doh; no arbitrary target")
	vlessFinal := flag.Bool("vless-final",false,"Exact independent final Steer TLS fixture, not a general proxy")
	flag.Parse()
	if *listenPort != 0 && (*listenPort < 1024 || *listenPort > 65535) {
		return fmt.Errorf("invalid fixture UDP port")
	}
	ip, err := netip.ParseAddr(*host)
	if err != nil || ip.IsUnspecified() || ip.IsMulticast() {
		return fmt.Errorf("invalid fixture endpoint")
	}
	if !ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
		return fmt.Errorf("fixture endpoint must be a local/private address")
	}
	if !filepath.IsAbs(*output) {
		return fmt.Errorf("absolute private output directory required")
	}
	info, err := os.Stat(*output)
	if err != nil || !info.IsDir() {
		return fmt.Errorf("output directory required")
	}
	if *vlessFinal {return runFinalVless(ip.String(),*output,*publicDNS)}
	params, conf, err := fixtureParameters(*version)
	if err!=nil{return err}
	serverKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	clientKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	tun, stack, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1"), netip.MustParseAddr("fd77::1")}, nil, 1280)
	if err != nil {
		return err
	}
	audit := &auditedTun{Device: tun}
	dev := newFixturePeerDevice(*version,audit)
	defer dev.Close()
	if err = dev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\n%sreplace_peers=true\npublic_key=%s\nallowed_ip=10.77.0.2/32\nallowed_ip=fd77::2/128\n", hex.EncodeToString(serverKey.Bytes()), *listenPort, params, hex.EncodeToString(clientKey.PublicKey().Bytes()))); err != nil {
		return err
	}
	if err = dev.Up(); err != nil {
		return err
	}
	state, err := dev.IpcGet()
	if err != nil {
		return err
	}
	port := ""
	for _, line := range strings.Split(state, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	if port == "" || port == "0" {
		return fmt.Errorf("fixture UDP listener unavailable")
	}
	nonce := make([]byte, 16)
	if _, err = rand.Read(nonce); err != nil {
		return err
	}
	expected := "mcrf-fixture-" + hex.EncodeToString(nonce)
	fixturePath := "/fixture/" + hex.EncodeToString(nonce)
	dns,err:=newManagedFixtureDNS(hex.EncodeToString(nonce),
		func(address *net.UDPAddr)(net.PacketConn,error){return stack.ListenUDP(address)},
		func(address *net.TCPAddr)(net.Listener,error){return stack.ListenTCP(address)})
	if err!=nil{return err};defer dns.Close()
	encrypted,err:=newFixtureTLS(func(address *net.TCPAddr)(net.Listener,error){return stack.ListenTCP(address)})
	if err!=nil{return err};defer encrypted.Close()
	relay,err:=newFixturePublicDNS(*publicDNS,func(address *net.TCPAddr)(net.Listener,error){return stack.ListenTCP(address)})
	if err!=nil{return err};defer relay.Close()
	var requests, bodyBytes atomic.Int64
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != fixturePath {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		w.Header().Set("Cache-Control", "no-store")
		n, _ := io.WriteString(w, expected)
		requests.Add(1)
		bodyBytes.Add(int64(n))
	}), ReadHeaderTimeout: 3 * time.Second}
	defer server.Close()
	listener, err := stack.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 77, 0, 1), Port: 8080})
	if err != nil {
		return err
	}
	go server.Serve(listener)
	listener6, err := stack.ListenTCP(&net.TCPAddr{IP: net.ParseIP("fd77::1"), Port: 8080})
	if err != nil {
		return err
	}
	go server.Serve(listener6)
	clientConf := fmt.Sprintf("[Interface]\nName = MCRF isolated %s fixture\nPrivateKey = %s\nAddress = 10.77.0.2/32, fd77::2/128\nDNS = 10.77.0.1\nMTU = 1280\n%s[Peer]\nPublicKey = %s\nEndpoint = %s\nAllowedIPs = 10.77.0.1/32, fd77::1/128\nPersistentKeepalive = 5\n", *version, base64.StdEncoding.EncodeToString(clientKey.Bytes()), conf, base64.StdEncoding.EncodeToString(serverKey.PublicKey().Bytes()), net.JoinHostPort(ip.String(), port))
	target := filepath.Join(*output, "client.conf")
	file, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	_, err = file.WriteString(clientConf)
	file.Close()
	if err != nil {
		return err
	}
	json.NewEncoder(os.Stdout).Encode(map[string]string{"event": "ready", "config_path": target, "url": "http://10.77.0.1:8080" + fixturePath, "url_ipv6": "http://[fd77::1]:8080" + fixturePath, "expected_body": expected, "version": *version, "note": "Local test fixture; not WARP/Internet/DNS/provider evidence. Expires in 5 minutes; STOP on stdin closes. Final receipt contains only handshake and byte counters, never private keys."})
	stopped := make(chan struct{})
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			if scanner.Text() == "STOP" {
				break
			}
		}
		close(stopped)
	}()
	select {
	case <-stopped:
	case <-time.After(5 * time.Minute):
	}
	server.Close()
	dns.Close()
	encrypted.Close()
	relay.Close()
	state, err = dev.IpcGet()
	if err != nil {
		return err
	}
	// Never emit the raw IPC state: it contains the generated fixture private key.
	counters := map[string]int64{"rx_bytes": 0, "tx_bytes": 0, "last_handshake_time_sec": 0}
	for _, line := range strings.Split(state, "\n") {
		parts := strings.SplitN(line, "=", 2)
		if len(parts) == 2 {
			if _, known := counters[parts[0]]; known {
				value, parseErr := strconv.ParseInt(parts[1], 10, 64)
				if parseErr != nil {
					return parseErr
				}
				counters[parts[0]] = value
			}
		}
	}
	dev.Close()
	json.NewEncoder(os.Stdout).Encode(map[string]any{"event": "closed", "version": *version, "handshake_observed": counters["last_handshake_time_sec"] > 0, "http_requests": requests.Load(), "http_body_bytes": bodyBytes.Load(), "dns_requests": dns.total(),"dns_udp4":dns.udp4.Load(),"dns_udp6":dns.udp6.Load(),"dns_tcp4":dns.tcp4.Load(),"dns_tcp6":dns.tcp6.Load(),"dns_refused":dns.refused.Load(),"dns_sockets_closed":true,"tls_sockets_closed":true,"tls_untrusted_accepted":encrypted.accepted.Load(),"tls_untrusted_rejected":encrypted.rejected.Load(),"tls_application_bytes":encrypted.applicationBytes.Load(),"public_dns_mode":relay.kind,"public_dns_connections":relay.opened.Load(),"public_dns_tx":relay.upBytes.Load(),"public_dns_rx":relay.downBytes.Load(),"public_dns_refused":relay.refused.Load(),"public_dns_sockets_closed":true,"internal_dns_packets":audit.internalDNS.Load(), "decrypted_packets": audit.decrypted.Load(), "blocked_packets": audit.blocked.Load(), "tunnel_counters": counters, "note": "Owned HTTP/DNS/untrusted TLS refusal and explicitly selected fixed DNS relay only, not general Internet/provider acceptance. No private key is included."})
	return nil
}
func main() {
	if run() != nil {
		fmt.Fprintln(os.Stderr, "fixture failed; no account or production configuration used")
		os.Exit(1)
	}
}
