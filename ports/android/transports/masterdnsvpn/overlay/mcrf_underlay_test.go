// SPDX-License-Identifier: GPL-3.0-or-later
package client

import (
	"errors"
	"net"
	"testing"
)

func TestMcrfMasterUnderlayBeforeUDP(t *testing.T) {
	c := &Client{}
	denied := errors.New("network binding refused")
	never := func(uint64) error { t.Fatal("unexpected process bind"); return nil }
	for _, invalid := range []string{"0", "-1", "bad"} {
		if err := c.mcrfBindUnderlay(invalid, never, nil); err == nil {
			t.Fatalf("accepted invalid handle %q", invalid)
		}
	}
	if err := c.mcrfBindUnderlay("", never, nil); err != nil {
		t.Fatalf("legacy launch changed: %v", err)
	}
	if err := c.mcrfBindUnderlay("123", func(uint64) error { return denied }, nil); !errors.Is(err, denied) {
		t.Fatalf("lost process bind error: %v", err)
	}
	if c.mcrfSocketControl != nil {
		t.Fatal("installed socket hook after process bind failure")
	}
	var sockets int
	if err := c.mcrfBindUnderlay("123", func(handle uint64) error {
		if handle != 123 {
			t.Fatal("wrong process handle")
		}
		return nil
	}, func(handle uint64, _ uintptr) error {
		if handle != 123 {
			t.Fatal("wrong socket handle")
		}
		sockets++
		return denied
	}); err != nil {
		t.Fatal(err)
	}
	if conn, err := c.mcrfListenTunnelUDP(); conn != nil || !errors.Is(err, denied) {
		t.Fatalf("worker socket did not fail closed: %v, %v", conn, err)
	}
	if conn, err := c.mcrfDialResolverUDP(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9}); conn != nil || !errors.Is(err, denied) {
		t.Fatalf("resolver socket did not fail closed: %v, %v", conn, err)
	}
	if sockets != 2 {
		t.Fatalf("expected one binding per socket, got %d", sockets)
	}
}
