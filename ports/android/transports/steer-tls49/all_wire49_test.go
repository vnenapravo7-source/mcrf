package main

import (
	"crypto/tls"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"time"
)

// All pinned names, including PSK templates on a cold connection, perform an
// actual certificate-authenticated handshake and exchange application bytes.
// A flexible TLS12/13 peer is intentional: random/old identities must not be
// silently forced into TLS13 to make the test pass. Forced versions have their
// separate test. No user endpoint, ambient roots or cached ticket is used.
func TestEveryRegisteredIdentityActualTLSAndALPN49(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	names := append(names49(), "unsafe")
	for _, name := range names {
		for _, protocols := range [][]string{nil, {}, {"http/1.1"}, {"h2", "http/1.1"}, {"http/1.1", "h2"}} {
			label := "absent"
			if protocols != nil {
				label = "explicit-" + strings.Join(protocols, ",")
			}
			t.Run(name+"/"+label, func(t *testing.T) {
				a, b := tcpPair49(t)
				defer a.Close()
				server := tls.Server(b, &tls.Config{Certificates: []tls.Certificate{cert},
					MinVersion: tls.VersionTLS12, MaxVersion: tls.VersionTLS13, NextProtos: []string{"http/1.1", "h2"}})
				done := make(chan error, 1)
				go func() {
					defer b.Close()
					_ = b.SetDeadline(time.Now().Add(4 * time.Second))
					e := server.Handshake()
					if e == nil {
						var request [1]byte
						_, e = io.ReadFull(server, request[:])
						if e == nil && request[0] != 49 {
							e = io.ErrUnexpectedEOF
						}
					}
					if e == nil {
						_, e = server.Write([]byte("owned-response-49"))
					}
					done <- e
				}()
				r := newRegistry49()
				epoch := r.begin()
				defer r.stop(epoch)
				o := baseOptions49()
				o.Fingerprint = name
				if protocols != nil {
					o.ALPN = &protocols
				}
				data, _ := json.Marshal(o)
				parsed, e := parseOptions49(data)
				if e != nil {
					t.Fatal("valid pinned identity refused before handshake", e)
				}
				id, e := r.open(epoch, a, parsed, roots)
				if e != nil {
					t.Fatal("actual pinned identity failed", e)
				}
				s := r.get(id)
				if s == nil {
					t.Fatal("no owned TLS session")
				}
				if protocols != nil && len(protocols) == 0 && s.alpn != "" {
					t.Fatal("explicit empty ALPN not preserved")
				}
				if e = s.write([]byte{49}); e != nil {
					t.Fatal("TLS application write", e)
				}
				var reply []byte
				until := time.Now().Add(2 * time.Second)
				for len(reply) < len("owned-response-49") && time.Now().Before(until) {
					var buf [7]byte // actual partial consumer reads, not one entire TLS record
					n, readErr := s.read(buf[:], false)
					reply = append(reply, buf[:n]...)
					if readErr != nil && status49(readErr) != -108 {
						t.Fatal("TLS application read", readErr)
					}
				}
				if string(reply) != "owned-response-49" {
					t.Fatal("actual authenticated application bytes differ")
				}
				r.close(id)
				select {
				case e := <-done:
					if e != nil {
						t.Fatal("owned server", e)
					}
				case <-time.After(time.Second):
					t.Fatal("owned server did not retire")
				}
			})
		}
	}
}
