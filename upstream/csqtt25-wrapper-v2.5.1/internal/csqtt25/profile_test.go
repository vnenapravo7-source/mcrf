package csqtt25

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func fixture() Profile {
	return Profile{Version: "2.5", Peer: "fixture.invalid:46000", Password: "fixture-only-secret", Hashes: []string{"fixture-call-0123456789abcdef"}, HashMode: "manual", Workers: 10, DeviceID: "fixture-binding", Listen: "127.0.0.1:22001", CaptchaMode: "auto", VKAuthMode: "vkcalls", Fingerprint: "chrome", Obfs: "audio", TurnTransport: "udp"}
}
func Test25ExtendedHashPoolReachesNativeArguments(t *testing.T) {
    p:=fixture();p.Hashes=nil;p.Workers=100
    for i:=0;i<12;i++{p.Hashes=append(p.Hashes,fmt.Sprintf("fixture-call-%d-0123456789abcdef",i))}
    spec,err:=Prepare(p)
    if err!=nil||!has(spec.args,"-vk",strings.Join(p.Hashes,",")){t.Fatalf("extended native pool lost: %v",err)}
}

func Test25OwnMaskingAndServerGenerationAreNotDropped(t *testing.T) {
	p := fixture()
	p.Obfs = "video"
	p.ClientIDs = "fixture-client"
	p.Generation = 17
	p.Salt = "fixture-salt"
	s, e := Prepare(p)
	if e != nil || !has(s.args, "-obfs", "video") || !has(s.args, "-client-ids", p.ClientIDs) ||
		!has(s.args, "-gen", "17") || !has(s.args, "-salt", p.Salt) {
		t.Fatal("new native settings lost")
	}
	p.Generation = -1
	if _, e := Prepare(p); e == nil {
		t.Fatal("negative generation accepted")
	}
}

func Test25UnifiedMaskingAndEmptyFingerprintRetainNewNativeDefaults(t *testing.T) {
	p := fixture()
	p.Obfs = "unified"
	p.Fingerprint = ""
	s, e := Prepare(p)
	if e != nil || !has(s.args, "-obfs", "unified") {
		t.Fatal("actual2.5 unified masking rejected")
	}
	for _, arg := range s.args {
		if arg == "-fingerprint" {
			t.Fatal("empty upstream fingerprint was silently replaced")
		}
	}
}

func Test25AuthenticatedBridgeFieldsNeverBecomeNativeArguments(t *testing.T) {
	p := fixture()
	if p.ValidateBridge() == nil {
		t.Fatal("missing authenticated bridge accepted")
	}
	p.SOCKS = "127.0.0.1:22002"
	p.SOCKSUser = "fixture-socks-user"
	p.SOCKSPass = "fixture-socks-password"
	if p.ValidateBridge() != nil {
		t.Fatal("owned authenticated bridge rejected")
	}
	s, e := Prepare(p)
	if e != nil {
		t.Fatal(e)
	}
	for _, arg := range s.args {
		if arg == p.SOCKS || arg == p.SOCKSUser || arg == p.SOCKSPass {
			t.Fatal("host bridge credentials exposed as native CLI options")
		}
	}
	p.SOCKS = "0.0.0.0:22002"
	if p.ValidateBridge() == nil {
		t.Fatal("public unaudited bridge accepted")
	}
}
func has(args []string, key, value string) bool {
	for i := 0; i+1 < len(args); i++ {
		if args[i] == key && args[i+1] == value {
			return true
		}
	}
	return false
}
func Test25ExplicitVersionAndWorkersNeverUseLegacyRounding(t *testing.T) {
	for _, n := range []int{3, 7, 18, 99, 100} {
		p := fixture()
		p.Workers = n
		p.ExactWorkers = true
		s, e := Prepare(p)
		if e != nil || !has(s.args, "-n", fmt.Sprint(n)) {
			t.Fatal("exact worker contract lost")
		}
	}
	for _, version := range []string{"", "legacy", "2.1.9", "future"} {
		p := fixture()
		p.Version = version
		if _, e := Prepare(p); e == nil {
			t.Fatal("wrong generation accepted")
		}
	}
	p := fixture()
	p.Workers = 7
	if _, e := Prepare(p); e == nil {
		t.Fatal("non-exact workers silently rounded")
	}
	p.Workers = 40
	if _, e := Prepare(p); e == nil {
		t.Fatal("manual call capacity silently exceeded")
	}
	p.AllowHashRedistribution = true
	s, e := Prepare(p)
	if e != nil || !has(s.args, "-n", "40") {
		t.Fatal("explicit redistribution was silently capped")
	}
}
func Test25CaptchaAndOwnTurnSettingsReachNativeSpec(t *testing.T) {
	for _, mode := range []string{"auto", "wv", "rjs"} {
		p := fixture()
		p.CaptchaMode = mode
		p.TurnTransport = "tcp"
		p.CustomTurnIPs = []string{"192.0.2.1", "198.51.100.2"}
		s, e := Prepare(p)
		if e != nil || !has(s.args, "-captcha-mode", mode) || !has(s.args, "-turn-transport", "tcp") || !has(s.args, "-custom-turn-ips", "192.0.2.1,198.51.100.2") {
			t.Fatal("native settings lost")
		}
	}
}
func Test25TokenBootstrapDoesNotTruncateOrMixManualHashPool(t *testing.T) {
	p := fixture()
	p.Hashes = nil
	p.Token = "fixture-private-token"
	p.HashMode = "auto_js"
	p.VKAuthMode = "auto_js"
	s, e := Prepare(p)
	if e != nil || !strings.HasPrefix(s.bootstrap, "VK_JS_BOOTSTRAP:") || has(s.args, "-vk", p.Token) || !has(s.args, "-vk-hash-mode", "auto_js") {
		t.Fatal("private bootstrap contract lost")
	}
	p.Hashes = fixture().Hashes
	if _, e = Prepare(p); e == nil {
		t.Fatal("ambiguous credentials accepted")
	}
}
func Test25MalformedUnknownOrUnsafeDataNeverLeaksInErrors(t *testing.T) {
	p := fixture()
	b, _ := json.Marshal(p)
	good := string(b)
	for _, bad := range []string{good + "{}", strings.Replace(good, "\"2.5\"", "\"legacy\"", 1), strings.TrimSuffix(good, "}") + ",\"unknown\":true}", strings.TrimSuffix(good, "}") + ",\"csqtt_generation\":\"legacy\"}", strings.Repeat("x", 65537)} {
		if _, e := ReadProfile(strings.NewReader(bad)); e == nil || strings.Contains(e.Error(), p.Password) {
			t.Fatal("malformed profile or secret error")
		}
	}
	p.Listen = "0.0.0.0:22001"
	if _, e := Prepare(p); e == nil {
		t.Fatal("public listener accepted")
	}
	if strings.Contains(fmt.Sprint(p), p.Password) {
		t.Fatal("profile String leaked secret")
	}
	p = fixture()
	s, _ := Prepare(p)
	if strings.Contains(fmt.Sprint(s), p.Password) {
		t.Fatal("launch String leaked secret")
	}
}
