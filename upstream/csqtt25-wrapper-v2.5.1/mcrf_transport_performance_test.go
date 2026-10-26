package main

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestMcrfRawMTUCeilingPreservesSmallerServerValue(t *testing.T) {
	for _, c := range []struct{ in, want int }{{576, 576}, {1100, 1100}, {1280, 1280}, {1300, 1280}, {1500, 1280}} {
		if got := mcrfRawTransportMTU(c.in); got != c.want {
			t.Fatalf("MTU %d became %d; want %d", c.in, got, c.want)
		}
	}
}

func TestMcrfIPRelayBuffersOnOwnedSocket(t *testing.T) {
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	if mcrfIPRelayBufferBytes != 4<<20 {
		t.Fatal("unexpected per-adapter buffer request")
	}
	if err = mcrfConfigureIPRelaySocket(socket); err != nil {
		t.Fatal(err)
	}
	socket.Close()
	if mcrfConfigureIPRelaySocket(socket) == nil {
		t.Fatal("closed socket silently accepted")
	}
	// This checks option application, not an unclamped 4MiB kernel allocation.
	// Actual Android SO_RCVBUF/SO_SNDBUF belongs to the common device check.
}

func TestMcrfSOCKSAdmissionQueuesAndResumes(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	slots := make(chan struct{}, mcrfSOCKSMaxClients)
	for i := 0; i < 512; i++ {
		if !mcrfAcquireSOCKSSlot(ctx, slots) {
			t.Fatal("capacity smaller than 512")
		}
	}
	done := make(chan bool, 1)
	go func() { done <- mcrfAcquireSOCKSSlot(ctx, slots) }()
	select {
	case <-done:
		t.Fatal("overflow was not queued")
	case <-time.After(50 * time.Millisecond):
	}
	<-slots
	select {
	case ok := <-done:
		if !ok {
			t.Fatal("queued client rejected")
		}
	case <-time.After(time.Second):
		t.Fatal("queue did not resume")
	}
	// Full again: cancellation must not wait for another connection to finish.
	go func() { done <- mcrfAcquireSOCKSSlot(ctx, slots) }()
	cancel()
	select {
	case ok := <-done:
		if ok {
			t.Fatal("cancelled full queue admitted")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled queue stalled")
	}
}
