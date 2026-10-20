package main

import (
	"bytes"
	"net"
	"sync/atomic"

	wgtun "github.com/amnezia-vpn/amneziawg-go/tun"
)

// Observe decrypted packets before the independent peer's IP stack can discard
// unknown destinations. Absence of an HTTP response alone is not a BLOCK proof.
type auditedTun struct {
	wgtun.Device
	decrypted atomic.Int64
	blocked   atomic.Int64
	internalDNS atomic.Int64
}

func blockedFixturePacket(packet []byte) bool {
	if len(packet) == 0 {
		return false
	}
	switch packet[0] >> 4 {
	case 4:
		return len(packet) >= 20 && bytes.Equal(packet[16:20], net.IPv4(10, 77, 0, 3).To4())
	case 6:
		return len(packet) >= 40 && bytes.Equal(packet[24:40], net.ParseIP("fd77::3").To16())
	}
	return false
}

func (t *auditedTun) Write(packets [][]byte, offset int) (int, error) {
	for _, packet := range packets {
		if offset < 0 || offset >= len(packet) {
			continue
		}
		t.decrypted.Add(1)
		if internalDNSFixturePacket(packet[offset:]){t.internalDNS.Add(1)}
		if blockedFixturePacket(packet[offset:]) {
			t.blocked.Add(1)
		}
	}
	return t.Device.Write(packets, offset)
}
func internalDNSFixturePacket(packet []byte)bool{
	if len(packet)>=20&&packet[0]>>4==4{return bytes.Equal(packet[12:16],net.IPv4(198,19,255,53).To4())||bytes.Equal(packet[16:20],net.IPv4(198,19,255,53).To4())}
	alias:=net.ParseIP("fdfe:dcba:9876:dd54::53").To16()
	return len(packet)>=40&&packet[0]>>4==6&&(bytes.Equal(packet[8:24],alias)||bytes.Equal(packet[24:40],alias))
}
