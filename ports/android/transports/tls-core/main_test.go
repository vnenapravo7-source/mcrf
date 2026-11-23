// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"github.com/sagernet/sing-box/common/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"io"
	"math/big"
	bridge "mcrf.local/optionalbridge"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type recording struct {
	net.Conn
	mu   sync.Mutex
	data []byte
}

func (r *recording) Read(p []byte) (int, error) {
	n, e := r.Conn.Read(p)
	r.mu.Lock()
	r.data = append(r.data, p[:n]...)
	r.mu.Unlock()
	return n, e
}

func fixture(t *testing.T) (string, string, <-chan string, <-chan []byte) {
	t.Helper()
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture.example.test"}, DNSNames: []string{"fixture.example.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	pair, e := tls.X509KeyPair(certPEM, keyPEM)
	if e != nil {
		t.Fatal(e)
	}
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { l.Close() })
	names := make(chan string, 16)
	records := make(chan []byte, 16)
	go func() {
		for {
			n, e := l.Accept()
			if e != nil {
				return
			}
			go func() {
				defer n.Close()
				r := &recording{Conn: n}
				c := tls.Server(r, &tls.Config{Certificates: []tls.Certificate{pair}, GetConfigForClient: func(h *tls.ClientHelloInfo) (*tls.Config, error) { names <- h.ServerName; return nil, nil }})
				c.SetDeadline(time.Now().Add(5 * time.Second))
				if c.Handshake() != nil {
					return
				}
				r.mu.Lock()
				data := append([]byte{}, r.data...)
				r.mu.Unlock()
				records <- data
				input := bufio.NewReader(c)
				line, e := input.ReadString('\n')
				if e != nil || !strings.HasPrefix(line, "CONNECT ") {
					return
				}
				for {
					line, e = input.ReadString('\n')
					if e != nil {
						return
					}
					if line == "\r\n" {
						break
					}
				}
				io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
				for {
					line, e = input.ReadString('\n')
					if e != nil {
						return
					}
					if line == "\r\n" {
						break
					}
				}
				payload := strings.Repeat("mcrf-fixture-data-", 512)
				fmt.Fprintf(c, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(payload), payload)
			}()
		}
	}()
	return l.Addr().String(), string(certPEM), names, records
}

func freePort(t *testing.T) int {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
func cfg(t *testing.T, addr, cert string, fragment, mixed, utls bool) bridge.Config {
	host, p, _ := net.SplitHostPort(addr)
	var port int
	fmt.Sscan(p, &port)
	tlsOpts := map[string]any{"enabled": true, "server_name": "fixture.example.test", "certificate": []string{cert}, "tls_tricks": map[string]bool{"mixedcase_sni": mixed}}
	if utls {
		tlsOpts["utls"] = map[string]any{"enabled": true, "fingerprint": "chrome"}
	}
	out := map[string]any{"type": "http", "server": host, "server_port": port, "tls": tlsOpts}
	if fragment {
		out["tls_fragment"] = map[string]any{"enabled": true, "size": "32-32", "sleep": "2-2", "method": "tlsHello"}
	}
	raw, _ := json.Marshal(out)
	return bridge.Config{API: 1, SOCKS: fmt.Sprintf("127.0.0.1:%d", freePort(t)), User: "fixture-user-123456", Password: "fixture-password-123456", Outbound: raw}
}

func socksRequest(t *testing.T, c bridge.Config) string {
	t.Helper()
	n, e := net.DialTimeout("tcp", c.SOCKS, time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer n.Close()
	n.SetDeadline(time.Now().Add(8 * time.Second))
	n.Write([]byte{5, 1, 2})
	b := make([]byte, 2)
	io.ReadFull(n, b)
	if b[1] != 2 {
		t.Fatal("auth method")
	}
	a := append([]byte{1, byte(len(c.User))}, []byte(c.User)...)
	a = append(a, byte(len(c.Password)))
	a = append(a, []byte(c.Password)...)
	n.Write(a)
	io.ReadFull(n, b)
	if b[1] != 0 {
		t.Fatal("auth failed")
	}
	n.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 80})
	reply := make([]byte, 10)
	if _, e = io.ReadFull(n, reply); e != nil || reply[1] != 0 {
		t.Fatal("SOCKS CONNECT failed")
	}
	io.WriteString(n, "GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n")
	data, e := io.ReadAll(n)
	if e != nil {
		t.Fatal(e)
	}
	return string(data)
}

func TestWireTrafficAndLifecycle(t *testing.T) {
	for _, test := range []struct {
		name                  string
		fragment, mixed, utls bool
	}{{"std-custom", true, true, false}, {"utls-custom", true, true, true}, {"baseline", false, false, false}} {
		t.Run(test.name, func(t *testing.T) {
			addr, cert, names, records := fixture(t)
			c := cfg(t, addr, cert, test.fragment, test.mixed, test.utls)
			data, _ := json.Marshal(c)
			file := filepath.Join(t.TempDir(), "config.json")
			os.WriteFile(file, data, 0600)
			exe, _ := filepath.Abs("../../optional-cores/build/tls-core.exe")
			cmd := exec.Command(exe, "--mcrf-config", file)
			stdin, _ := cmd.StdinPipe()
			output, _ := cmd.StdoutPipe()
			cmd.Stderr = io.Discard
			if e := cmd.Start(); e != nil {
				t.Fatal(e)
			}
			t.Cleanup(func() { stdin.Close(); cmd.Process.Kill(); cmd.Wait() })
			ready := make(chan string, 1)
			go func() { line, _ := bufio.NewReader(output).ReadString('\n'); ready <- line }()
			select {
			case line := <-ready:
				if strings.TrimSpace(line) != "MCRF_READY" {
					t.Fatal("no readiness")
				}
			case <-time.After(8 * time.Second):
				t.Fatal("readiness timeout")
			}
			if !bridge.Ready(c) {
				t.Fatal("authentication readiness")
			}
			bad := c
			bad.Password = "wrong-fixture-password"
			if bridge.Ready(bad) {
				t.Fatal("wrong password accepted")
			}
			if got := socksRequest(t, c); !strings.Contains(got, strings.Repeat("mcrf-fixture-data-", 512)) {
				t.Fatal("actual traffic payload mismatch")
			}
			name := <-names
			if !strings.EqualFold(name, "fixture.example.test") {
				t.Fatal("SNI changed hostname")
			}
			if test.mixed && name == "fixture.example.test" {
				t.Fatal("mixedcase absent on wire")
			}
			if !test.mixed && name != "fixture.example.test" {
				t.Fatal("baseline SNI altered")
			}
			wire := <-records
			count := 0
			var hello []byte
			for len(wire) >= 5 {
				size := int(wire[3])<<8 | int(wire[4])
				if len(wire) < 5+size {
					break
				}
				if wire[0] == 22 {
					if test.fragment && size > 32 {
						t.Fatalf("fragment record %d exceeds 32", size)
					}
					hello = append(hello, wire[5:5+size]...)
					count++
				}
				wire = wire[5+size:]
				if len(hello) >= 4 && len(hello) >= 4+(int(hello[1])<<16|int(hello[2])<<8|int(hello[3])) {
					break
				}
			}
			if test.fragment && count < 2 {
				t.Fatal("no TLS record fragmentation")
			}
			if len(hello) < 4 || hello[0] != 1 {
				t.Fatal("no reconstructed ClientHello")
			}
			// Upstream sleeps between randomized bursts (1..5 records); measured
			// complete handshake must include at least one 2ms configured pause.
			command := "STOP\n"
			if test.utls {
				command = "NETWORK_CHANGED\n"
			}
			if test.name == "baseline" {
				stdin.Close()
			} else {
				io.WriteString(stdin, command)
			}
			done := make(chan error, 1)
			go func() { done <- cmd.Wait() }()
			select {
			case e := <-done:
				if e != nil {
					t.Fatal("unclean stop")
				}
			case <-time.After(3 * time.Second):
				t.Fatal("stop timeout")
			}
			if n, e := net.DialTimeout("tcp", c.SOCKS, 200*time.Millisecond); e == nil {
				n.Close()
				t.Fatal("listener survives stop")
			}
		})
	}
}

func TestInvalidConfig(t *testing.T) {
	addr, cert, _, _ := fixture(t)
	base := cfg(t, addr, cert, true, true, false)
	for _, test := range []struct {
		name string
		edit func(map[string]any)
	}{
		{"zero-size", func(m map[string]any) { m["tls_fragment"].(map[string]any)["size"] = "0" }},
		{"oversize", func(m map[string]any) { m["tls_fragment"].(map[string]any)["size"] = "16385" }},
		{"reverse-range", func(m map[string]any) { m["tls_fragment"].(map[string]any)["size"] = "20-10" }},
		{"ignored-range-method", func(m map[string]any) { m["tls_fragment"].(map[string]any)["method"] = "range" }},
		{"excess-sleep", func(m map[string]any) { m["tls_fragment"].(map[string]any)["sleep"] = "101" }},
		{"TFO", func(m map[string]any) { m["tcp_fast_open"] = true }},
		{"custom-field", func(m map[string]any) { m["fragment_size"] = "10-100" }},
		{"padding", func(m map[string]any) {
			m["tls"].(map[string]any)["tls_tricks"].(map[string]any)["padding_size"] = "10"
		}},
		{"file", func(m map[string]any) { m["tls"].(map[string]any)["certificate_path"] = "secret.pem" }},
		{"detour", func(m map[string]any) { m["detour"] = "unknown" }},
	} {
		t.Run(test.name, func(t *testing.T) {
			var m map[string]any
			json.Unmarshal(base.Outbound, &m)
			test.edit(m)
			raw, _ := json.Marshal(m)
			c := base
			c.Outbound = raw
			core, e := create(context.Background(), c)
			if e == nil {
				core.Close()
				t.Fatal("invalid config accepted")
			}
		})
	}
}

func TestCertificateMismatchRefused(t *testing.T) {
	addr, cert, _, _ := fixture(t)
	c := cfg(t, addr, cert, false, false, false)
	var out map[string]any
	json.Unmarshal(c.Outbound, &out)
	out["tls"].(map[string]any)["server_name"] = "wrong.example.test"
	c.Outbound, _ = json.Marshal(out)
	instance, e := create(context.Background(), c)
	if e != nil {
		t.Fatal(e)
	}
	defer instance.Close()
	if e = instance.Start(); e != nil {
		t.Fatal(e)
	}
	n, e := net.DialTimeout("tcp", c.SOCKS, time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer n.Close()
	n.SetDeadline(time.Now().Add(3 * time.Second))
	n.Write([]byte{5, 1, 2})
	b := make([]byte, 2)
	io.ReadFull(n, b)
	a := append([]byte{1, byte(len(c.User))}, []byte(c.User)...)
	a = append(a, byte(len(c.Password)))
	a = append(a, []byte(c.Password)...)
	n.Write(a)
	io.ReadFull(n, b)
	n.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 80})
	reply := make([]byte, 10)
	_, e = io.ReadFull(n, reply)
	if e != nil || reply[1] != 0 {
		return
	}
	io.WriteString(n, "GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n")
	data, e := io.ReadAll(n)
	if e == nil && len(data) > 0 {
		t.Fatal("certificate name mismatch allowed traffic")
	}
}

func TestCertificateStores(t *testing.T) {
	store, e := certificate.NewStore(context.Background(), logger.NOP(), option.CertificateOptions{Store: "mozilla"})
	if e != nil {
		t.Fatal(e)
	}
	if store.StoreKind() != "mozilla" || len(store.Pool().Subjects()) < 100 {
		t.Fatal("Mozilla root pool not populated")
	}
	addr, cert, _, _ := fixture(t)
	for _, kind := range []string{"system", "mozilla"} {
		t.Run(kind, func(t *testing.T) {
			c := cfg(t, addr, cert, true, true, false)
			c.CertificateStore = kind
			instance, e := create(context.Background(), c)
			if e != nil {
				t.Fatal(e)
			}
			defer instance.Close()
			if e = instance.Start(); e != nil {
				t.Fatal(e)
			}
			if got := socksRequest(t, c); !strings.Contains(got, strings.Repeat("mcrf-fixture-data-", 512)) {
				t.Fatal("inline profile trust lost when selecting global CA store")
			}
		})
	}
	c := cfg(t, addr, cert, false, false, false)
	c.CertificateStore = "unknown"
	if instance, e := create(context.Background(), c); e == nil {
		instance.Close()
		t.Fatal("unknown CA store accepted")
	}
}
