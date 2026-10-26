package integration

import (
	"context"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type ownedBackend struct {
	address     string
	connections atomic.Int64
}

func ownedSOCKSBackend(t *testing.T) *ownedBackend {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	backend := &ownedBackend{address: ln.Addr().String()}
	var workers sync.WaitGroup
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			conn, e := ln.Accept()
			if e != nil {
				return
			}
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer conn.Close()
				stop := context.AfterFunc(ctx, func() { conn.Close() })
				defer stop()
				conn.SetDeadline(time.Now().Add(10 * time.Second))
				backend.connections.Add(1)
				var greeting [3]byte
				if _, e := io.ReadFull(conn, greeting[:]); e != nil || greeting != [3]byte{5, 1, 2} {
					return
				}
				conn.Write([]byte{5, 2})
				var header [2]byte
				if _, e := io.ReadFull(conn, header[:]); e != nil || header[0] != 1 {
					return
				}
				user := make([]byte, int(header[1]))
				if _, e := io.ReadFull(conn, user); e != nil {
					return
				}
				var length [1]byte
				if _, e := io.ReadFull(conn, length[:]); e != nil {
					return
				}
				password := make([]byte, int(length[0]))
				if _, e := io.ReadFull(conn, password); e != nil {
					return
				}
				if string(user) != "remote" || string(password) != "owned-password" {
					conn.Write([]byte{1, 1})
					return
				}
				conn.Write([]byte{1, 0})
				// Readiness authenticates then closes without a CONNECT. Data streams
				// can CONNECT only to this synthetic destination; never forward it.
				var request [10]byte
				if _, e := io.ReadFull(conn, request[:]); e != nil {
					return
				}
				if request != [10]byte{5, 1, 0, 1, 198, 51, 100, 7, 1, 187} {
					return
				}
				conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 1})
				var payload [5]byte
				if _, e := io.ReadFull(conn, payload[:]); e == nil && string(payload[:]) == "OWNED" {
					conn.Write(payload[:])
				}
			}()
		}
	}()
	t.Cleanup(func() {
		cancel()
		ln.Close()
		<-acceptDone
		done := make(chan struct{})
		go func() { workers.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("backend workers did not retire")
		}
	})
	return backend
}
