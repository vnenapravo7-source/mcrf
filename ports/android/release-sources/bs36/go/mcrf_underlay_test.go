package main

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"
)

type mcrfFixtureRawConn struct{}

func (mcrfFixtureRawConn) Control(action func(uintptr)) error { action(42); return nil }
func (mcrfFixtureRawConn) Read(func(uintptr) bool) error      { panic("unexpected read") }
func (mcrfFixtureRawConn) Write(func(uintptr) bool) error     { panic("unexpected write") }

func TestMcrfQwdttUnderlayRequiresAnExactHostSelection(t *testing.T) {
	for _, value := range []string{"0", "01", "+123", "abc", "18446744073709551616"} {
		if _, err := mcrfRequestedUnderlay(value, "qwdtt"); err == nil {
			t.Fatalf("admitted an invalid handle")
		}
	}
	if _, err := mcrfRequestedUnderlay("123", "csqtt"); err == nil {
		t.Fatal("another transport accepted the QWDTT underlay")
	}
	if handle, err := mcrfRequestedUnderlay("123", "qwdtt"); handle != 123 || err != nil {
		t.Fatal("the exact handle was refused")
	}
	if handle, err := mcrfRequestedUnderlay("", "csqtt"); handle != 0 || err != nil {
		t.Fatal("ordinary startup must not request binding")
	}
}

func TestMcrfQwdttUnderlayBindsProviderTCPUDPAndStopsOnFailure(t *testing.T) {
	oldHandle, oldBind := mcrfUnderlayHandle, mcrfBindSocketNetwork
	t.Cleanup(func() { mcrfUnderlayHandle, mcrfBindSocketNetwork = oldHandle, oldBind })
	mcrfUnderlayHandle = 123
	calls := 0
	refused := errors.New("fixture binding failed")
	mcrfBindSocketNetwork = func(handle uint64, fd uintptr) error {
		calls++
		if handle != 123 || fd != 42 {
			t.Fatal("changed physical identity or borrowed descriptor")
		}
		return refused
	}
	original := 0
	dialer := mcrfUnderlayDialer(net.Dialer{Control: func(_, _ string, _ syscall.RawConn) error {
		original++
		return nil
	}})
	ctx := context.Background()
	for _, protocol := range []string{"tcp4", "udp4"} {
		if err := dialer.ControlContext(ctx, protocol, "192.0.2.42:443", mcrfFixtureRawConn{}); err != refused {
			t.Fatalf("provider socket ignored binding failure: %v", err)
		}
	}
	if err := dialer.ControlContext(ctx, "tcp4", "127.0.0.1:12345", mcrfFixtureRawConn{}); err != nil || original != 3 || calls != 2 {
		t.Fatal("local socket handling altered or unexpected binding")
	}
}

