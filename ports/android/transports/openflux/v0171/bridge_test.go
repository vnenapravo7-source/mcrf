package mobile

import (
	"encoding/json"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"path/filepath"
	"testing"
)

func mcrf0171Config() McrfConfig {
	return McrfConfig{Secret: "fixture-session-key", Negotiate: true, Context: "fixture-context", Codec: "legacy", Transports: []McrfTransport{
		{Type: "telemost", Name: "video", URL: "https://telemost.yandex.ru/j/fixture", Priority: 23, Params: map[string]interface{}{"stripes": "4", "stripes_max": "6"}},
		{Type: "direct", Name: "backup", Dial: "[2001:db8::1]:443", Priority: 2},
	}}
}
func TestMcrf0171SessionFields(t *testing.T) {
	c := mcrf0171Config()
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	raw, err := mcrfSessionSpecs(c)
	if err != nil {
		t.Fatal(err)
	}
	specs, context, _, err := parseSessionSpecs(raw)
	if err != nil || context != c.Context || len(specs) != 2 {
		t.Fatal("session/context lost")
	}
	if specs[0].Name != "video" || specs[0].Priority != 23 || specs[0].Params["stripes"] != "4" || specs[1].Params["dial"] != "[2001:db8::1]:443" {
		t.Fatal("transport fields lost")
	}
	// Both native room-list formats remain opaque, byte-for-byte.
	for _, typ := range []string{"yandex", "cupsonline", "avito"} {
		c.Transports = []McrfTransport{{Type: typ, URL: "eyJ2IjoyLCJyb29tcyI6WyJhIiwiYiJdfQ"}}
		raw, err := mcrfSessionSpecs(c)
		if err != nil {
			t.Fatal(err)
		}
		specs, _, _, err := parseSessionSpecs(raw)
		if err != nil || specs[0].URL != c.Transports[0].URL {
			t.Fatal("opaque room list changed")
		}
	}
}
func TestMcrf0171OfficialPackages(t *testing.T) {
	c := mcrf0171Config()
	var manifest struct {
		Packages []struct {
			ID string `json:"id"`
		} `json:"packages"`
	}
	raw, err := mcrfPackages.ReadFile("mcrf-packages/manifest.json")
	if err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal(raw, &manifest) != nil || len(manifest.Packages) != 10 {
		t.Fatal("official inventory lost")
	}
	c.Transports = nil
	for _, p := range manifest.Packages {
		c.Transports = append(c.Transports, McrfTransport{Type: "script", Name: p.ID, URL: "fixture", Params: map[string]interface{}{"name": p.ID, "settings": map[string]interface{}{"value": "fixture"}}})
	}
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if err := McrfPrepareScripts(filepath.Join(t.TempDir(), "profile.json"), &c); err != nil {
		t.Fatal(err)
	}
	defer McrfCleanupScripts()
	specs, err := mcrfSessionSpecs(c)
	if err != nil {
		t.Fatal(err)
	}
	parsed, _, _, err := parseSessionSpecs(specs)
	if err != nil || len(parsed) != 10 {
		t.Fatal("script specs lost")
	}
	for i, p := range parsed {
		settings, ok := p.Params["settings"].(map[string]interface{})
		if !ok || settings["value"] != "fixture" || p.Params["name"] != manifest.Packages[i].ID || p.Params["pubkey"] == "" || p.Params["path"] == "" {
			t.Fatal("script settings or pinned identity lost")
		}
	}
	for _, spec := range parsed {
		carrier, err := newRawTransport("script", spec.URL, spec.Params, transport.DefaultConfig(), false)
		if err != nil || carrier == nil {
			t.Fatal("official signed package rejected by the real registry/host API")
		}
		_ = carrier.Stop()
	}
}
func TestMcrf0171HostAPI(t *testing.T) {
	var result struct {
		OK bool `json:"ok"`
	}
	raw := ScriptEngineSelfTest(t.TempDir())
	if err := json.Unmarshal([]byte(raw), &result); err != nil || !result.OK {
		t.Fatalf("signed JS open/send/emit host API roundtrip failed: %s", raw)
	}
}
func TestMcrf0171HostOwnedParameters(t *testing.T) {
	for _, key := range []string{"path", "pubkey", "exit", "is_exit", "listen"} {
		c := mcrf0171Config()
		c.Transports[0].Params[key] = "untrusted"
		if c.Validate() == nil {
			t.Fatalf("host field accepted: %s", key)
		}
	}
	for _, name := range []string{"../arbitrary", "unpublished"} {
		c := mcrf0171Config()
		c.Transports = []McrfTransport{{Type: "script", Params: map[string]interface{}{"name": name}}}
		if c.Validate() == nil {
			t.Fatal("unknown script accepted")
		}
	}
	c := mcrf0171Config()
	c.Transports = []McrfTransport{{Type: "fluxfast", Dial: "127.0.0.1:443"}}
	if c.Validate() == nil {
		t.Fatal("fork-only protocol accepted")
	}
}
