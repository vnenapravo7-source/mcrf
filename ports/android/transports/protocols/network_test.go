// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"os"
	"runtime"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

func TestMcrfProtocolsPhysicalNetworkBinding(t *testing.T) {
	if runtime.GOOS != "android" {
		if bindPhysical(1) == nil {
			t.Fatal("host pretended to bind Android physical network")
		}
		return
	}
	// The fixture Instrumentation captures the actual opaque Android Network
	// handle. Never construct it from a netId or substitute the default network.
	handle, e := strconv.ParseUint(os.Getenv("MCRF_PROTOCOLS_FIXTURE_NETWORK_HANDLE"), 10, 64)
	if e != nil || handle == 0 {
		t.Fatal("actual physical network handle required")
	}
	if e = bindPhysical(handle); e != nil {
		t.Fatal(e)
	}
	bound, e := boundPhysical()
	if e != nil || bound != handle {
		t.Fatalf("child physical binding not confirmed: %v", e)
	}
	if bindSocket(handle, -1) == nil {
		t.Fatal("invalid socket accepted")
	}
	var calls atomic.Int32
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ctx = service.ContextWith(ctx, &dialer.McrfPhysicalControl{Bind: func(fd int) error { calls.Add(1); return bindSocket(handle, fd) }})
	sdk, e := dialer.NewDefault(ctx, option.DialerOptions{})
	if e != nil {
		t.Fatal(e)
	}
	target, stop := wireEcho(t)
	defer stop()
	tcp, e := sdk.DialContext(ctx, "tcp", target)
	if e != nil {
		t.Fatal(e)
	}
	tcp.Close()
	udp, e := sdk.ListenPacket(ctx, target)
	if e != nil {
		t.Fatal(e)
	}
	udp.Close()
	if calls.Load() < 2 {
		t.Fatal("original SDK Go TCP/UDP bypassed descriptor binding")
	}
}
