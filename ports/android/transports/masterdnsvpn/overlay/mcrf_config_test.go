package config

import (
	"encoding/json"
	"strings"
	"testing"
)

func mcrfFixture(t *testing.T) map[string]any {
	t.Helper()
	return map[string]any{"domains": []string{"t.example.test"}, "resolvers": []string{"127.0.0.1:5353"}, "encryption_method": 5, "encryption_key": "owned-fixture-key", "socks": "127.0.0.1:12345", "socks_user": "local", "socks_pass": "owned-local-password"}
}
func mcrfParse(t *testing.T, value map[string]any) (McrfSettings, error) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return McrfRead(strings.NewReader(string(data)))
}

func TestMcrfMasterStrictConfigDoesNotExposeCliPaths(t *testing.T) {
	value := mcrfFixture(t)
	for _, key := range []string{"config_path", "resolvers_file", "log", "listen_ip", "protocol_type", "local_dns_enabled", "cache_persist", "Domains", "ENCRYPTION_KEY"} {
		value[key] = "untrusted"
		if _, err := mcrfParse(t, value); err == nil {
			t.Fatalf("CLI option accepted: %s", key)
		}
		delete(value, key)
	}
	value["socks"] = "0.0.0.0:12345"
	if _, err := mcrfParse(t, value); err == nil {
		t.Fatal("public listener accepted")
	}
}

func TestMcrfMasterPrivateConfigForcesAuthAndNoPersistence(t *testing.T) {
	value, err := mcrfParse(t, mcrfFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := McrfConfig(value, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ProtocolType != "SOCKS5" || !cfg.SOCKS5Auth || cfg.ListenIP != "127.0.0.1" || cfg.SOCKS5User != "local" {
		t.Fatal("private endpoint changed")
	}
	if cfg.LocalDNSEnabled || cfg.LocalDNSCachePersist || cfg.SaveMTUServersToFile {
		t.Fatal("standalone persistence/listener leaked into adapter")
	}
	if cfg.RX_TX_Workers != 2 || cfg.TunnelProcessWorkers != 2 {
		t.Fatal("unexpected Android worker default")
	}
	if cfg.EncryptionKey != "owned-fixture-key" || cfg.DataEncryptionMethod != 5 || len(cfg.Resolvers) != 1 {
		t.Fatal("wire settings silently changed")
	}
}

func TestMcrfMasterRejectsAmbiguousOrUnspecifiedEncryption(t *testing.T) {
	value := mcrfFixture(t)
	data, _ := json.Marshal(value)
	for _, source := range []string{string(data) + " {}", strings.TrimSuffix(string(data), "}") + `,"encryption_method":0}`, `{"domains":null}`, strings.Repeat(" ", 65537)} {
		if _, err := McrfRead(strings.NewReader(source)); err == nil {
			t.Fatal("ambiguous config accepted")
		}
	}
	delete(value, "encryption_method")
	if _, err := mcrfParse(t, value); err == nil {
		t.Fatal("upstream weak cipher default silently selected")
	}
	value["encryption_method"] = 5
	for _, control := range []string{"\x01", "\x1f", "\x7f", "\u0085"} {
		value["encryption_key"] = "key" + control
		if _, err := mcrfParse(t, value); err == nil {
			t.Fatal("control character accepted in encryption key")
		}
	}
	value["encryption_key"] = "owned key with spaces"
	value["resolvers"] = []string{"resolver.example:53"}
	if _, err := mcrfParse(t, value); err == nil {
		t.Fatal("implicit bootstrap DNS accepted")
	}
}
