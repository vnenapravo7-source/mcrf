package main

import (
	"bytes"
	"crypto/tls"
	utls "github.com/refraction-networking/utls"
	"net"
	"testing"
	"time"
)

type serverCapture49 struct {
	net.Conn
	wire []byte
}

func TestActualHelloRetryRequestHybrid49(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	name := "hellorandomizedalpn"
	original := OtherFingerprints[name]
	if original == nil {
		t.Fatal("pinned random identity missing")
	}
	defer func() { OtherFingerprints[name] = original }()
	var chosen *utls.ClientHelloID
	// Deterministically find a REAL randomized template that advertises MLKEM
	// but doesn't initially key-share it. Do not edit curves or downgrade offers.
	for i := 0; i < 256; i++ {
		candidate := *original
		seed := new(utls.PRNGSeed)
		seed[0] = byte(i)
		candidate.Seed = seed
		c := utls.UClient(nil, &utls.Config{ServerName: "fixture.invalid", MinVersion: utls.VersionTLS12, SessionTicketsDisabled: true, OmitEmptyPsk: true}, candidate)
		if c.BuildHandshakeState() != nil {
			continue
		}
		h := c.HandshakeState.Hello
		offered, sent, has13 := false, false, false
		for _, g := range h.SupportedCurves {
			offered = offered || g == utls.X25519MLKEM768
		}
		for _, g := range h.KeyShares {
			sent = sent || g.Group == utls.X25519MLKEM768
		}
		for _, v := range h.SupportedVersions {
			has13 = has13 || v == utls.VersionTLS13
		}
		if offered && !sent && has13 {
			chosen = &candidate
			break
		}
	}
	if chosen == nil {
		t.Fatal("no deterministic offered-but-unsent hybrid random identity")
	}
	OtherFingerprints[name] = chosen
	a, b := tcpPair49(t)
	capture := &serverCapture49{Conn: b}
	done := make(chan error, 1)
	go func() {
		defer b.Close()
		_ = b.SetDeadline(time.Now().Add(3 * time.Second))
		done <- tls.Server(capture, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13, CurvePreferences: []tls.CurveID{tls.X25519MLKEM768}}).Handshake()
	}()
	r := newRegistry49()
	epoch := r.begin()
	defer r.stop(epoch)
	o := baseOptions49()
	o.Fingerprint = name
	id, e := r.open(epoch, a, o, roots)
	if e != nil {
		t.Fatal("actual hybrid HRR handshake", e)
	}
	r.close(id)
	if e := <-done; e != nil {
		t.Fatal("owned hybrid HRR server", e)
	}
	hrr := []byte{0xcf, 0x21, 0xad, 0x74, 0xe5, 0x9a, 0x61, 0x11, 0xbe, 0x1d, 0x8c, 0x02, 0x1e, 0x65, 0xb8, 0x91, 0xc2, 0xa2, 0x11, 0x16, 0x7a, 0xbb, 0x8c, 0x5e, 0x07, 0x9e, 0x09, 0xe2, 0xc8, 0xa8, 0x33, 0x9c}
	if !bytes.Contains(capture.wire, hrr) {
		t.Fatal("hybrid test did not exercise HRR")
	}
}

func (c *serverCapture49) Write(p []byte) (int, error) {
	if len(c.wire) < 8192 {
		n := 8192 - len(c.wire)
		if n > len(p) {
			n = len(p)
		}
		c.wire = append(c.wire, p[:n]...)
	}
	return c.Conn.Write(p)
}

// Force a real TLS1.3 HelloRetryRequest by selecting P256, which these exact
// initial browser Hellos support but do not initially key-share. This exercises
// the mature TLS state machine rather than a synthetic "retry succeeded" flag.
func TestActualHelloRetryRequestP25649(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	hrr := []byte{0xcf, 0x21, 0xad, 0x74, 0xe5, 0x9a, 0x61, 0x11, 0xbe, 0x1d, 0x8c, 0x02, 0x1e, 0x65, 0xb8, 0x91, 0xc2, 0xa2, 0x11, 0x16, 0x7a, 0xbb, 0x8c, 0x5e, 0x07, 0x9e, 0x09, 0xe2, 0xc8, 0xa8, 0x33, 0x9c}
	for _, name := range []string{"chrome", "edge"} {
		t.Run(name, func(t *testing.T) {
			a, b := tcpPair49(t)
			capture := &serverCapture49{Conn: b}
			done := make(chan error, 1)
			go func() {
				defer b.Close()
				_ = b.SetDeadline(time.Now().Add(3 * time.Second))
				done <- tls.Server(capture, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13, CurvePreferences: []tls.CurveID{tls.CurveP256}}).Handshake()
			}()
			r := newRegistry49()
			epoch := r.begin()
			defer r.stop(epoch)
			o := baseOptions49()
			o.Fingerprint = name
			id, e := r.open(epoch, a, o, roots)
			if e != nil {
				t.Fatal("actual HRR handshake failed", status49(e))
			}
			r.close(id)
			if e := <-done; e != nil {
				t.Fatal("owned peer failed", e)
			}
			if !bytes.Contains(capture.wire, hrr) {
				t.Fatal("test did not exercise an actual HelloRetryRequest")
			}
		})
	}
}
