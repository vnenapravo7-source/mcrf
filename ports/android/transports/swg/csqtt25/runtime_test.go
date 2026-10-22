package csqtt25

import (
	"context"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var actualNative = flag.String("csqtt25-native", "", "Optional exact supplied native file: static digest/ELF inspection only, never execute")

func TestRuntimeCannotAcceptMissingOrLegacyOrWrongArtifact(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "libcsqtt25_transport.so")
	if _, e := LoadRuntime(path); e == nil {
		t.Fatal("missing runtime accepted")
	}
	if e := os.WriteFile(path, []byte("owned fixture; not an executable"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e := LoadRuntime(path); e == nil {
		t.Fatal("wrong artifact accepted")
	}
	if _, e := LoadRuntime(filepath.Join(root, "libcsqtt_transport.so")); e == nil {
		t.Fatal("legacy name accepted")
	}
}
func TestSuppliedActualRuntimeStaticGate(t *testing.T) {
	if *actualNative == "" {
		t.Skip("actual supplied artifact was not selected; no native execution")
	}
	runtime, e := LoadRuntime(*actualNative)
	if e != nil || runtime == nil {
		t.Fatal("actual supplied native SHA/ELF gate failed")
	}
	p := fixture()
	spec, e := Prepare(p)
	if e != nil {
		t.Fatal("owned profile preparation failed")
	}
	t.Setenv("CSQTT_CONFIG_FILE", "must-not-be-used")
	t.Setenv("CSQTT_EVENTS", "0")
	t.Setenv("MCRF_CAPTCHA_IDS", "1")
	t.Setenv("LD_PRELOAD", "must-not-be-loaded")
	t.Setenv("CSQTT_PASSWORD", "must-not-be-inherited")
	dir := t.TempDir()
	command, e := runtime.Command(context.Background(), spec, dir)
	if e != nil || command == nil || command.Process != nil || command.Dir != dir {
		t.Fatal("static command construction failed or executed supplied code")
	}
	owned := 0
	passwords := 0
	for _, value := range command.Env {
		if strings.HasPrefix(value, "CSQTT_CONFIG_FILE=") || strings.HasPrefix(value, "LD_PRELOAD=") || strings.HasPrefix(value, "MCRF_CAPTCHA_IDS=") || strings.Contains(value, "must-not-be") {
			t.Fatal("legacy profile or loader override inherited")
		}
		if strings.HasPrefix(value, "CSQTT_EVENTS=") {
			owned++
			if value != "CSQTT_EVENTS=1" {
				t.Fatal("structured events disabled")
			}
		}
		if strings.HasPrefix(value, "CSQTT_PASSWORD=") {
			passwords++
			if value != "CSQTT_PASSWORD="+p.Password { t.Fatal("password ingress changed") }
		}
	}
	if owned != 1 || passwords != 1 || has(command.Args[1:], "-password", p.Password) || !has(command.Args[1:], "-obfs", p.Obfs) {
		t.Fatal("actual2.5 CLI contract changed")
	}
	for _, arg := range command.Args[1:] {
		if arg == p.Password || arg == "-device-id" || arg == "--allow-hash-redistribution" { t.Fatal("obsolete native argument") }
	}
}
