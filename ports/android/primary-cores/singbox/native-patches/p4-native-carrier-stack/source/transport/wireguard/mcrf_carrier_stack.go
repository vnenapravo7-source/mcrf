//go:build with_gvisor && with_lx_raw_packet

package wireguard

import (
	"context"
	"errors"
	"net/netip"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
)

// NewMcrfCarrierStack exposes only the existing userspace IP device, without a
// WireGuard device, kernel TUN, SOCKS listener or second transport child. Normal
// native routing/sniffing can dial this device and send its IP packets through
// the same authenticated original carrier endpoint. It does not enable a route.
func NewMcrfCarrierStack(ctx context.Context, local netip.Addr, mtu uint32) (Device, error) {
	if ctx == nil || !local.Is4() || local.IsUnspecified() || local.IsLoopback() || local.IsMulticast() || mtu < 576 || mtu > 1984 {
		return nil, errors.New("MCRF_CARRIER_STACK_ARGUMENT")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	d, err := newStackDevice(DeviceOptions{Context: ctx, MTU: mtu, Address: []netip.Prefix{netip.PrefixFrom(local, 32)}})
	if err != nil {
		return nil, err
	}
	// This stack terminates external TCP, not a local SOCKS hop. Match the
	// Android kernel's CUBIC instead of gVisor's Reno default, which reacts
	// differently to loss across TURN. Keep the existing network-sized buffers.
	congestion := tcpip.CongestionControlOption("cubic")
	if gErr := d.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &congestion); gErr != nil {
		_ = d.Close()
		return nil, errors.New("MCRF_CARRIER_TCP_CONGESTION")
	}
	// Keep the ordinary stream/packet path disjoint from reserved raw selectors.
	if gErr := d.stack.SetPortRange(16000, 65535); gErr != nil {
		_ = d.Close()
		return nil, errors.New("MCRF_CARRIER_STACK_PORT_RANGE")
	}
	if err = d.Start(); err != nil {
		_ = d.Close()
		return nil, err
	}
	if err = ctx.Err(); err != nil {
		_ = d.Close()
		return nil, err
	}
	go func() {
		select {
		case <-ctx.Done():
			_ = d.Close()
		case <-d.done:
		}
	}()
	return d, nil
}
