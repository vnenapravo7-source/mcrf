// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	cronet "github.com/sagernet/cronet-go"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/crypto/ssh"
)

func fixtureConfig(kind string) map[string]any {
	result := map[string]any{"api": 1, "socks": "127.0.0.1:44011", "socks_user": strings.Repeat("a", 32), "socks_pass": strings.Repeat("b", 32), "outbound": map[string]any{"type": kind, "server": "127.0.0.1", "server_port": 443}}
	if runtime.GOOS == "android" {
		if handle, e := strconv.ParseUint(os.Getenv("MCRF_PROTOCOLS_FIXTURE_NETWORK_HANDLE"), 10, 64); e == nil && handle > 0 {
			result["physical_network_handle"] = handle
		}
	}
	return result
}
func parseFixture(c map[string]any) error {
	b, _ := json.Marshal(c)
	_, e := readConfig(bytes.NewReader(b))
	return e
}
func TestMcrfProtocolsStrictConfig(t *testing.T) {
	c := fixtureConfig("tuic")
	b, _ := json.Marshal(c)
	if _, e := readConfig(bytes.NewReader(b)); e != nil {
		t.Fatal(e)
	}
	for _, raw := range []string{string(b) + " {}", strings.Replace(string(b), `"api":1`, `"API":1`, 1), strings.Replace(string(b), `"api":1`, `"api":1,"api":1`, 1), strings.Replace(string(b), `"outbound":`, `"other":null,"outbound":`, 1)} {
		if _, e := readConfig(strings.NewReader(raw)); e == nil {
			t.Fatal("ambiguous/private schema accepted")
		}
	}
	c["outbound"].(map[string]any)["tls"] = map[string]any{"certificate_path": "secret"}
	if parseFixture(c) == nil {
		t.Fatal("filesystem injection")
	}
}
func TestMcrfProtocolsSshHostTrust(t *testing.T) {
	c := fixtureConfig("ssh")
	if !errors.Is(parseFixture(c), errTrust) {
		t.Fatal("missing SSH host pin accepted")
	}
	key, _, _ := ed25519.GenerateKey(rand.Reader)
	pub, _ := ssh.NewPublicKey(key)
	c["outbound"].(map[string]any)["host_key"] = strings.TrimSpace(string(ssh.MarshalAuthorizedKey(pub)))
	if e := parseFixture(c); e != nil {
		t.Fatal(e)
	}
	c["outbound"].(map[string]any)["host_key"] = "SHA256:not-an-OpenSSH-key"
	if !errors.Is(parseFixture(c), errTrust) {
		t.Fatal("fingerprint is not an authorized public key")
	}
}
func TestMcrfProtocolsDnsNotSilentlyDropped(t *testing.T) {
	c := fixtureConfig("anytls")
	c["outbound"].(map[string]any)["server"] = "unit.invalid"
	if parseFixture(c) == nil {
		t.Fatal("implicit system fallback")
	}
	c["server_dns"] = map[string]any{"endpoint": map[string]any{"type": "udp", "server": "192.0.2.53"}, "strategy": "ipv4_only"}
	if e := parseFixture(c); e != nil {
		t.Fatal(e)
	}
	c["server_dns"].(map[string]any)["endpoint"].(map[string]any)["detour"] = "other"
	if parseFixture(c) == nil {
		t.Fatal("DNS outbound escape")
	}
}
func TestMcrfProtocolsAddressFraming(t *testing.T) {
	for _, a := range []M.Socksaddr{M.ParseSocksaddrHostPort("192.0.2.5", 443), M.ParseSocksaddrHostPort("2001:db8::5", 443), M.ParseSocksaddrHostPort("unit.invalid", 443)} {
		p, e := appendAddress(nil, a)
		if e != nil {
			t.Fatal(e)
		}
		for n := 0; n < len(p); n++ {
			if _, e = readAddress(bytes.NewReader(p[:n])); e == nil {
				t.Fatal("short address accepted")
			}
		}
		got, e := readAddress(bytes.NewReader(p))
		if e != nil || got != a {
			t.Fatalf("address mismatch: %v", e)
		}
	}
}
func TestMcrfProtocolsAnonymousAndBadPasswordRejected(t *testing.T) {
	for _, method := range []byte{0, 2} {
		server, client := net.Pipe()
		done := make(chan error, 1)
		go func() {
			defer server.Close()
			done <- authenticate(server, strings.Repeat("a", 32), strings.Repeat("b", 32))
		}()
		client.SetDeadline(time.Now().Add(time.Second))
		writeAll(client, []byte{5, 1, method})
		var reply [2]byte
		io.ReadFull(client, reply[:])
		if method == 0 {
			if reply != [2]byte{5, 255} {
				t.Fatal("anonymous")
			}
		} else {
			writeAll(client, []byte{1, 1, 'x', 1, 'y'})
			io.ReadFull(client, reply[:])
			if reply != [2]byte{1, 1} {
				t.Fatal("bad password")
			}
		}
		client.Close()
		if <-done == nil {
			t.Fatal("authentication accepted")
		}
	}
}
func TestMcrfProtocolsOwnerEofAndStop(t *testing.T) {
	for _, command := range []string{"", "STOP\n", "NETWORK_CHANGED\n"} {
		done := make(chan error, 1)
		go func() {
			done <- own(context.Background(), strings.NewReader(command), func(ctx context.Context) error { <-ctx.Done(); return ctx.Err() })
		}()
		select {
		case e := <-done:
			if e != nil && !errors.Is(e, context.Canceled) {
				t.Fatal(e)
			}
		case <-time.After(time.Second):
			t.Fatal("owner did not cancel")
		}
	}
}
func TestMcrfProtocolsDiagnosticPrivacy(t *testing.T) {
	if failureCode(cronet.NetErrorCertAuthorityInvalid) != "PROTOCOL_CRONET_202" {
		t.Fatal("actual Cronet certificate code lost")
	}
	if failureCode(&cronet.ErrorGo{InternalErrorCode: -338, Message: "private token response body"}) != "PROTOCOL_CRONET_338" {
		t.Fatal("Cronet typed error code lost")
	}
	secret := "password=private host-key server unit.invalid"
	if failureCode(errors.New(secret)) != "PROTOCOL_FAILURE_UNCLASSIFIED" {
		t.Fatal("guessed reason")
	}
	var b bytes.Buffer
	d := &diagnostics{output: &b}
	d.report(errors.New(secret))
	d.report(errors.New(secret))
	if strings.Contains(b.String(), secret) || strings.Count(b.String(), "MCRF_ERROR") != 1 {
		t.Fatal("unsafe or excessive diagnostics")
	}
}
