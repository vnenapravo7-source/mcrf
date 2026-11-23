package wgbackend

import (
	"encoding/binary"
	"net/netip"
	"sync"
	"time"
)

// PacketOwner is supplied by the host that owns this generation of the VPN.
type PacketOwner interface {
	AdmitPacketOwner(protocol int32, source string, sourcePort int32, destination string, destinationPort int32) bool
}

type ownerFlow struct {
	protocol            byte
	source, destination netip.AddrPort
}

type ownerGate struct {
	lookup PacketOwner
	mu     sync.Mutex
	tcp    map[ownerFlow]time.Time
}

func (g *ownerGate) admit(packet []byte, now time.Time) bool {
	flow, flags, ok := ownerTuple(packet)
	if !ok {
		return false
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	const idle = 2 * time.Minute
	if flow.protocol == 6 {
		// A new SYN can reuse a previous socket's entire tuple. Always
		// look it up and discard the old decision before consulting Android.
		if expiry, exists := g.tcp[flow]; exists && flags&0x02 == 0 && now.Before(expiry) {
			if flags&0x04 != 0 {
				delete(g.tcp, flow)
			} else {
				g.tcp[flow] = now.Add(idle)
			}
			return true
		}
		delete(g.tcp, flow)
	}
	if !g.lookup.AdmitPacketOwner(int32(flow.protocol), flow.source.Addr().String(), int32(flow.source.Port()),
		flow.destination.Addr().String(), int32(flow.destination.Port())) {
		return false
	}
	// A datagram can be the only packet sent with its port, and UDP socket
	// reuse has no SYN to invalidate a stale decision.
	if flow.protocol == 6 && flags&0x04 == 0 {
		if g.tcp == nil {
			g.tcp = make(map[ownerFlow]time.Time)
		}
		if len(g.tcp) >= 4096 {
			for key, expiry := range g.tcp {
				if !now.Before(expiry) {
					delete(g.tcp, key)
				}
			}
		}
		if len(g.tcp) < 4096 {
			g.tcp[flow] = now.Add(idle)
		}
	}
	return true
}

func ownerTuple(packet []byte) (flow ownerFlow, flags byte, ok bool) {
	if len(packet) < 20 {
		return
	}
	var source, destination netip.Addr
	var header int
	switch packet[0] >> 4 {
	case 4:
		header = int(packet[0]&15) * 4
		if header < 20 || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) ||
			binary.BigEndian.Uint16(packet[6:8])&0x3fff != 0 {
			return
		}
		source = netip.AddrFrom4([4]byte(packet[12:16]))
		destination = netip.AddrFrom4([4]byte(packet[16:20]))
		flow.protocol = packet[9]
	case 6:
		if len(packet) < 40 || int(binary.BigEndian.Uint16(packet[4:6])) != len(packet)-40 {
			return
		}
		header = 40
		source = netip.AddrFrom16([16]byte(packet[8:24]))
		destination = netip.AddrFrom16([16]byte(packet[24:40]))
		flow.protocol = packet[6]
		for i := 0; flow.protocol == 0 || flow.protocol == 43 || flow.protocol == 60; i++ {
			if i == 8 || len(packet)-header < 8 {
				return
			}
			flow.protocol = packet[header]
			header += (int(packet[header+1]) + 1) * 8
		}
	default:
		return
	}
	if header > len(packet) {
		return
	}
	transport := packet[header:]
	switch flow.protocol {
	case 6:
		if len(transport) < 20 || transport[12]>>4 < 5 || int(transport[12]>>4)*4 > len(transport) {
			return
		}
		flags = transport[13]
	case 17:
		if len(transport) < 8 || int(binary.BigEndian.Uint16(transport[4:6])) != len(transport) {
			return
		}
	default:
		return
	}
	sourcePort := binary.BigEndian.Uint16(transport[:2])
	destinationPort := binary.BigEndian.Uint16(transport[2:4])
	if sourcePort == 0 || destinationPort == 0 {
		return
	}
	flow.source = netip.AddrPortFrom(source, sourcePort)
	flow.destination = netip.AddrPortFrom(destination, destinationPort)
	return flow, flags, true
}
