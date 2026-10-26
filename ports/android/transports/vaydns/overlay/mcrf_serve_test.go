package client

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestMcrfServeRejectsUnprotectedListener(t *testing.T) {
	ln, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	tunnel := &Tunnel{}
	if err = tunnel.McrfServe(context.Background(), ln, McrfBridge{}); err == nil {
		t.Fatal("missing auth hooks accepted")
	}
	if _, err = net.DialTimeout("tcp4", ln.Addr().String(), time.Second); err == nil {
		t.Fatal("rejected listener remained open")
	}
}

func TestMcrfServeRejectsWildcardListener(t *testing.T) {
	ln, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4zero})
	if err != nil {
		t.Fatal(err)
	}
	tunnel := &Tunnel{}
	auth := func(net.Conn) error { t.Fatal("auth ran on rejected listener"); return nil }
	if err = tunnel.McrfServe(context.Background(), ln, McrfBridge{LocalAuth: auth, RemoteAuth: auth}); err == nil {
		t.Fatal("wildcard listener accepted")
	}
}

func TestMcrfBackoffCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := mcrfPause(ctx, 30*time.Second); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	delay := time.Second
	for i := 0; i < 10; i++ {
		delay = mcrfNextDelay(delay)
		if delay > 30*time.Second {
			t.Fatal("unbounded retry")
		}
	}
	if delay != 30*time.Second {
		t.Fatal(delay)
	}
}

func TestMcrfRemoteAuthPreservesTerminalFailure(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	tracked := &mcrfDeadlineConn{Conn: left}
	if err := mcrfRemoteAuth(tracked, func(net.Conn) error { return ErrMcrfRemoteAuth }); !errors.Is(err, ErrMcrfRemoteAuth) {
		t.Fatal("authentication rejection lost")
	}
	if err := mcrfRemoteAuth(tracked, func(net.Conn) error { return nil }); err != nil {
		t.Fatal(err)
	}
	// Success must clear the handshake deadline so an established stream is
	// not silently torn down ten seconds later by the setup timeout.
	if len(tracked.deadlines) != 4 || tracked.deadlines[0].IsZero() || !tracked.deadlines[1].IsZero() || tracked.deadlines[2].IsZero() || !tracked.deadlines[3].IsZero() {
		t.Fatal("handshake deadlines were not set and cleared")
	}
}

type mcrfDeadlineConn struct {
	net.Conn
	deadlines []time.Time
}

func TestMcrfVayCapacityQueuesAndCancels(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if mcrfVayMaxClients != 512 {
		t.Fatal("unexpected client capacity")
	}
	slots := make(chan struct{}, mcrfVayMaxClients)
	for i := 0; i < 512; i++ {
		if err := mcrfVayAcquire(ctx, slots, nil, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	done := make(chan error, 1)
	go func() { done <- mcrfVayAcquire(ctx, slots, nil, nil, nil) }()
	select {
	case <-done:
		t.Fatal("full queue did not wait")
	case <-time.After(50 * time.Millisecond):
	}
	<-slots
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("capacity release did not resume")
	}
	go func() { done <- mcrfVayAcquire(ctx, slots, nil, nil, nil) }()
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("full queue blocked cancellation")
	}
}

func (c *mcrfDeadlineConn) SetDeadline(at time.Time) error {
	c.deadlines = append(c.deadlines, at)
	return c.Conn.SetDeadline(at)
}
