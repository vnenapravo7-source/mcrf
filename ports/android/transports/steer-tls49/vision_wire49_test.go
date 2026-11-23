package main

import (
	"crypto/tls"
	"io"
	"net"
	"testing"
	"time"
)

// Coalesce the last encrypted application record and first Vision-direct wire
// bytes in ONE underlying write, so a read-ahead regression cannot be hidden by
// the peer delivering them in convenient separate packets.
type directTail49 struct {
	net.Conn
	enabled bool
}

func (c *directTail49) Write(p []byte) (int, error) {
	if !c.enabled {
		return c.Conn.Write(p)
	}
	c.enabled = false
	combined := append(append([]byte{}, p...), []byte("wire")...)
	n, e := c.Conn.Write(combined)
	if n > len(p) {
		n = len(p)
	}
	return n, e
}

func TestUTLSReceiveDirectCoalescedWirePreservesTLSWriter49(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	for _, name := range []string{"chrome", "firefox", "edge", "ios"} {
		t.Run(name, func(t *testing.T) {
			a, b := net.Pipe()
			defer a.Close()
			done := make(chan error, 1)
			go func() {
				defer b.Close()
				_ = b.SetDeadline(time.Now().Add(4 * time.Second))
				wire := &directTail49{Conn: b}
				server := tls.Server(wire, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13})
				e := server.Handshake()
				if e == nil {
					wire.enabled = true
					_, e = server.Write([]byte("plaintext"))
				}
				if e == nil {
					var out [9]byte
					_, e = io.ReadFull(server, out[:])
					if e == nil && string(out[:]) != "still TLS" {
						e = errPolicy
					}
				}
				done <- e
			}()
			r := newRegistry49()
			epoch := r.begin()
			defer r.stop(epoch)
			o := baseOptions49()
			o.Fingerprint = name
			id, e := r.open(epoch, a, o, roots)
			if e != nil {
				t.Fatal("owned TLS handshake", e)
			}
			s := r.get(id)
			var received []byte
			var buf [3]byte
			until := time.Now().Add(time.Second)
			for len(received) == 0 && time.Now().Before(until) {
				n, e := s.read(buf[:], false)
				received = append(received, buf[:n]...)
				if e != nil && status49(e) != -108 {
					t.Fatal("initial TLS read", e)
				}
			}
			if len(received) == 0 {
				t.Fatal("no initial plaintext")
			}
			// The native transport enables only RX_DIRECT at this point. Drain its
			// existing decrypted/plain-wire buffers in their original order first.
			for len(received) < len("plaintextwire") {
				n, e := s.read(buf[:], true)
				if n > 0 {
					received = append(received, buf[:n]...)
					continue
				}
				if e != io.EOF {
					t.Fatal("receive-direct pending drain", e)
				}
				_ = a.SetReadDeadline(time.Now().Add(time.Second))
				missing := make([]byte, len("plaintextwire")-len(received))
				if _, e = io.ReadFull(a, missing); e != nil {
					t.Fatal("raw direct tail", e)
				}
				received = append(received, missing...)
			}
			if string(received) != "plaintextwire" {
				t.Fatal("receive-direct data reordered/lost")
			}
			if e = s.write([]byte("still TLS")); e != nil {
				t.Fatal("receive-direct detached TLS writer", e)
			}
			r.close(id)
			select {
			case e := <-done:
				if e != nil {
					t.Fatal("owned TLS peer", e)
				}
			case <-time.After(time.Second):
				t.Fatal("owned TLS peer did not retire")
			}
		})
	}
}
