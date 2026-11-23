//go:build with_lx_carrier_stack && with_lx_raw_packet && with_gvisor && (linux || android)

package socks

import (
	"context"
	"errors"
	"net"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/iponly"
	W "github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// Deliberately NOT FlowOutbound: the ordinary native router retains sniffing,
// UID/domain/IP rules and connection ownership. No ordinary SOCKS fallback is
// allowed once this same-session whole-IP endpoint has been selected.
type originalCarrierStackOutbound struct {
	*Outbound
	ctx      context.Context
	cancel   context.CancelFunc
	endpoint adapter.OriginalPacketEndpoint
	device   W.Device
	provider adapter.OriginalPacketEndpointProvider
	stopOnce sync.Once
	workers  sync.WaitGroup
}

func wrapMcrfCarrierStackOutbound(ctx context.Context, base *Outbound) (adapter.Outbound, error, bool) {
	provider := service.FromContext[adapter.OriginalPacketEndpointProvider](ctx)
	if provider == nil {
		return nil, nil, false
	}
	endpoint, found := provider.OriginalPacketEndpoint(base.Tag())
	if !found {
		return nil, nil, false
	}
	carrier, enabled := endpoint.(interface{ OriginalCarrierStack() bool })
	if !enabled || !carrier.OriginalCarrierStack() {
		return nil, nil, false
	}
	if base.uotClient != nil {
		return nil, errors.New("MCRF_ORIGINAL_CARRIER_UOT_UNSUPPORTED"), true
	}
	if len(base.Dependencies()) != 0 || base.resolve {
		return nil, errors.New("MCRF_ORIGINAL_CARRIER_DIAL_OPTIONS_UNSUPPORTED"), true
	}
	owned, cancel := context.WithCancel(ctx)
	device, err := W.NewMcrfCarrierStack(owned, endpoint.LocalAddress(), uint32(endpoint.MTU()))
	if err != nil {
		cancel()
		return nil, err, true
	}
	o := &originalCarrierStackOutbound{Outbound: base, ctx: owned, cancel: cancel, endpoint: endpoint, device: device, provider: provider}
	o.workers.Add(2)
	go o.sendPackets()
	go o.readPackets()
	return o, nil, true
}

func (o *originalCarrierStackOutbound) stopTransport() {
	o.stopOnce.Do(func() {
		o.cancel()
		if closer, ok := o.endpoint.(interface{ Close() error }); ok {
			_ = closer.Close()
		}
		_ = o.device.Close()
	})
}
func (o *originalCarrierStackOutbound) failed() {
	unexpected := o.ctx.Err() == nil
	o.stopTransport()
	if unexpected {
		if reporter, ok := o.provider.(interface{ ReportOriginalCarrierFailure() }); ok {
			go reporter.ReportOriginalCarrierFailure()
		}
	}
}
func (o *originalCarrierStackOutbound) Close() error { o.stopTransport(); o.workers.Wait(); return nil }

func (o *originalCarrierStackOutbound) sendPackets() {
	defer o.workers.Done()
	// Read copies into caller storage and SendOriginalPacket synchronously
	// copies into the owned SEQPACKET socket before Accepted. Keep one buffer
	// alive across WouldBlock/retry; reuse only after that packet is accepted.
	// The extra byte preserves oversized-packet rejection instead of truncation.
	storage := make([]byte, o.endpoint.MTU()+1)
	buffers := [][]byte{storage}
	sizes := []int{0}
	for {
		n, err := o.device.Read(buffers, sizes, 0)
		if n > 0 {
			if n != 1 || sizes[0] < 20 || sizes[0] > o.endpoint.MTU() {
				o.failed()
				return
			}
			packet := storage[:sizes[0]]
			for {
				state, e := o.endpoint.SendOriginalPacket(o.ctx, packet)
				if e != nil {
					o.failed()
					return
				}
				if state == adapter.RawPacketAccepted {
					break
				}
				if state != adapter.RawPacketWouldBlock {
					o.failed()
					return
				}
				writable, ok := o.endpoint.(adapter.OriginalPacketWritable)
				if !ok {
					o.failed()
					return
				}
				if e = writable.WaitOriginalPacketWritable(o.ctx); e != nil {
					o.failed()
					return
				}
			}
		}
		if err != nil {
			o.failed()
			return
		}
	}
}
func (o *originalCarrierStackOutbound) readPackets() {
	defer o.workers.Done()
	for {
		packet, err := o.endpoint.ReadOriginalPacket(o.ctx)
		if err != nil || len(packet) == 0 {
			o.failed()
			return
		}
		if _, err = o.device.Write([][]byte{packet}, 0); err != nil {
			o.failed()
			return
		}
	}
}

func (o *originalCarrierStackOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if err := o.ctx.Err(); err != nil {
		return nil, err
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	if destination.IsDomain() {
		addresses, err := o.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		return N.DialSerial(ctx, o.device, network, destination, addresses)
	}
	if !destination.Addr.IsValid() {
		return nil, errors.New("MCRF_ORIGINAL_CARRIER_DESTINATION")
	}
	return o.device.DialContext(ctx, network, destination)
}
func (o *originalCarrierStackOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if err := o.ctx.Err(); err != nil {
		return nil, err
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	if destination.IsDomain() {
		addresses, err := o.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		connection, address, err := N.ListenSerial(ctx, o.device, destination, addresses)
		if err != nil {
			return nil, err
		}
		connection = iponly.NewPacketConn(o.logger, connection)
		if address.IsValid() && destination != M.SocksaddrFrom(address, destination.Port) {
			return bufio.NewNATPacketConn(bufio.NewPacketConn(connection), M.SocksaddrFrom(address, destination.Port), destination), nil
		}
		return connection, nil
	}
	if !destination.Addr.IsValid() {
		return nil, errors.New("MCRF_ORIGINAL_CARRIER_DESTINATION")
	}
	connection, err := o.device.ListenPacket(ctx, destination)
	if err != nil {
		return nil, err
	}
	return iponly.NewPacketConn(o.logger, connection), nil
}
