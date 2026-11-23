// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestMcrfSstpUnderlayBeforeDial(t *testing.T) {
	denied := errors.New("bind denied")
	never := func(uint64) error { t.Fatal("unexpected process bind"); return nil }
	for _, input := range []string{"0", "-1", "not-a-network"} {
		if _, err := newSSTPUnderlay(input, never, nil); err == nil {
			t.Fatalf("accepted %q", input)
		}
	}
	if dialer, err := newSSTPUnderlay("", never, nil); dialer != nil || err != nil {
		t.Fatalf("legacy launch changed: %v, %v", dialer, err)
	}
	if _, err := newSSTPUnderlay("123", func(uint64) error { return denied }, nil); !errors.Is(err, denied) {
		t.Fatalf("lost process bind failure: %v", err)
	}

	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for _, binding := range []error{nil, denied} {
		processBound, socketBound := false, false
		dialer, err := newSSTPUnderlay("123", func(handle uint64) error {
			processBound = handle == 123
			return nil
		}, func(handle uint64, fd uintptr) error {
			socketBound = handle == 123 && fd != ^uintptr(0)
			return binding
		})
		if err != nil || !processBound {
			t.Fatalf("process not bound before dial: %v", err)
		}
		conn, err := dialer.DialContext(ctx, "tcp4", listener.Addr().String())
		if conn != nil {
			conn.Close()
		}
		if !socketBound || !errors.Is(err, binding) {
			t.Fatalf("socket binding %v, want %v, called %v", err, binding, socketBound)
		}
	}
}
