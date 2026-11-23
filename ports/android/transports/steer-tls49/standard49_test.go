package main

import (
	"bytes"
	"crypto/tls"
	"io"
	"net"
	"testing"
	"time"
)

func TestStandardRecordReaderNeverPrefetchesNextRecord49(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	first := []byte{23, 3, 3, 0, 4, 1, 2, 3, 4}
	wire := append(append([]byte{}, first...), []byte{23, 3, 3, 0, 4, 5, 6, 7, 8}...)
	done := make(chan int, 1)
	go func() { n, _ := b.Write(wire); done <- n }()
	c := &recordBoundary49{Conn: a}
	buf := make([]byte, 1024)
	n, e := c.Read(buf)
	if e != nil || n != 5 {
		t.Fatal("reader crossed record header")
	}
	n, e = c.Read(buf)
	if e != nil || n != 4 || !bytes.Equal(buf[:n], first[5:]) {
		t.Fatal("reader prefetched next record")
	}
	_ = a.Close()
	select {
	case n := <-done:
		if n != len(first) {
			t.Fatal("prefetched raw bytes")
		}
	case <-time.After(time.Second):
		t.Fatal("owned writer did not retire")
	}
}

// Actual authenticated TLS with independent direction transitions: after the
// received DIRECT signal, drain plaintext, then the raw socket; outgoing bytes
// must still be TLS. No internal stdlib field casts, no TLS Close during drain.
func TestStandardTLSReceiveDirectPreservesTLSWriter49(t *testing.T) {
	cert, roots, _ := fixturePKI49(t)
	a, b := net.Pipe()
	done := make(chan error, 1)
	go func() {
		defer b.Close()
		_ = b.SetDeadline(time.Now().Add(3 * time.Second))
		server := tls.Server(b, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13})
		if e := server.Handshake(); e != nil {
			done <- e
			return
		}
		if _, e := server.Write([]byte("plaintext")); e != nil {
			done <- e
			return
		}
		if _, e := b.Write([]byte("wire")); e != nil {
			done <- e
			return
		}
		out := make([]byte, 9)
		_, e := io.ReadFull(server, out)
		if e == nil && string(out) != "still TLS" {
			e = errPolicy
		}
		done <- e
	}()
	r := newRegistry49()
	epoch := r.begin()
	defer r.stop(epoch)
	o := baseOptions49()
	o.Fingerprint = "unsafe"
	id, e := r.open(epoch, a, o, roots)
	if e != nil {
		t.Fatal("standard TLS handshake failed", status49(e))
	}
	s := r.get(id)
	var small [3]byte
	var plain []byte
	until := time.Now().Add(time.Second)
	for len(plain) == 0 && time.Now().Before(until) {
		n, e := s.read(small[:], false)
		if n > 0 {
			plain = append(plain, small[:n]...)
		} else if e != nil && status49(e) != -108 {
			t.Fatal(e)
		}
	}
	if len(plain) == 0 {
		t.Fatal("no first plaintext")
	}
	for s.hasData(true) {
		n, e := s.read(small[:], true)
		if e != nil || n == 0 {
			t.Fatal("pending drain stalled")
		}
		plain = append(plain, small[:n]...)
	}
	if string(plain) != "plaintext" {
		t.Fatal("plaintext lost or reordered")
	}
	_ = a.SetReadDeadline(time.Now().Add(time.Second))
	var raw [4]byte
	if _, e = io.ReadFull(a, raw[:]); e != nil || string(raw[:]) != "wire" {
		t.Fatal("standard TLS prefetched DIRECT wire data")
	}
	if e = s.write([]byte("still TLS")); e != nil {
		t.Fatal("receive transition detached TLS writer")
	}
	r.close(id)
	if e := <-done; e != nil {
		t.Fatal("owned peer failed", e)
	}
}
