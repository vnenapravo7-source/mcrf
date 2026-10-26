//go:build linux || android

package main

import (
	"context"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func fixtureRelay(t *testing.T) (net.Listener, string, context.CancelFunc, <-chan error) {
	t.Helper()
	dir, err := os.MkdirTemp("", "mcrf-slip-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	path := filepath.Join(dir, "peer.sock")
	peer, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	local, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		peer.Close()
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	auth, err := McrfSOCKS("local", "fixture-password", "", "")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- relay(ctx, local, path, auth); close(done) }()
	t.Cleanup(func() {
		cancel()
		peer.Close()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("relay cleanup stalled")
		}
	})
	return peer, local.Addr().String(), cancel, done
}

func fixtureLocal(t *testing.T, address string) *net.TCPConn {
	t.Helper()
	c, err := net.Dial("tcp4", address)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(2 * time.Second))
	if err = mcrfWrite(c, []byte{5, 1, 2}); err != nil {
		t.Fatal(err)
	}
	var response [2]byte
	if _, err = io.ReadFull(c, response[:]); err != nil || response != [2]byte{5, 2} {
		t.Fatal("local method negotiation failed")
	}
	auth := append([]byte{1, 5}, []byte("local")...)
	auth = append(auth, 16)
	auth = append(auth, []byte("fixture-password")...)
	if err = mcrfWrite(c, auth); err != nil {
		t.Fatal(err)
	}
	if _, err = io.ReadFull(c, response[:]); err != nil || response != [2]byte{1, 0} {
		t.Fatal("local authentication failed")
	}
	return c.(*net.TCPConn)
}

func TestMcrfSlipstreamUnauthenticatedClientCannotOpenRemoteStream(t *testing.T) {
	peer, address, _, _ := fixtureRelay(t)
	var accepted atomic.Bool
	go func() {
		c, e := peer.Accept()
		if e == nil {
			accepted.Store(true)
			c.Close()
		}
	}()
	c, err := net.Dial("tcp4", address)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	mcrfWrite(c, []byte{5, 1, 0})
	var response [2]byte
	if _, err = io.ReadFull(c, response[:]); err != nil || response != [2]byte{5, 255} {
		t.Fatal("unauthenticated greeting was not rejected")
	}
	c.Close()
	time.Sleep(30 * time.Millisecond)
	if accepted.Load() {
		t.Fatal("unauthenticated app reached QUIC ingress")
	}
}

func TestMcrfSlipstreamPrivateRelayPreservesHalfClose(t *testing.T) {
	peer, address, _, _ := fixtureRelay(t)
	result := make(chan error, 1)
	go func() {
		c, e := peer.Accept()
		if e != nil {
			result <- e
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(2 * time.Second))
		var greeting [3]byte
		if _, e = io.ReadFull(c, greeting[:]); e != nil {
			result <- e
			return
		}
		if e = mcrfWrite(c, []byte{5, 0}); e != nil {
			result <- e
			return
		}
		data, e := io.ReadAll(c)
		if e == nil && string(data) != "owned-upload" {
			e = io.ErrUnexpectedEOF
		}
		if e == nil {
			e = mcrfWrite(c, []byte("owned-response"))
		}
		result <- e
	}()
	c := fixtureLocal(t, address)
	if err := mcrfWrite(c, []byte("owned-upload")); err != nil {
		t.Fatal(err)
	}
	if err := c.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(c)
	if err != nil || string(data) != "owned-response" {
		t.Fatalf("half-close lost response: %v", err)
	}
	if err = <-result; err != nil {
		t.Fatal(err)
	}
}

func TestMcrfSlipstreamCancellationClosesPendingRemoteAuth(t *testing.T) {
	peer, address, cancel, done := fixtureRelay(t)
	accepted := make(chan net.Conn, 1)
	go func() {
		c, e := peer.Accept()
		if e == nil {
			accepted <- c
		}
	}()
	c := fixtureLocal(t, address)
	defer c.Close()
	var up net.Conn
	select {
	case up = <-accepted:
	case <-time.After(time.Second):
		t.Fatal("private stream not opened")
	}
	defer up.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancellation waited for remote authentication timeout")
	}
}
