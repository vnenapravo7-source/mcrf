package main

import (
	"crypto/x509"
	"encoding/json"
	"strings"
	"testing"
)

func sstpConfig() map[string]any {
	return map[string]any{"server": "vpn.example.test", "username": "fixture-user", "password": "fixture-password", "socks": "127.0.0.1:12345", "socks_user": "local", "socks_pass": "local-private"}
}
func configJSON(t *testing.T, value map[string]any) string {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestMcrfSstpStrictConfigAndSecureDefaults(t *testing.T) {
	value := sstpConfig()
	p, err := readConfig(strings.NewReader(configJSON(t, value)))
	if err != nil {
		t.Fatal(err)
	}
	if p.ServerPort != 443 {
		t.Fatal("missing HTTPS default")
	}
	for _, key := range []string{"allow_insecure", "insecure", "tls_mode", "device_id", "probe_only"} {
		value[key] = true
		if _, err = readConfig(strings.NewReader(configJSON(t, value))); err == nil {
			t.Fatalf("unsafe field accepted: %s", key)
		}
		delete(value, key)
	}
	value["socks_user"] = ""
	if _, err = readConfig(strings.NewReader(configJSON(t, value))); err == nil {
		t.Fatal("unauthenticated local listener accepted")
	}
}

func TestMcrfSstpRejectAmbiguousConfigAndEndpoint(t *testing.T) {
	value := sstpConfig()
	source := configJSON(t, value)
	for _, text := range []string{source + " {}", strings.TrimSuffix(source, "}") + `,"server":"evil.test"}`, `{"server":null}`, strings.Repeat(" ", 65537)} {
		if _, err := readConfig(strings.NewReader(text)); err == nil {
			t.Fatal("ambiguous configuration accepted")
		}
	}
	for _, server := range []string{"https://host/path", "host:443", "host?query", "host\r\nheader", "-bad.example"} {
		value["server"] = server
		if _, err := readConfig(strings.NewReader(configJSON(t, value))); err == nil {
			t.Fatal("unsafe host accepted")
		}
	}
	value["server"] = "2001:db8::1"
	value["server_port"] = 0
	if _, err := readConfig(strings.NewReader(configJSON(t, value))); err == nil {
		t.Fatal("explicit zero port accepted")
	}
	value["server_port"] = 443
	value["socks"] = "0.0.0.0:12345"
	if _, err := readConfig(strings.NewReader(configJSON(t, value))); err == nil {
		t.Fatal("nonprivate listener accepted")
	}
}

func TestMcrfSstpTypedCertificateFailureIsPreserved(t *testing.T) {
	if failure(x509.UnknownAuthorityError{}) != "SSTP_TLS_UNKNOWN_AUTHORITY" {
		t.Fatal("certificate trust error hidden")
	}
	if failure(x509.HostnameError{}) != "SSTP_TLS_NAME_MISMATCH" {
		t.Fatal("certificate hostname error hidden")
	}
	if failure(nil) != "SSTP_SESSION_CLOSED" {
		t.Fatal("session close lost")
	}
}
