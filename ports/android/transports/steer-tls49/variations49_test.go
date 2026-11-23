package main

import (
	"encoding/json"
	"net"
	"reflect"
	"sort"
	"strings"
	"testing"

	utls "github.com/refraction-networking/utls"
)

func baseOptions49() options49 {
	return options49{Security: "tls", Host: "fixture.invalid", SNI: "fixture.invalid", TimeoutMS: 2000}
}

func names49() []string {
	set := map[string]bool{"": true}
	for _, m := range []map[string]*utls.ClientHelloID{PresetFingerprints, ModernFingerprints, OtherFingerprints} {
		for name, id := range m {
			if id != nil {
				set[name] = true
			}
		}
	}
	names := make([]string, 0, len(set))
	for name := range set {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// Uses EVERY actual registered identity, not just a handpicked Edge/Chrome pair.
// It builds real ClientHellos without opening a socket or claiming TLS traffic.
func TestAllRegisteredHelloAndALPN49(t *testing.T) {
	for _, name := range names49() {
		for _, protocols := range [][]string{nil, {}, {"http/1.1"}, {"h2", "http/1.1"}, {"http/1.1", "h2"}} {
			t.Run(name+"/"+strings.Join(protocols, ","), func(t *testing.T) {
				o := baseOptions49()
				o.Fingerprint = name
				if protocols != nil {
					o.ALPN = &protocols
				}
				data, _ := json.Marshal(o)
				parsed, e := parseOptions49(data)
				if e != nil {
					t.Fatal(e)
				}
				id, e := fingerprint49(parsed.Fingerprint)
				if e != nil {
					t.Fatal(e)
				}
				c := utls.UClient(nil, &utls.Config{ServerName: o.SNI, InsecureSkipVerify: true, MinVersion: utls.VersionTLS12, SessionTicketsDisabled: true, OmitEmptyPsk: true, NextProtos: selectedALPN49(parsed)}, id)
				if e := applyALPN49(c, selectedALPN49(parsed)); e != nil {
					t.Fatal(e)
				}
				if protocols != nil && !reflect.DeepEqual([]string(c.HandshakeState.Hello.AlpnProtocols), protocols) {
					// nil and [] both mean an empty wire ALPN list after explicit removal.
					if len(protocols) > 0 || len(c.HandshakeState.Hello.AlpnProtocols) > 0 {
						t.Fatal("ordered explicit ALPN changed")
					}
				}
				if len(c.HandshakeState.Hello.Raw) == 0 {
					if e := c.MCRFMarshalHello49(); e != nil {
						t.Fatal(e)
					}
				}
				if len(c.HandshakeState.Hello.Raw) < 40 {
					t.Fatal("missing actual Hello bytes")
				}
			})
		}
	}
}

func TestOptionsRejectAmbiguityAndMalformed49(t *testing.T) {
	for _, data := range []string{
		`{"security":"tls","security":"reality","host":"fixture.invalid","timeoutMS":2000}`,
		`{"security":"tls","Security":"reality","host":"fixture.invalid","timeoutMS":2000}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":2000,"fingerprint":"unknown"}`,
		`{"security":"reality","host":"fixture.invalid","timeoutMS":2000,"fingerprint":"unsafe"}`,
		`{"security":"reality","host":"fixture.invalid","timeoutMS":2000,"fingerprint":"hellogolang"}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":2000,"alpn":[],"requiredALPN":"h2"}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":2000,"alpn":[""]}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":2000,"unexpected":true}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":2000} {}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":0}`,
		`{"security":"tls","host":"fixture.invalid","timeoutMS":2000,"sni":"a\u0000b"}`,
	} {
		if _, e := parseOptions49([]byte(data)); e == nil {
			t.Fatal("accepted ambiguous or malformed options")
		}
	}
}

func TestTLS12RealityConflictBeforeDial49(t *testing.T) {
	for _, name := range []string{"android", "helloandroid_11_okhttp", "360", "hello360_7_5"} {
		o := baseOptions49()
		o.Fingerprint = name
		if e := validateHello49(o); e != nil {
			t.Fatalf("ordinary TLS identity refused: %s", name)
		}
		o.Security = "reality"
		if e := validateHello49(o); e == nil {
			t.Fatalf("TLS12 identity falsely accepted Reality: %s", name)
		}
	}
}

func TestEpochStoppedAndBadHandshakeDoNotLeak49(t *testing.T) {
	r := newRegistry49()
	epoch := r.begin()
	r.stop(epoch)
	a, b := net.Pipe()
	defer b.Close()
	if id, e := r.open(epoch, a, baseOptions49(), nil); e == nil || id != 0 {
		t.Fatal("late open published")
	}
	r.mu.Lock()
	n := len(r.sessions)
	r.mu.Unlock()
	if n != 0 {
		t.Fatal("stopped owner leaked session")
	}
	epoch = r.begin()
	a, b = net.Pipe()
	defer b.Close()
	if id, e := r.open(epoch, a, baseOptions49(), nil); e == nil || id != 0 {
		t.Fatal("missing roots accepted")
	}
	r.mu.Lock()
	n = len(r.sessions)
	r.mu.Unlock()
	if n != 0 {
		t.Fatal("failed handshake leaked handle")
	}
	r.stop(epoch)
}
