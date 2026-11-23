package main

import (
	"context"
	"errors"
	"net"
	"testing"
)

func TestMcrfDnsttUnderlayRejectsInvalidHandle(t *testing.T) {
	bindProcess := func(uint64) error { t.Fatal("invalid handle reached Android"); return nil }
	for _, value := range []string{"0", "-1", "1.1", "abc", "18446744073709551616"} {
		if _, err := newDNSTTUnderlay(value, bindProcess, dnsttBindSocket); err == nil {
			t.Fatalf("accepted invalid handle %q", value)
		}
	}
	if disabled, err := newDNSTTUnderlay("", bindProcess, dnsttBindSocket); err != nil || disabled.control != nil {
		t.Fatalf("legacy mode must leave bindings disabled: %v", err)
	}
}

func TestMcrfDnsttUnderlayRejectsUnboundSocket(t *testing.T) {
	denied := errors.New("fixture network denied")
	bindProcess := func(handle uint64) error {
		if handle != 42 {
			t.Fatalf("unexpected process handle %d", handle)
		}
		return nil
	}
	if _, err := newDNSTTUnderlay("42", func(uint64) error { return denied }, dnsttBindSocket); !errors.Is(err, denied) {
		t.Fatalf("process binding failure was lost: %v", err)
	}

	calls := 0
	bound, err := newDNSTTUnderlay("42", bindProcess, func(handle uint64, fd uintptr) error {
		calls++
		if handle != 42 {
			t.Fatalf("unexpected socket handle %d", handle)
		}
		return denied
	})
	if err != nil {
		t.Fatal(err)
	}
	if conn, err := bound.listenUDP(context.Background()); !errors.Is(err, denied) {
		if conn != nil {
			conn.Close()
		}
		t.Fatalf("UDP bypassed failed socket binding: %v", err)
	}
	for _, dialer := range []*net.Dialer{bound.dialer(), bound.httpDialer()} {
		if conn, err := dialer.DialContext(context.Background(), "tcp4", "127.0.0.1:1"); !errors.Is(err, denied) {
			if conn != nil {
				conn.Close()
			}
			t.Fatalf("TCP bypassed failed socket binding: %v", err)
		}
	}
	if calls != 3 {
		t.Fatalf("want three socket admission calls, got %d", calls)
	}
}
