// SPDX-License-Identifier: GPL-3.0-or-later
// Test-only loopback peer. Never stage this executable in a release module.
package main

import (
	"bufio"
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

const id = "4b72af9e-32b4-4b14-a2fc-4a9100d2eb21"
const payload = "mcrf-optional-device-fixture-payload"

var output sync.Mutex

func event(kind string, value any) {
	b, _ := json.Marshal(value)
	output.Lock()
	defer output.Unlock()
	fmt.Println(kind + " " + string(b))
}
func port() int {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		panic("loopback unavailable")
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
func certificate() (tls.Certificate, string) {
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		panic("fixture key unavailable")
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture.example.test"}, DNSNames: []string{"fixture.example.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if e != nil {
		panic("fixture certificate unavailable")
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	pair, e := tls.X509KeyPair(certPEM, keyPEM)
	if e != nil {
		panic("fixture certificate invalid")
	}
	return pair, string(certPEM)
}

type capture struct {
	net.Conn
	bytes           []byte
	first, helloEnd time.Time
}

func (c *capture) Read(p []byte) (int, error) {
	n, e := c.Conn.Read(p)
	if n > 0 && c.first.IsZero() {
		c.first = time.Now()
	}
	if len(c.bytes) < 65536 {
		c.bytes = append(c.bytes, p[:n]...)
	}
	if c.helloEnd.IsZero() {
		_, _, complete := records(c.bytes)
		if complete {
			c.helloEnd = time.Now()
		}
	}
	return n, e
}
func records(raw []byte) (int, int, bool) {
	count, max := 0, 0
	var hello []byte
	for len(raw) >= 5 {
		n := int(raw[3])<<8 | int(raw[4])
		if len(raw) < 5+n {
			break
		}
		if raw[0] == 22 {
			count++
			if n > max {
				max = n
			}
			hello = append(hello, raw[5:5+n]...)
		}
		raw = raw[5+n:]
		if len(hello) >= 4 && len(hello) >= 4+(int(hello[1])<<16|int(hello[2])<<8|int(hello[3])) {
			return count, max, true
		}
	}
	return count, max, false
}

func tlsPeer() (net.Listener, string) {
	pair, cert := certificate()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		panic("fixture bind failed")
	}
	go func() {
		for {
			n, e := l.Accept()
			if e != nil {
				return
			}
			go func() {
				defer n.Close()
				c := &capture{Conn: n}
				name := ""
				conn := tls.Server(c, &tls.Config{Certificates: []tls.Certificate{pair}, GetConfigForClient: func(h *tls.ClientHelloInfo) (*tls.Config, error) { name = h.ServerName; return nil, nil }})
				conn.SetDeadline(time.Now().Add(10 * time.Second))
				if conn.Handshake() != nil {
					return
				}
				count, max, complete := records(c.bytes)
				event("MCRF_FIXTURE_TLS", map[string]any{"sni": name, "records": count, "max_record": max, "hello_complete": complete, "hello_ms": c.helloEnd.Sub(c.first).Milliseconds()})
				r := bufio.NewReader(conn)
				line, e := r.ReadString('\n')
				if e != nil || !strings.HasPrefix(line, "CONNECT ") {
					return
				}
				for {
					line, e = r.ReadString('\n')
					if e != nil {
						return
					}
					if line == "\r\n" {
						break
					}
				}
				io.WriteString(conn, "HTTP/1.1 200 Connection established\r\n\r\n")
				for {
					line, e = r.ReadString('\n')
					if e != nil {
						return
					}
					if line == "\r\n" {
						break
					}
				}
				fmt.Fprintf(conn, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(payload), payload)
			}()
		}
	}()
	return l, cert
}

func run(kind string) error {
	if kind == "tls" {
		l, cert := tlsPeer()
		defer l.Close()
		event("MCRF_FIXTURE_READY", map[string]any{"kind": kind, "address": l.Addr().String(), "certificate": cert, "payload": payload})
		wait()
		return nil
	}
	if kind != "xray-raw" && kind != "xray-mux" && kind != "xray-xhttp" && kind != "xray-reality" && kind != "xray-vision" && kind != "xray-ws" && kind != "xray-grpc" && kind != "xray-httpupgrade" {
		return fmt.Errorf("unknown fixture")
	}
	target, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		return e
	}
	defer target.Close()
	udp, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		return e
	}
	defer udp.Close()
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, address, err := udp.ReadFromUDP(buffer)
			if err != nil {
				return
			}
			if address.IP.IsLoopback() {
				udp.WriteToUDP(buffer[:n], address)
			}
		}
	}()
	httpServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprint(len(payload)))
		io.WriteString(w, payload)
	})}
	defer httpServer.Close()
	go httpServer.Serve(target)
	p := port()
	network := "raw"
	if kind == "xray-xhttp" {
		network = "xhttp"
	}
	if kind == "xray-ws" || kind == "xray-grpc" || kind == "xray-httpupgrade" {
		network = strings.TrimPrefix(kind, "xray-")
	}
	serverStream := map[string]any{"network": network, "security": "none"}
	clientStream := map[string]any{"network": network, "security": "none"}
	user := map[string]any{"id": id}
	clientUser := map[string]any{"id": id, "encryption": "none"}
	if network == "xhttp" {
		serverStream["xhttpSettings"] = map[string]any{"path": "/mcrf-fixture", "mode": "auto"}
		clientStream["xhttpSettings"] = map[string]any{"path": "/mcrf-fixture", "mode": "packet-up"}
	}
	if network == "ws" || network == "httpupgrade" {
		field := "wsSettings"
		if network == "httpupgrade" {
			field = "httpupgradeSettings"
		}
		serverStream[field] = map[string]any{"path": "/mcrf-fixture"}
		clientStream[field] = map[string]any{"path": "/mcrf-fixture", "host": "fixture.example.test", "headers": map[string]string{"X-MCRF-Fixture": "owned-loopback"}}
	}
	if network == "grpc" {
		serverStream["grpcSettings"] = map[string]any{"serviceName": "mcrf-fixture"}
		clientStream["grpcSettings"] = map[string]any{"serviceName": "mcrf-fixture"}
	}
	if kind == "xray-reality" || kind == "xray-vision" {
		pair, _ := certificate()
		decoy, e := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{pair}, NextProtos: []string{"h2", "http/1.1"}})
		if e != nil {
			return e
		}
		defer decoy.Close()
		go func() {
			for {
				c, e := decoy.Accept()
				if e != nil {
					return
				}
				go func() { defer c.Close(); c.SetDeadline(time.Now().Add(3 * time.Second)); c.(*tls.Conn).Handshake() }()
			}
		}()
		key, e := ecdh.X25519().GenerateKey(rand.Reader)
		if e != nil {
			return e
		}
		serverStream["security"] = "reality"
		clientStream["security"] = "reality"
		serverStream["realitySettings"] = map[string]any{"dest": decoy.Addr().String(), "serverNames": []string{"fixture.example.test"}, "privateKey": base64.RawURLEncoding.EncodeToString(key.Bytes()), "shortIds": []string{"abcd"}}
		clientStream["realitySettings"] = map[string]any{"serverName": "fixture.example.test", "fingerprint": "chrome", "password": base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), "shortId": "abcd"}
		if kind == "xray-vision" {
			user["flow"] = "xtls-rprx-vision"
			clientUser["flow"] = "xtls-rprx-vision"
		}
	}
	config := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": p, "protocol": "vless", "settings": map[string]any{"clients": []any{user}, "decryption": "none"}, "streamSettings": serverStream}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1"}}}}}}}
	data, _ := json.Marshal(config)
	conf, e := serial.LoadJSONConfig(bytes.NewReader(data))
	if e != nil {
		return e
	}
	instance, e := core.New(conf)
	if e != nil {
		return e
	}
	defer instance.Close()
	if e = instance.Start(); e != nil {
		return e
	}
	out := map[string]any{"protocol": "vless", "settings": map[string]any{"vnext": []any{map[string]any{"address": "127.0.0.1", "port": p, "users": []any{clientUser}}}}, "streamSettings": clientStream}
	if kind == "xray-mux" {
		out["mux"] = map[string]any{"enabled": true, "concurrency": 8}
	}
	event("MCRF_FIXTURE_READY", map[string]any{"kind": kind, "outbound": out, "target": target.Addr().String(), "udp_target": udp.LocalAddr().String(), "payload": payload})
	wait()
	return nil
}
func wait() {
	s := bufio.NewScanner(io.LimitReader(os.Stdin, 8192))
	for s.Scan() {
		if s.Text() == "STOP" {
			return
		}
	}
}
func main() {
	if len(os.Args) != 2 {
		os.Exit(2)
	}
	if e := run(os.Args[1]); e != nil {
		fmt.Fprintln(os.Stderr, "fixture failed")
		os.Exit(1)
	}
}
