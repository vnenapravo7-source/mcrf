//go:build android && cgo

package main

import (
	"context"
	"net"
	"os"
	"syscall"
	"testing"
)

func TestMcrfQwdttUnderlayAndroidKernel(t *testing.T) {
	value := os.Getenv("MCRF_OWNED_TEST_NETWORK")
	if value == "" {
		t.Skip("requires an owned Android physical network")
	}
	t.Setenv(mcrfUnderlayEnv, value)
	t.Setenv("MCRF_BS_MODE", "qwdtt")
	if err := mcrfConfigureUnderlay(); err != nil {
		t.Fatal(err)
	}
	handle := mcrfUnderlayHandle
	t.Cleanup(func() {
		mcrfUnderlayHandle = 0
		_ = mcrfBindProcessNetwork(0)
	})
	bind := func(network uint64) error {
		listen := net.ListenConfig{Control: func(_, _ string, conn syscall.RawConn) error {
			var bindError error
			if err := conn.Control(func(fd uintptr) { bindError = mcrfBindSocketNetwork(network, fd) }); err != nil {
				return err
			}
			return bindError
		}}
		socket, err := listen.ListenPacket(context.Background(), "udp4", "0.0.0.0:0")
		if socket != nil {
			socket.Close()
		}
		return err
	}
	if err := bind(handle); err != nil {
		t.Fatalf("kernel refused the physical network: %v", err)
	}
	if err := bind(^uint64(0)); err == nil {
		t.Fatal("kernel accepted a nonexistent network")
	}
}
