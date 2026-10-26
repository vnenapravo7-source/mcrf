package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"strings"
	"testing"
	"time"
)

func fixtureConfig(t *testing.T) map[string]any {
	t.Helper()
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture.test"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	return map[string]any{"domain": "t.example.test", "resolver": "127.0.0.1:5353", "certificate": string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), "socks": "127.0.0.1:12345", "socks_user": "fixture-user", "socks_pass": "fixture-password"}
}

func parseFixture(t *testing.T, value map[string]any) (config, error) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return readConfig(strings.NewReader(string(data)))
}

func TestMcrfSlipstreamStrictConfigAndMandatoryLeaf(t *testing.T) {
	value := fixtureConfig(t)
	c, err := parseFixture(t, value)
	if err != nil {
		t.Fatal(err)
	}
	if c.KeepAliveMS == nil || *c.KeepAliveMS != 1000 {
		t.Fatal("unexpected keep-alive default")
	}
	for _, key := range []string{"insecure", "backend", "authoritative", "socks_override"} {
		value[key] = true
		if _, err = parseFixture(t, value); err == nil {
			t.Fatalf("unsafe override accepted: %s", key)
		}
		delete(value, key)
	}
	value["certificate"] = ""
	if _, err = parseFixture(t, value); err == nil {
		t.Fatal("missing certificate accepted")
	}
}

func TestMcrfSlipstreamRejectDuplicateAndTrailingConfig(t *testing.T) {
	value := fixtureConfig(t)
	data, _ := json.Marshal(value)
	for _, input := range []string{string(data) + " {}", strings.TrimSuffix(string(data), "}") + `,"domain":"evil.test"}`, `{"domain":null}`, strings.Repeat(" ", 65537)} {
		if _, err := readConfig(strings.NewReader(input)); err == nil {
			t.Fatal("ambiguous config accepted")
		}
	}
}

func TestMcrfSlipstreamEndpointAndKeepAliveBounds(t *testing.T) {
	value := fixtureConfig(t)
	for _, endpoint := range []string{"resolver.example:53", "1.1.1.1:0", "127.0.0.1:65536", "https://1.1.1.1/dns-query"} {
		value["resolver"] = endpoint
		if _, err := parseFixture(t, value); err == nil {
			t.Fatal("unsafe resolver accepted")
		}
	}
	value["resolver"] = "[::1]:5353"
	if _, err := parseFixture(t, value); err != nil {
		t.Fatal(err)
	}
	for _, interval := range []int{0, 400, 1000, 60000} {
		value["keep_alive_ms"] = interval
		if _, err := parseFixture(t, value); err != nil {
			t.Fatal(err)
		}
	}
	for _, interval := range []int{-1, 1, 399, 60001, 65536} {
		value["keep_alive_ms"] = interval
		if _, err := parseFixture(t, value); err == nil {
			t.Fatal("bad interval accepted")
		}
	}
	value["keep_alive_ms"] = 1000
	value["socks"] = "0.0.0.0:12345"
	if _, err := parseFixture(t, value); err == nil {
		t.Fatal("public listener accepted")
	}
}
