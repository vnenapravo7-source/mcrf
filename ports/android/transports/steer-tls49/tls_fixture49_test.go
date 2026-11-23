package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"math/big"
	"net"
	"testing"
	"time"
)

// PKI is generated only WHEN the common fixture batch executes. The source
// preparation step creates no certificates; no production auth bypass exists.
func fixturePKI49(t *testing.T) (tls.Certificate, []byte, []byte) {
	return fixturePKIAt49(t, time.Now())
}
func fixturePKIAt49(t *testing.T, now time.Time) (tls.Certificate, []byte, []byte) {
	t.Helper()
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(49), Subject: pkix.Name{CommonName: "fixture.invalid"}, DNSNames: []string{"fixture.invalid"}, NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	der, e := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), der
}

func TestActualTLSCertificateNegativesAndExplicitInsecure49(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	_, foreign, _ := fixturePKI49(t)
	expired, _, _ := fixturePKIAt49(t, time.Now().Add(-2*time.Hour))
	cases := []struct {
		name   string
		cert   tls.Certificate
		roots  []byte
		change func(*options49)
		ok     bool
	}{
		{"wrong-name", cert, roots, func(o *options49) { o.SNI = "wrong.invalid" }, false},
		{"foreign-chain", cert, foreign, func(*options49) {}, false},
		{"missing-roots", cert, nil, func(*options49) {}, false},
		{"wrong-pin", cert, roots, func(o *options49) { o.CertPins = []string{hex.EncodeToString(make([]byte, 32))} }, false},
		{"expired", expired, roots, func(*options49) {}, false},
		{"explicit-insecure", cert, nil, func(o *options49) { o.Insecure = true; o.SNI = "wrong.invalid" }, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			a, b := tcpPair49(t)
			done := make(chan struct{})
			go func() {
				defer close(done)
				defer b.Close()
				_ = b.SetDeadline(time.Now().Add(3 * time.Second))
				_ = tls.Server(b, &tls.Config{Certificates: []tls.Certificate{c.cert}, MinVersion: tls.VersionTLS12, MaxVersion: tls.VersionTLS13}).Handshake()
			}()
			r := newRegistry49()
			epoch := r.begin()
			defer r.stop(epoch)
			o := baseOptions49()
			c.change(&o)
			id, e := r.open(epoch, a, o, c.roots)
			if (e == nil) != c.ok {
				t.Fatal("certificate admission mismatch", status49(e))
			}
			if !c.ok && status49(e) != -104 {
				t.Fatal("negative must prove certificate policy refusal, not a transport error", status49(e))
			}
			r.close(id)
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("owned peer did not retire")
			}
		})
	}
}

func TestActualTLS12And13AndPolicies49(t *testing.T) {
	cert, roots, der := fixturePKI49(t)
	for _, version := range []uint16{tls.VersionTLS12, tls.VersionTLS13} {
		for _, name := range []string{"chrome", "edge", "firefox", "safari", "ios", "qq", "hellogolang", "unsafe", "randomized", "randomizednoalpn", "android", "360"} {
			if version == tls.VersionTLS13 && (name == "android" || name == "360") {
				continue
			}
			t.Run(name+"/"+tls.VersionName(version), func(t *testing.T) {
				a, b := tcpPair49(t)
				server := tls.Server(b, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: version, MaxVersion: version, NextProtos: []string{"http/1.1"}})
				done := make(chan error, 1)
				go func() {
					defer b.Close()
					_ = b.SetDeadline(time.Now().Add(3 * time.Second))
					e := server.Handshake()
					if e == nil {
						_, e = server.Write([]byte("HTTP/1.1 204 No Content\r\n\r\n"))
					}
					done <- e
				}()
				r := newRegistry49()
				epoch := r.begin()
				defer r.stop(epoch)
				o := baseOptions49()
				o.Fingerprint = name
				o.RequiredALPN = "http/1.1"
				id, e := r.open(epoch, a, o, roots)
				if e != nil {
					t.Fatal("owned TLS failed", e)
				}
				s := r.get(id)
				if s == nil {
					t.Fatal("no ready session")
				}
				var out [128]byte
				n := 0
				until := time.Now().Add(time.Second)
				for n == 0 && time.Now().Before(until) {
					n, e = s.read(out[:], false)
					if e != nil && status49(e) != -108 {
						break
					}
				}
				if n == 0 {
					t.Fatal("no actual HTTPS response")
				}
				r.close(id)
				if e := <-done; e != nil {
					t.Fatal("owned server failed", e)
				}
			})
		}
	}
	good := baseOptions49()
	verify, e := certificatePolicy49(good, roots)
	if e != nil || verify([][]byte{der}, nil) != nil {
		t.Fatal("valid own chain/name refused")
	}
	bad := good
	bad.SNI = "wrong.invalid"
	verify, e = certificatePolicy49(bad, roots)
	if e != nil || verify([][]byte{der}, nil) == nil {
		t.Fatal("wrong name accepted")
	}
	h := sha256.Sum256(der)
	bad.CertPins = []string{hex.EncodeToString(h[:])}
	verify, e = certificatePolicy49(bad, nil)
	if e != nil || verify([][]byte{der}, nil) != nil {
		t.Fatal("native exact leaf pin policy changed")
	}
	bad.CertPins = []string{hex.EncodeToString(make([]byte, 32))}
	verify, e = certificatePolicy49(bad, roots)
	if e != nil || verify([][]byte{der}, nil) == nil {
		t.Fatal("wrong pin accepted through roots fallback")
	}
}

func TestStopInterruptsActualBlockedHandshake49(t *testing.T) {
	_, roots, _ := fixturePKI49(t)
	a, b := net.Pipe()
	defer b.Close()
	r := newRegistry49()
	epoch := r.begin()
	done := make(chan error, 1)
	go func() { _, e := r.open(epoch, a, baseOptions49(), roots); done <- e }()
	// Consume actual ClientHello bytes before cancellation, not an artificial
	// scheduler sleep that might stop before the handshake ever began.
	_ = b.SetReadDeadline(time.Now().Add(time.Second))
	var hello [4096]byte
	if n, e := b.Read(hello[:]); e != nil || n == 0 {
		t.Fatal("handshake never started")
	}
	r.stop(epoch)
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("stopped handshake published")
		}
	case <-time.After(time.Second):
		t.Fatal("STOP did not interrupt I/O")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.sessions) != 0 || len(r.owners) != 0 {
		t.Fatal("owner leak after STOP")
	}
}
