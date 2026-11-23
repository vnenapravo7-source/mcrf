package wgbackend

import (
	"encoding/binary"
	"testing"
	"time"
)

type packetOwnerFunc func(int32, string, int32, string, int32) bool

func (f packetOwnerFunc) AdmitPacketOwner(protocol int32, source string, sourcePort int32, destination string, destinationPort int32) bool {
	return f(protocol, source, sourcePort, destination, destinationPort)
}

func ownerTestPacket(protocol, flags byte) []byte {
	length := 40
	if protocol == 17 {
		length = 28
	}
	packet := make([]byte, length)
	packet[0], packet[9] = 0x45, protocol
	binary.BigEndian.PutUint16(packet[2:4], uint16(length))
	copy(packet[12:20], []byte{10, 0, 0, 1, 192, 0, 2, 1})
	binary.BigEndian.PutUint16(packet[20:22], 45000)
	binary.BigEndian.PutUint16(packet[22:24], 443)
	if protocol == 17 {
		binary.BigEndian.PutUint16(packet[24:26], 8)
	} else {
		packet[32], packet[33] = 0x50, flags
	}
	return packet
}

func TestOwnerGateChecksTcpReuseUdpAndUnknownOwner(t *testing.T) {
	permitted, calls := true, 0
	gate := ownerGate{lookup: packetOwnerFunc(func(protocol int32, source string, sourcePort int32, destination string, destinationPort int32) bool {
		calls++
		if protocol != 6 && protocol != 17 || source != "10.0.0.1" || sourcePort != 45000 || destination != "192.0.2.1" || destinationPort != 443 {
			t.Fatalf("unexpected connection owner lookup tuple")
		}
		return permitted
	})}
	start := time.Unix(1, 0)
	if !gate.admit(ownerTestPacket(6, 0x02), start) || !gate.admit(ownerTestPacket(6, 0x10), start) || calls != 1 {
		t.Fatal("TCP did not use the generation-local association")
	}
	permitted = false
	if gate.admit(ownerTestPacket(6, 0x02), start) || gate.admit(ownerTestPacket(6, 0x10), start) || calls != 3 {
		t.Fatal("reused TCP tuple inherited admission")
	}
	permitted = true
	if !gate.admit(ownerTestPacket(17, 0), start) {
		t.Fatal("owned UDP packet was blocked")
	}
	permitted = false
	if gate.admit(ownerTestPacket(17, 0), start) || calls != 5 {
		t.Fatal("reused UDP/QUIC tuple inherited admission")
	}
}

func TestOwnerGateRejectsFragmentsAndOtherProtocols(t *testing.T) {
	gate := ownerGate{lookup: packetOwnerFunc(func(int32, string, int32, string, int32) bool {
		t.Fatal("invalid packet reached owner lookup")
		return false
	})}
	now := time.Unix(1, 0)
	fragment := ownerTestPacket(6, 2)
	fragment[6] = 0x20
	if gate.admit(fragment, now) || gate.admit(ownerTestPacket(1, 0), now) {
		t.Fatal("unprovable packet passed admission")
	}
	ipv6 := make([]byte, 56)
	ipv6[0], ipv6[6] = 0x60, 44
	binary.BigEndian.PutUint16(ipv6[4:6], 16)
	if gate.admit(ipv6, now) || gate.admit(ipv6[:20], now) {
		t.Fatal("IPv6 fragment or malformed packet passed admission")
	}
}

func TestOwnerGateIpv6UdpWithDestinationOptionsAndNewGeneration(t *testing.T) {
	packet := make([]byte, 56)
	packet[0], packet[6], packet[7] = 0x60, 60, 64
	binary.BigEndian.PutUint16(packet[4:6], 16)
	copy(packet[8:24], []byte{0x20, 1, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})
	copy(packet[24:40], []byte{0x20, 1, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2})
	packet[40] = 17
	binary.BigEndian.PutUint16(packet[48:50], 45000)
	binary.BigEndian.PutUint16(packet[50:52], 53)
	binary.BigEndian.PutUint16(packet[52:54], 8)
	gate := ownerGate{lookup: packetOwnerFunc(func(protocol int32, source string, sourcePort int32, destination string, destinationPort int32) bool {
		return protocol == 17 && source == "2001:db8::1" && destination == "2001:db8::2" &&
			sourcePort == 45000 && destinationPort == 53
	})}
	now := time.Unix(1, 0)
	if !gate.admit(packet, now) {
		t.Fatal("owned IPv6 UDP with a destination-options header was blocked")
	}
	const lookupTime = 1
	permitted := ownerGate{lookup: packetOwnerFunc(func(int32, string, int32, string, int32) bool { return true })}
	if !permitted.admit(ownerTestPacket(6, 0x02), time.Unix(lookupTime, 0)) {
		t.Fatal("old generation did not admit its own SYN")
	}
	changed := ownerGate{lookup: packetOwnerFunc(func(int32, string, int32, string, int32) bool { return false })}
	if changed.admit(ownerTestPacket(6, 0x10), time.Unix(lookupTime, 1)) {
		t.Fatal("new generation inherited the old generation's cached TCP verdict")
	}
}
