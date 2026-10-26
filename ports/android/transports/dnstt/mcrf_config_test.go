package main

import (
	"encoding/json"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func mcrfDnsttFixture() map[string]any {
	return map[string]any{"domain": "t.example.test", "key": strings.Repeat("ab", 32), "udp": "127.0.0.1:5353", "socks": "127.0.0.1:12345", "socks_user": "local", "socks_pass": "private"}
}
func mcrfDnsttParse(value map[string]any) (*mobileConfig, string, string, error) {
	data, _ := json.Marshal(value)
	return mobileReadConfig(strings.NewReader(string(data)))
}
func TestMcrfDnsttStrictPrivateConfig(t *testing.T) {
	value := mcrfDnsttFixture()
	c, flag, resolver, err := mcrfDnsttParse(value)
	if err != nil || c.Domain != "t.example.test" || flag != "-udp" || resolver != "127.0.0.1:5353" {
		t.Fatal("valid private config rejected")
	}
	for _, key := range []string{"type", "device_id", "probe_only", "log", "listen", "Domain", "KEY"} {
		value[key] = "untrusted"
		if _, _, _, err := mcrfDnsttParse(value); err == nil {
			t.Fatalf("unknown %s accepted", key)
		}
		delete(value, key)
	}
	data, _ := json.Marshal(value)
	for _, bad := range []string{string(data) + " {}", strings.TrimSuffix(string(data), "}") + `,"domain":"other.example.test"}`, `{"domain":null}`, strings.Repeat(" ", 65537)} {
		if _, _, _, err := mobileReadConfig(strings.NewReader(bad)); err == nil {
			t.Fatal("ambiguous/oversized config accepted")
		}
	}
}
func TestMcrfDnsttRejectsUnsafeEndpoints(t *testing.T) {
	for key, values := range map[string][]string{
		"socks":  {"0.0.0.0:12345", "127.0.0.1:0", "127.0.0.1:65536"},
		"domain": {"", "-bad.example.test", "bad..example.test", "example.test.", "a/../../example"},
		"udp":    {"resolver.example:53", "127.0.0.1:0", "127.0.0.1:65536"},
		"key":    {"ab", strings.Repeat("zz", 32)},
	} {
		for _, bad := range values {
			value := mcrfDnsttFixture()
			value[key] = bad
			if _, _, _, err := mcrfDnsttParse(value); err == nil {
				t.Fatalf("unsafe %s accepted", key)
			}
		}
	}
	for _, doh := range []string{"http://dns.example.test/query", "https://u:p@dns.example.test/query", "https://dns.example.test/query#secret", "https://dns.example.test:0/query"} {
		value := mcrfDnsttFixture()
		delete(value, "udp")
		value["doh"] = doh
		if _, _, _, err := mcrfDnsttParse(value); err == nil {
			t.Fatal("unsafe DoH accepted")
		}
	}
}
func TestMcrfDnsttPreservesResolverAndRemoteCredentials(t *testing.T) {
	for key, resolver := range map[string]string{"udp": "[::1]:5353", "dot": "dns.example.test:853", "doh": "https://dns.example.test:443/dns-query"} {
		value := mcrfDnsttFixture()
		delete(value, "udp")
		value[key] = resolver
		value["username"] = "remote + user"
		value["password"] = "private + secret"
		c, flag, actual, err := mcrfDnsttParse(value)
		if err != nil || flag != "-"+key || actual != resolver || c.RemoteUser != "remote + user" || c.RemotePass != "private + secret" {
			t.Fatal("wire configuration changed")
		}
		value["password"] = ""
		if _, _, _, err := mcrfDnsttParse(value); err == nil {
			t.Fatal("unpaired credentials accepted")
		}
	}
}
func TestMcrfDnsttLocalAuthRejectsUnauthenticatedClient(t *testing.T) {
	old := mobile
	mobile = &mobileConfig{User: "local", Pass: "private"}
	defer func() { mobile = old }()
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(2 * time.Second))
	done := make(chan error, 1)
	go func() { done <- mobileAuth(server) }()
	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2)
	if _, err := io.ReadFull(client, reply); err != nil || reply[0] != 5 || reply[1] != 255 {
		t.Fatal("unauthenticated greeting not rejected")
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("unauthenticated client accepted")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("authentication leaked worker")
	}
}
func TestMcrfDnsttRemoteAuthPreservesProtocolBoundary(t *testing.T) {
	old := mobile
	mobile = &mobileConfig{RemoteUser: "user", RemotePass: "pass"}
	defer func() { mobile = old }()
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(2 * time.Second))
	server.SetDeadline(time.Now().Add(2 * time.Second))
	done := make(chan error, 1)
	go func() { done <- mobileRemote(client) }()
	greeting := make([]byte, 3)
	if _, err := io.ReadFull(server, greeting); err != nil || string(greeting) != string([]byte{5, 1, 2}) {
		t.Fatal("remote method changed")
	}
	if _, err := server.Write([]byte{5, 2}); err != nil {
		t.Fatal(err)
	}
	auth := make([]byte, 11)
	if _, err := io.ReadFull(server, auth); err != nil || string(auth) != string([]byte{1, 4, 'u', 's', 'e', 'r', 4, 'p', 'a', 's', 's'}) {
		t.Fatal("remote RFC1929 changed")
	}
	if _, err := server.Write([]byte{1, 0}); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("remote auth stalled")
	}
}
