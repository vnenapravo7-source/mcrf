package main

import (
	"net"
	"testing"
	"time"
)

func TestMcrfDnsttDeadSessionClosesItsListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	closed := make(chan struct{})
	stop := mobileWatchSession(listener, closed)
	defer stop()
	result := make(chan error, 1)
	go func() {
		c, err := listener.Accept()
		if c != nil {
			c.Close()
		}
		result <- err
	}()
	close(closed)
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("session death left listener accepting")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("dead session left Accept blocked")
	}
}
func TestMcrfDnsttSessionWatcherRetiresWithoutClosingLiveListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	closed := make(chan struct{})
	stop := mobileWatchSession(listener, closed)
	stop()
	// Stop must not close a still-valid listener. The accepted synthetic TCP
	// connection proves this without any external DNS or server traffic.
	result := make(chan error, 1)
	go func() {
		c, e := listener.Accept()
		if c != nil {
			c.Close()
		}
		result <- e
	}()
	c, err := net.DialTimeout("tcp", listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	select {
	case err := <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("watcher cancellation stalled Accept")
	}
}
