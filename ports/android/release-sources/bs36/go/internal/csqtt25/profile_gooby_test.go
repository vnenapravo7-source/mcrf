package csqtt25

import (
	"encoding/json"
	"strings"
	"testing"
)

func Test25GoobyPresetsReachNativeAndDisabledKeepsOriginalArgv(t *testing.T) {
	p := fixture()
	p.TurnTransport = "tcp"
	baseline, e := Prepare(p)
	if e != nil {
		t.Fatal(e)
	}
	for _, strategy := range []string{"current", "gentle", "extended"} {
		p.GoobyStrategy = strategy
		p.GoobyEnabled = false
		s, e := Prepare(p)
		if e != nil || strings.Join(s.args, "\x00") != strings.Join(baseline.args, "\x00") {
			t.Fatal("inactive Gooby changed native argv", e)
		}
		p.GoobyEnabled = true
		s, e = Prepare(p)
		if e != nil {
			t.Fatal(e)
		}
		joined := strings.Join(s.args, " ")
		if !strings.Contains(joined, "--gooby") {
			t.Fatal("native option lost")
		}
		if strategy != "current" && !has(s.args, "--gooby-strategy", strategy) {
			t.Fatal("preset lost")
		}
	}
	p.GoobyEnabled = true
	p.TurnTransport = "udp"
	if _, e := Prepare(p); e == nil {
		t.Fatal("active UDP Gooby admitted")
	}
}
func Test25GoobyPrivateJsonRejectsCoercionsNullAndUnknownModes(t *testing.T) {
	b, _ := json.Marshal(fixture())
	base := strings.TrimSuffix(string(b), "}")
	for _, tail := range []string{`"gooby_enabled":null`, `"gooby_enabled":"true"`, `"gooby_enabled":1`, `"gooby_strategy":null`, `"gooby_strategy":1`, `"gooby_strategy":""`, `"gooby_strategy":"unknown"`} {
		if _, e := ReadProfile(strings.NewReader(base + "," + tail + "}")); e == nil {
			t.Fatal("invalid optional value admitted", tail)
		}
	}
	p := fixture()
	p.TurnTransport = "tcp"
	p.GoobyEnabled = true
	p.GoobyStrategy = "gentle"
	b, _ = json.Marshal(p)
	parsed, e := ReadProfile(strings.NewReader(string(b)))
	if e != nil || !parsed.GoobyEnabled || parsed.GoobyStrategy != "gentle" {
		t.Fatal("valid Gooby lost", e)
	}
}
