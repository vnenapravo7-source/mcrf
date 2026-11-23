//go:build with_lx_raw_packet

package adapter

import (
	"bytes"
	"encoding/binary"
	"net/netip"
	"testing"
)

func natPacket(protocol byte, payload []byte) []byte {
	header := 20
	transport := 8
	if protocol == 6 {
		transport = 20
	}
	p := make([]byte, header+transport+len(payload))
	p[0] = 0x45
	p[8] = 64
	p[9] = protocol
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	copy(p[12:16], []byte{172, 19, 0, 2})
	copy(p[16:20], []byte{198, 18, 0, 9})
	binary.BigEndian.PutUint16(p[20:22], 50000)
	binary.BigEndian.PutUint16(p[22:24], 443)
	if protocol == 6 {
		p[32] = 0x50
		p[33] = 2
	} else {
		binary.BigEndian.PutUint16(p[24:26], uint16(transport+len(payload)))
		p[26] = 1
	}
	copy(p[header+transport:], payload)
	return p
}

func TestRewriteOriginalRoundTripAndChecksums(t *testing.T) {
	for _, proto := range []byte{6, 17} {
		for _, payload := range [][]byte{nil, []byte("odd"), []byte("even")} {
			original := natPacket(proto, payload)
			normalized, err := RewriteOriginalIPv4(original, netip.Addr{}, netip.Addr{}, 0, 0)
			if err != nil {
				t.Fatal(err)
			}
			immutable := append([]byte(nil), normalized...)
			changed, err := RewriteOriginalIPv4(normalized, netip.MustParseAddr("10.42.1.7"), netip.MustParseAddr("203.0.113.11"), 1025, 8443)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(normalized, immutable) {
				t.Fatal("input mutated")
			}
			if rawPacketChecksum(changed[:20], 0) != 0 {
				t.Fatal("IP checksum")
			}
			initial := uint32(proto) + uint32(len(changed)-20)
			for at := 12; at < 20; at += 2 {
				initial += uint32(binary.BigEndian.Uint16(changed[at : at+2]))
			}
			if rawPacketChecksum(changed[20:], initial) != 0 {
				t.Fatal("transport checksum")
			}
			restored, err := RewriteOriginalIPv4(changed, netip.MustParseAddr("172.19.0.2"), netip.MustParseAddr("198.18.0.9"), 50000, 443)
			if err != nil || !bytes.Equal(restored, normalized) {
				t.Fatal("round trip", err)
			}
		}
	}
}

func TestRewriteOriginalRejectsFragmentsAndMalformedPackets(t *testing.T) {
	p := natPacket(17, []byte("payload"))
	p[26], p[27] = 0, 0
	out, err := RewriteOriginalIPv4(p, netip.MustParseAddr("10.1.0.1"), netip.Addr{}, 1000, 0)
	if err != nil || binary.BigEndian.Uint16(out[26:28]) != 0 {
		t.Fatal("disabled UDP checksum must remain disabled")
	}
	for _, flags := range []uint16{0x2000, 1, 0x8000} {
		bad := append([]byte(nil), p...)
		binary.BigEndian.PutUint16(bad[6:8], flags)
		if _, err := RewriteOriginalIPv4(bad, netip.Addr{}, netip.Addr{}, 0, 0); err == nil {
			t.Fatalf("accepted fragmented/reserved flags %x", flags)
		}
	}
	bad := append([]byte(nil), p...)
	bad[24], bad[25] = 0, 8
	if _, err := RewriteOriginalIPv4(bad, netip.Addr{}, netip.Addr{}, 0, 0); err == nil {
		t.Fatal("accepted invalid UDP length")
	}
	if _, err := RewriteOriginalIPv4(p, netip.MustParseAddr("::1"), netip.Addr{}, 0, 0); err == nil {
		t.Fatal("accepted IPv6 rewrite")
	}
}
