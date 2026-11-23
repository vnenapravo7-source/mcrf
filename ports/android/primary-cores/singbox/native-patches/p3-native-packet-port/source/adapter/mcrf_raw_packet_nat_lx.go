//go:build with_lx_raw_packet

package adapter

import (
	"encoding/binary"
	"errors"
	"net/netip"
)

// RewriteOriginalIPv4 changes only the selected address/port boundary and its
// checksums. CSQTT still owns the original IP packet codec, framing and crypto.
// Ports are optional (zero preserves them). Fragmented/unsupported packets are
// refused before any modification and must never be reclassified mid-flow.
func RewriteOriginalIPv4(packet []byte, source, destination netip.Addr, sourcePort, destinationPort uint16) ([]byte, error) {
	if len(packet) < 20 || packet[0]>>4 != 4 || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) || binary.BigEndian.Uint16(packet[6:8])&0xbfff != 0 {
		return nil, errors.New("MCRF_RAW_NAT_IPV4")
	}
	header := int(packet[0]&15) * 4
	if header < 20 || header > len(packet) {
		return nil, errors.New("MCRF_RAW_NAT_HEADER")
	}
	protocol := packet[9]
	checksumOffset := 0
	switch protocol {
	case 6:
		if len(packet) < header+20 {
			return nil, errors.New("MCRF_RAW_NAT_TCP")
		}
		tcpHeader := int(packet[header+12]>>4) * 4
		if tcpHeader < 20 || header+tcpHeader > len(packet) {
			return nil, errors.New("MCRF_RAW_NAT_TCP_HEADER")
		}
		checksumOffset = header + 16
	case 17:
		if len(packet) < header+8 || int(binary.BigEndian.Uint16(packet[header+4:header+6])) != len(packet)-header {
			return nil, errors.New("MCRF_RAW_NAT_UDP")
		}
		checksumOffset = header + 6
	default:
		return nil, errors.New("MCRF_RAW_NAT_PROTOCOL")
	}
	if source.IsValid() && !source.Is4() || destination.IsValid() && !destination.Is4() {
		return nil, errors.New("MCRF_RAW_NAT_FAMILY")
	}
	result := append([]byte(nil), packet...)
	if source.IsValid() {
		a := source.As4()
		copy(result[12:16], a[:])
	}
	if destination.IsValid() {
		a := destination.As4()
		copy(result[16:20], a[:])
	}
	if sourcePort != 0 {
		binary.BigEndian.PutUint16(result[header:header+2], sourcePort)
	}
	if destinationPort != 0 {
		binary.BigEndian.PutUint16(result[header+2:header+4], destinationPort)
	}
	result[10], result[11] = 0, 0
	binary.BigEndian.PutUint16(result[10:12], rawPacketChecksum(result[:header], 0))
	// IPv4 UDP checksum zero explicitly disables it. Preserve that wire meaning.
	if protocol == 17 && binary.BigEndian.Uint16(packet[checksumOffset:checksumOffset+2]) == 0 {
		return result, nil
	}
	result[checksumOffset], result[checksumOffset+1] = 0, 0
	initial := uint32(protocol) + uint32(len(result)-header)
	for at := 12; at < 20; at += 2 {
		initial += uint32(binary.BigEndian.Uint16(result[at : at+2]))
	}
	checksum := rawPacketChecksum(result[header:], initial)
	if protocol == 17 && checksum == 0 {
		checksum = 0xffff
	}
	binary.BigEndian.PutUint16(result[checksumOffset:checksumOffset+2], checksum)
	return result, nil
}

func rawPacketChecksum(data []byte, sum uint32) uint16 {
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) == 1 {
		sum += uint32(data[0]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}
