package main

import (
	wgtun "github.com/amnezia-vpn/amneziawg-go/tun"
	"net"
	"testing"
)

type auditSink struct {
	wgtun.Device
	writes int
}

func (s *auditSink) Write(packets [][]byte, offset int) (int, error) {
	s.writes += len(packets)
	return len(packets), nil
}

func TestAuditSeesBlockedDestinationsBeforeStackAndStillForwards(t *testing.T) {
	v4 := make([]byte, 20)
	v4[0] = 0x45
	copy(v4[16:], net.IPv4(10, 77, 0, 3).To4())
	v6 := make([]byte, 40)
	v6[0] = 0x60
	copy(v6[24:], net.ParseIP("fd77::3").To16())
	sink := &auditSink{}
	audit := &auditedTun{Device: sink}
	packets := [][]byte{append([]byte{0, 0}, v4...), append([]byte{0, 0}, v6...)}
	if n, e := audit.Write(packets, 2); e != nil || n != 2 {
		t.Fatal("audit changed forwarding")
	}
	if audit.blocked.Load() != 2 || audit.decrypted.Load() != 2 || sink.writes != 2 {
		t.Fatal("audit counters missing decrypted traffic")
	}
	v4[19] = 1
	v6[39] = 1
	if blockedFixturePacket(v4) || blockedFixturePacket(v6) || blockedFixturePacket(nil) || blockedFixturePacket([]byte{0x45}) {
		t.Fatal("audit confused permitted or truncated packet")
	}
}
