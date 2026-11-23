package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"io"
	"testing"
	"time"
)

// Construct one ephemeral draft-0xfe0d ECHConfigList as supported by the pinned
// client and Go server. Wire layout matches Go crypto/tls's independent parser;
// no fixture private key/certificate exists until the common tests execute.
func ownedECH49(t *testing.T) (tls.EncryptedClientHelloKey, string) {
	t.Helper()
	private, e := ecdh.X25519().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	vec := func(p []byte) []byte {
		b := make([]byte, 2+len(p))
		binary.BigEndian.PutUint16(b, uint16(len(p)))
		copy(b[2:], p)
		return b
	}
	name := []byte("public.fixture.invalid")
	body := []byte{49, 0, 0x20} // config_id, DHKEM(X25519, HKDF-SHA256)
	body = append(body, vec(private.PublicKey().Bytes())...)
	body = append(body, vec([]byte{0, 1, 0, 1})...) // HKDF-SHA256 / AES-128-GCM
	body = append(body, 0, byte(len(name)))
	body = append(body, name...)
	body = append(body, 0, 0)
	config := append([]byte{0xfe, 0x0d}, vec(body)...)
	return tls.EncryptedClientHelloKey{Config: config, PrivateKey: private.Bytes()}, base64.StdEncoding.EncodeToString(vec(config))
}

func TestOwnedECHAcceptedAndRejectedWithoutFallback49(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	key, list := ownedECH49(t)
	_, foreign, _ := fixturePKI49(t)
	for _, name := range []string{"chrome", "firefox", "hellogolang", "unsafe"} {
		for _, mode := range []string{"accepted", "server-no-key", "foreign-root"} {
			t.Run(name+"/"+mode, func(t *testing.T) {
				a, b := tcpPair49(t)
				defer a.Close()
				cfg := &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13}
				if mode != "server-no-key" {
					cfg.EncryptedClientHelloKeys = []tls.EncryptedClientHelloKey{key}
				}
				done := make(chan error, 1)
				go func() {
					defer b.Close()
					_ = b.SetDeadline(time.Now().Add(4 * time.Second))
					server := tls.Server(b, cfg)
					e := server.Handshake()
					if e == nil {
						if !server.ConnectionState().ECHAccepted {
							e = errPolicy
						}
					}
					if e == nil {
						_, e = server.Write([]byte{49})
					}
					done <- e
				}()
				r := newRegistry49()
				epoch := r.begin()
				defer r.stop(epoch)
				o := baseOptions49()
				o.Fingerprint = name
				o.ECH = list
				trust := roots
				if mode == "foreign-root" {
					trust = foreign
				}
				id, e := r.open(epoch, a, o, trust)
				if mode != "accepted" {
					if e == nil || id != 0 {
						t.Fatal("ECH rejection/untrusted chain produced a usable session")
					}
				} else {
					if e != nil {
						t.Fatal("owned ECH failed", e)
					}
					s := r.get(id)
					var response [1]byte
					until := time.Now().Add(time.Second)
					received := false
					for time.Now().Before(until) {
						n, e := s.read(response[:], false)
						if n == 1 {
							received = response[0] == 49
							break
						}
						if e != nil && e != io.EOF && status49(e) != -108 {
							t.Fatal(e)
						}
					}
					if !received {
						t.Fatal("no authenticated ECH application data")
					}
				}
				r.close(id)
				select {
				case e := <-done:
					if mode == "accepted" && e != nil {
						t.Fatal("owned ECH server failed", e)
					}
				case <-time.After(time.Second):
					t.Fatal("owned ECH server did not retire")
				}
			})
		}
	}
}
