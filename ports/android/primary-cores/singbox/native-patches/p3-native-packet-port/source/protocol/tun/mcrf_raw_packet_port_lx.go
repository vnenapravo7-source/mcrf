//go:build with_lx_raw_packet && with_lx_raw_packet_port && (linux || android)

package tun

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	singtun "github.com/sagernet/sing-tun"
)

// The native ForwardDispatcher owns NAT, routing and flow lifetimes in port mode.
func wrapMcrfRawPacketTun(_ context.Context, _ adapter.Router, _, _ string, underlying singtun.Tun) (singtun.Tun, func(), error) {
	return underlying, nil, nil
}
