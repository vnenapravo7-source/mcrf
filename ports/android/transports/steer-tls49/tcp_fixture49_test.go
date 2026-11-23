package main

import (
	"net"
	"testing"
	"time"
)

// Real TLS peers may both send CCS/alerts before reading the other direction.
// net.Pipe has no socket send buffer and can deadlock those valid writes. Use
// actual OWNED loopback TCP, not a synchronous pipe masquerading as a network.
// Pure byte-boundary/explicitly-blocked tests still use net.Pipe intentionally.
func tcpPair49(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	listener, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal("owned loopback listener", e)
	}
	defer listener.Close()
	_ = listener.SetDeadline(time.Now().Add(time.Second))
	client, e := net.DialTimeout("tcp4", listener.Addr().String(), time.Second)
	if e != nil {
		t.Fatal("owned loopback client", e)
	}
	server, e := listener.AcceptTCP()
	if e != nil {
		client.Close()
		t.Fatal("owned loopback accept", e)
	}
	t.Cleanup(func() { client.Close(); server.Close() })
	return client, server
}
