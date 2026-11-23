// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"io"
	"log"
	bridge "mcrf.local/optionalbridge"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const fixtureID = "4b72af9e-32b4-4b14-a2fc-4a9100d2eb21"

func freePort(t *testing.T) int {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
func outbound(port int, stream map[string]any, mux bool) json.RawMessage {
	m := map[string]any{"protocol": "vless", "settings": map[string]any{"vnext": []any{map[string]any{"address": "127.0.0.1", "port": port, "users": []any{map[string]any{"id": fixtureID, "encryption": "none"}}}}}, "streamSettings": stream}
	if mux {
		m["mux"] = map[string]any{"enabled": true, "concurrency": 8}
	}
	raw, _ := json.Marshal(m)
	return raw
}
func request(t *testing.T, c bridge.Config, dest string) {
	t.Helper()
	n, e := net.DialTimeout("tcp", c.SOCKS, time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer n.Close()
	n.SetDeadline(time.Now().Add(10 * time.Second))
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
		t.Fatal("auth")
	}
	_, p, _ := net.SplitHostPort(dest)
	var port int
	fmt.Sscan(p, &port)
	n.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	reply := make([]byte, 10)
	if _, e = io.ReadFull(n, reply); e != nil || reply[1] != 0 {
		t.Fatal("CONNECT")
	}
	fmt.Fprintf(n, "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", dest)
	response, e := http.ReadResponse(bufio.NewReader(n), nil)
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	data, e := io.ReadAll(response.Body)
	if e != nil {
		t.Fatal(e)
	}
	if string(data) != strings.Repeat("vless-real-traffic-", 512) {
		t.Fatal("traffic payload mismatch")
	}
}

func udpRequest(t *testing.T, c bridge.Config) {
	t.Helper()
	exit, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	defer exit.Close()
	go func() {
		b := make([]byte, 4096)
		for {
			n, from, e := exit.ReadFromUDP(b)
			if e != nil {
				return
			}
			exit.WriteToUDP(b[:n], from)
		}
	}()
	local, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	defer local.Close()
	openControl := func() net.Conn {
		control, err := net.DialTimeout("tcp", c.SOCKS, time.Second)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { control.Close() })
		control.SetDeadline(time.Now().Add(3 * time.Second))
		control.Write([]byte{5, 1, 2})
		var authReply [2]byte
		if _, err = io.ReadFull(control, authReply[:]); err != nil || authReply != [2]byte{5, 2} {
			t.Fatal("UDP control requires password authentication")
		}
		credentials := append([]byte{1, byte(len(c.User))}, []byte(c.User)...)
		credentials = append(credentials, byte(len(c.Password)))
		credentials = append(credentials, []byte(c.Password)...)
		control.Write(credentials)
		if _, err = io.ReadFull(control, authReply[:]); err != nil || authReply != [2]byte{1, 0} {
			t.Fatal("UDP control authentication failed")
		}
		return control
	}
	unbound := openControl()
	unbound.Write([]byte{5, 3, 0, 1, 127, 0, 0, 1, 0, 0})
	var denied [10]byte
	if _, e = io.ReadFull(unbound, denied[:]); e != nil || denied[1] != 2 {
		t.Fatal("UDP source port zero was not refused")
	}
	unbound.Close()
	control := openControl()
	p := local.LocalAddr().(*net.UDPAddr).Port
	control.Write([]byte{5, 3, 0, 1, 127, 0, 0, 1, byte(p >> 8), byte(p)})
	reply := make([]byte, 10)
	if _, e = io.ReadFull(control, reply); e != nil || reply[1] != 0 || reply[3] != 1 {
		t.Fatal("UDP ASSOCIATE failed")
	}
	relay := &net.UDPAddr{IP: net.IP(reply[4:8]), Port: int(reply[8])<<8 | int(reply[9])}
	target := exit.LocalAddr().(*net.UDPAddr).Port
	payload := []byte(strings.Repeat("mcrf-udp-", 128))
	packet := append([]byte{0, 0, 0, 1, 127, 0, 0, 1, byte(target >> 8), byte(target)}, payload...)
	local.SetReadDeadline(time.Now().Add(3 * time.Second))
	local.WriteToUDP(packet, relay)
	response := make([]byte, 4096)
	n, _, e := local.ReadFromUDP(response)
	if e != nil || n < 10 || !bytes.Equal(response[10:n], payload) {
		t.Fatal("UDP round trip payload mismatch")
	}
	// Different local application/source port must not hijack an association.
	foreign, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	defer foreign.Close()
	foreign.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	foreign.WriteToUDP(packet, relay)
	if _, _, e = foreign.ReadFromUDP(response); e == nil {
		t.Fatal("unauthenticated foreign UDP port accepted")
	}
	local.SetReadDeadline(time.Now().Add(2 * time.Second))
	local.WriteToUDP(packet, relay)
	if n, _, e = local.ReadFromUDP(response); e != nil || n < 10 || !bytes.Equal(response[10:n], payload) {
		t.Fatal("wrong source retired the valid UDP association")
	}
	control.Close()
	time.Sleep(100 * time.Millisecond)
	local.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	local.WriteToUDP(packet, relay)
	if _, _, e = local.ReadFromUDP(response); e == nil {
		t.Fatal("UDP association survives authenticated control close")
	}
}

func TestTrafficAndStop(t *testing.T) {
	for _, mode := range []string{"raw", "raw-mux", "xhttp", "reality", "vision-reality"} {
		t.Run(mode, func(t *testing.T) {
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				io.WriteString(w, strings.Repeat("vless-real-traffic-", 512))
			}))
			defer target.Close()
			dest := strings.TrimPrefix(target.URL, "http://")
			network := "raw"
			if mode == "xhttp" {
				network = "xhttp"
			}
			serverStream := map[string]any{"network": network, "security": "none"}
			clientStream := map[string]any{"network": network, "security": "none"}
			if network == "xhttp" {
				serverStream["xhttpSettings"] = map[string]any{"path": "/mcrf-fixture", "mode": "auto"}
				clientStream["xhttpSettings"] = map[string]any{"path": "/mcrf-fixture", "mode": "packet-up", "extra": map[string]any{"xmux": map[string]any{"maxConcurrency": "4-4", "hKeepAlivePeriod": 15}}}
			}
			if strings.Contains(mode, "reality") {
				decoy := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "fixture") }))
				decoy.Config.ErrorLog = log.New(io.Discard, "", 0)
				decoy.EnableHTTP2 = true
				decoy.StartTLS()
				defer decoy.Close()
				key, e := ecdh.X25519().GenerateKey(rand.Reader)
				if e != nil {
					t.Fatal(e)
				}
				serverStream["security"] = "reality"
				clientStream["security"] = "reality"
				serverStream["realitySettings"] = map[string]any{"dest": strings.TrimPrefix(decoy.URL, "https://"), "serverNames": []string{"fixture.example.test"}, "privateKey": base64.RawURLEncoding.EncodeToString(key.Bytes()), "shortIds": []string{"abcd"}}
				clientStream["realitySettings"] = map[string]any{"serverName": "fixture.example.test", "fingerprint": "chrome", "password": base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), "shortId": "abcd", "spiderX": "/"}
			}
			port := freePort(t)
			user := map[string]any{"id": fixtureID}
			if mode == "vision-reality" {
				user["flow"] = "xtls-rprx-vision"
			}
			serverConfig := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "vless", "settings": map[string]any{"clients": []any{user}, "decryption": "none"}, "streamSettings": serverStream}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1"}}}}}}}
			data, _ := json.Marshal(serverConfig)
			conf, e := serial.LoadJSONConfig(bytes.NewReader(data))
			if e != nil {
				t.Fatal(e)
			}
			server, e := core.New(conf)
			if e != nil {
				t.Fatal(e)
			}
			defer server.Close()
			if e = server.Start(); e != nil {
				t.Fatal(e)
			}
			out := outbound(port, clientStream, mode == "raw-mux")
			if mode == "vision-reality" {
				var m map[string]any
				json.Unmarshal(out, &m)
				m["settings"].(map[string]any)["vnext"].([]any)[0].(map[string]any)["users"].([]any)[0].(map[string]any)["flow"] = "xtls-rprx-vision"
				out, _ = json.Marshal(m)
			}
			c := bridge.Config{API: 1, SOCKS: fmt.Sprintf("127.0.0.1:%d", freePort(t)), User: "fixture-user-123456", Password: "fixture-password-123456", Outbound: out}
			data, _ = json.Marshal(c)
			file := filepath.Join(t.TempDir(), "config.json")
			os.WriteFile(file, data, 0600)
			exe, _ := filepath.Abs("../../optional-cores/build/xray.exe")
			cmd := exec.Command(exe, "--mcrf-config", file)
			stdin, _ := cmd.StdinPipe()
			output, _ := cmd.StdoutPipe()
			cmd.Stderr = io.Discard
			if e = cmd.Start(); e != nil {
				t.Fatal(e)
			}
			t.Cleanup(func() { stdin.Close(); cmd.Process.Kill(); cmd.Wait() })
			ready := make(chan string, 1)
			go func() { line, _ := bufio.NewReader(output).ReadString('\n'); ready <- line }()
			select {
			case line := <-ready:
				if strings.TrimSpace(line) != "MCRF_READY" {
					t.Fatal("readiness")
				}
			case <-time.After(8 * time.Second):
				t.Fatal("start timeout")
			}
			if !bridge.Ready(c) {
				t.Fatal("anonymous rejection/auth failed")
			}
			bad := c
			bad.Password = "wrong-fixture-password"
			if bridge.Ready(bad) {
				t.Fatal("wrong password accepted")
			}
			request(t, c, dest)
			udpRequest(t, c)
			io.WriteString(stdin, "STOP\n")
			done := make(chan error, 1)
			go func() { done <- cmd.Wait() }()
			select {
			case e = <-done:
				if e != nil {
					t.Fatal("stop exit")
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

func TestInvalid(t *testing.T) {
	for _, test := range []struct {
		name string
		edit func(map[string]any)
	}{
		{"unknown-root", func(m map[string]any) { m["unknown"] = true }},
		{"ignored-stream-field", func(m map[string]any) { m["streamSettings"].(map[string]any)["tls_tricks"] = true }},
		{"invalid-id", func(m map[string]any) {
			m["settings"].(map[string]any)["vnext"].([]any)[0].(map[string]any)["users"].([]any)[0].(map[string]any)["id"] = strings.Repeat("invalid", 10)
		}},
		{"invalid-xhttp-mode", func(m map[string]any) {
			m["streamSettings"] = map[string]any{"network": "xhttp", "xhttpSettings": map[string]any{"mode": "invalid"}}
		}},
		{"ignored-xhttp-extra", func(m map[string]any) {
			m["streamSettings"] = map[string]any{"network": "xhttp", "xhttpSettings": map[string]any{"extra": map[string]any{"unknownSetting": true}}}
		}},
		{"mux-zero", func(m map[string]any) { m["mux"] = map[string]any{"enabled": true, "concurrency": 0} }},
		{"file-log", func(m map[string]any) {
			m["streamSettings"] = map[string]any{"network": "raw", "security": "tls", "tlsSettings": map[string]any{"masterKeyLog": "secret"}}
		}},
		{"socket-option", func(m map[string]any) {
			m["streamSettings"].(map[string]any)["sockopt"] = map[string]any{"interface": "eth0"}
		}},
		{"invalid-reality-key", func(m map[string]any) {
			m["streamSettings"] = map[string]any{"network": "raw", "security": "reality", "realitySettings": map[string]any{"fingerprint": "chrome", "password": "bad"}}
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			var m map[string]any
			json.Unmarshal(outbound(443, map[string]any{"network": "raw"}, false), &m)
			test.edit(m)
			raw, _ := json.Marshal(m)
			c := bridge.Config{API: 1, SOCKS: "127.0.0.1:19999", User: "fixture-user-123456", Password: "fixture-password-123456", Outbound: raw}
			core, e := create(context.Background(), c)
			if e == nil {
				core.Close()
				t.Fatal("invalid config accepted")
			}
		})
	}
}
