//go:build with_lx_raw_packet_port && with_lx_raw_packet && (linux || android)

package socks

import (
	"context"
	"encoding/binary"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/service"
	"net/netip"
	"sync"
	"time"
)

type packetRangeProvider interface {
	adapter.OriginalPacketEndpointProvider
	OriginalPacketSelectorRange(string) (uint16, uint16)
}

// Ordinary DialContext/ListenPacket still use this SAME carrier's SOCKS bridge.
// Only the ordered native Router.PreMatch may admit original packet forwarding.
type originalPacketOutbound struct {
	adapter.Outbound
	ctx          context.Context
	stop         context.CancelFunc
	endpoint     adapter.OriginalPacketEndpoint
	start, count uint16
	mu           sync.RWMutex
	returnPath   tun.Return
	cancel       context.CancelFunc
	reader       sync.WaitGroup
	failed       bool
}

var _ adapter.FlowOutbound = (*originalPacketOutbound)(nil)
var _ tun.PortWithSelectorRange = (*originalPacketOutbound)(nil)

func wrapMcrfOriginalPacketOutbound(ctx context.Context, outbound adapter.Outbound) adapter.Outbound {
	provider, ok := service.FromContext[adapter.OriginalPacketEndpointProvider](ctx).(packetRangeProvider)
	if !ok {
		return outbound
	}
	endpoint, found := provider.OriginalPacketEndpoint(outbound.Tag())
	if !found {
		return outbound
	}
	start, count := provider.OriginalPacketSelectorRange(outbound.Tag())
	if start < 1024 || count == 0 || uint32(start)+uint32(count) > 16000 {
		return outbound
	}
	if !endpoint.LocalAddress().Is4() {
		return outbound
	}
	owned, stop := context.WithCancel(ctx)
	return &originalPacketOutbound{Outbound: outbound, ctx: owned, stop: stop, endpoint: endpoint, start: start, count: count}
}
func (o *originalPacketOutbound) PreMatchFlow(network string, destination netip.Addr) adapter.PreMatchAction {
	o.mu.RLock()
	failed := o.failed
	o.mu.RUnlock()
	if failed || network != "tcp" && network != "udp" || destination.IsValid() && !destination.Is4() {
		return adapter.PreMatchContinue
	}
	return adapter.PreMatchFlow
}
func (o *originalPacketOutbound) PortAddresses() (netip.Addr, netip.Addr) {
	return o.endpoint.LocalAddress(), netip.Addr{}
}
func (o *originalPacketOutbound) PortMTU() uint32                     { return uint32(o.endpoint.MTU()) }
func (o *originalPacketOutbound) PortSelectorRange() (uint16, uint16) { return o.start, o.count }
func (o *originalPacketOutbound) AttachReturn(path tun.Return) error {
	if path == nil || path.ReturnHeadroom() < 0 || path.ReturnHeadroom() > 256 {
		return errors.New("MCRF_RAW_RETURN_HEADROOM")
	}
	o.mu.Lock()
	if o.failed {
		o.mu.Unlock()
		return errors.New("MCRF_RAW_ENDPOINT_RETIRED")
	}
	if o.returnPath != nil {
		same := o.returnPath == path
		o.mu.Unlock()
		if same {
			return nil
		}
		return errors.New("MCRF_RAW_RETURN_ALREADY_ATTACHED")
	}
	ctx, cancel := context.WithCancel(o.ctx)
	o.cancel = cancel
	o.returnPath = path
	o.reader.Add(1)
	o.mu.Unlock()
	go o.readReplies(ctx)
	return nil
}
func (o *originalPacketOutbound) DetachReturn(path tun.Return) error {
	o.mu.Lock()
	if o.returnPath != path {
		o.mu.Unlock()
		return nil
	}
	o.returnPath = nil
	cancel := o.cancel
	o.cancel = nil
	o.failed = true
	o.mu.Unlock()
	o.stop()
	if cancel != nil {
		cancel()
	}
	o.reader.Wait()
	return nil
}
func (o *originalPacketOutbound) Close() error {
	o.mu.Lock()
	path := o.returnPath
	o.failed = true
	o.mu.Unlock()
	if path != nil {
		return o.DetachReturn(path)
	}
	o.stop()
	return nil
}
func (o *originalPacketOutbound) readReplies(ctx context.Context) {
	defer o.reader.Done()
	defer func() { o.mu.Lock(); o.failed = true; o.mu.Unlock() }()
	for {
		packet, err := o.endpoint.ReadOriginalPacket(ctx)
		if err != nil {
			return
		}
		// No reply may claim another backend's selector, even if its IPv4 matches.
		port, valid := originalPacketReplySelector(packet)
		if !valid || port < o.start || uint32(port-o.start) >= uint32(o.count) {
			continue
		}
		o.mu.RLock()
		path := o.returnPath
		if path != nil && ctx.Err() == nil {
			headroom := path.ReturnHeadroom()
			raw := make([]byte, headroom+len(packet))
			copy(raw[headroom:], packet)
			path.ReturnPackets([][]byte{raw}) // Unmatched replies are dropped, never reassigned.
		}
		o.mu.RUnlock()
	}
}

// ICMP errors quote the outbound packet, so their selector is its SOURCE port.
// Keeping those errors on the same native return path preserves PMTU feedback.
func originalPacketReplySelector(packet []byte) (uint16, bool) {
	if len(packet) < 28 || packet[0]>>4 != 4 || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) || binary.BigEndian.Uint16(packet[6:8])&0xbfff != 0 {
		return 0, false
	}
	h := int(packet[0]&15) * 4
	if h < 20 || h+8 > len(packet) {
		return 0, false
	}
	switch packet[9] {
	case 6:
		if h+20 > len(packet) || int(packet[h+12]>>4)*4 < 20 || h+int(packet[h+12]>>4)*4 > len(packet) {
			return 0, false
		}
		return binary.BigEndian.Uint16(packet[h+2 : h+4]), true
	case 17:
		if int(binary.BigEndian.Uint16(packet[h+4:h+6])) != len(packet)-h {
			return 0, false
		}
		return binary.BigEndian.Uint16(packet[h+2 : h+4]), true
	case 1:
		if packet[h] != 3 && packet[h] != 11 {
			return 0, false
		}
		quoted := packet[h+8:]
		if len(quoted) < 28 || quoted[0]>>4 != 4 || binary.BigEndian.Uint16(quoted[6:8])&0xbfff != 0 || quoted[9] != 6 && quoted[9] != 17 {
			return 0, false
		}
		inner := int(quoted[0]&15) * 4
		if inner < 20 || inner+8 > len(quoted) || int(binary.BigEndian.Uint16(quoted[2:4])) < inner+8 {
			return 0, false
		}
		return binary.BigEndian.Uint16(quoted[inner : inner+2]), true
	}
	return 0, false
}
func (o *originalPacketOutbound) WritePackets(packets [][]byte) error {
	// ForwardDispatcher holds its read fence while flushing. A bounded batch
	// avoids a full socket preventing dispatcher Close from reaching DetachReturn.
	// Congestion drops this batch in the native port layer, never reroutes it.
	ctx, cancel := context.WithTimeout(o.ctx, 50*time.Millisecond)
	defer cancel()
	for _, packet := range packets {
		for {
			if err := ctx.Err(); err != nil {
				return err
			}
			o.mu.RLock()
			live := !o.failed && o.returnPath != nil
			o.mu.RUnlock()
			if !live {
				return errors.New("MCRF_RAW_ENDPOINT_RETIRED")
			}
			state, err := o.endpoint.SendOriginalPacket(ctx, packet)
			if err != nil {
				return err
			}
			if state == adapter.RawPacketAccepted {
				break
			}
			if state != adapter.RawPacketWouldBlock {
				return errors.New("MCRF_RAW_PACKET_REJECTED")
			}
			if writable, ok := o.endpoint.(adapter.OriginalPacketWritable); ok {
				if err := writable.WaitOriginalPacketWritable(ctx); err != nil {
					return err
				}
			} else {
				timer := time.NewTimer(10 * time.Millisecond)
				select {
				case <-ctx.Done():
					timer.Stop()
					return ctx.Err()
				case <-timer.C:
				}
			}
		}
	}
	return nil
}
