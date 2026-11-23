// SPDX-License-Identifier: GPL-3.0-or-later
package bridge

import (
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPrivateConfigBoundary(t *testing.T) {
	good := Config{API: 1, SOCKS: "127.0.0.1:1080", User: "fixture-user-123456", Password: "fixture-password-123456", Outbound: json.RawMessage(`{"protocol":"vless"}`)}
	raw, _ := json.Marshal(good)
	for _, test := range []struct {
		name  string
		data  string
		valid bool
	}{
		{"valid", string(raw), true}, {"public-listener", strings.Replace(string(raw), "127.0.0.1", "0.0.0.0", 1), false},
		{"wrong-api", strings.Replace(string(raw), `"api":1`, `"api":2`, 1), false},
		{"empty-password", strings.Replace(string(raw), "fixture-password-123456", "", 1), false},
		{"unknown-root", strings.TrimSuffix(string(raw), "}") + `,"inbounds":[]}`, false},
		{"trailing-json", string(raw) + `{}`, false}, {"too-large", strings.Repeat(" ", 65537), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			file := filepath.Join(t.TempDir(), "config.json")
			os.WriteFile(file, []byte(test.data), 0600)
			_, e := Read(file)
			if (e == nil) != test.valid {
				t.Fatal("boundary result")
			}
		})
	}
}

type fake struct{ starts, closes int }

func (f *fake) Start() error { f.starts++; return nil }
func (f *fake) Close() error { f.closes++; return nil }
func TestCancelWithoutLeakedCore(t *testing.T) {
	for _, command := range []string{"STOP\n", "NETWORK_CHANGED\n", ""} {
		t.Run(strings.TrimSpace(command), func(t *testing.T) {
			f := new(fake)
			created := false
			factory := func(context.Context, Config) (Core, error) { created = true; return f, nil }
			if e := Run(Config{}, factory, strings.NewReader(command), io.Discard); e != nil {
				t.Fatal(e)
			}
			if created && f.closes != 1 {
				t.Fatal("core leaked")
			}
		})
	}
}
func TestRejectUnsafeKeys(t *testing.T) {
	m, _ := Object(json.RawMessage(`{"protect_path":"secret"}`))
	if RejectKeys(m, "protect_path") == nil {
		t.Fatal("unsafe dial accepted")
	}
	if _, e := Object(json.RawMessage(`[]`)); e == nil {
		t.Fatal("array accepted")
	}
	if _, e := Object(json.RawMessage(`null`)); e == nil {
		t.Fatal("null accepted")
	}
}
