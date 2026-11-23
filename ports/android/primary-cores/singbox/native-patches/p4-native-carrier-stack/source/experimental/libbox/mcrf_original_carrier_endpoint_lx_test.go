//go:build with_lx_carrier_stack && with_lx_raw_packet && with_gvisor && (linux || android)

package libbox

import (
	"bytes"
	"context"
	"os"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"golang.org/x/sys/unix"
)

func TestMcrfOriginalCarrierNativeProofRetainsBorrowedFD(t *testing.T) {
	endpoint, _ := endpointPair(t)
	if McrfOriginalCarrierPacketSchema() != 1 {
		t.Fatal("carrier schema")
	}
	before, err := unix.FcntlInt(uintptr(endpoint.fd), unix.F_GETFL, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckMcrfOriginalCarrierPacketEndpoint(int32(endpoint.fd), "0123456789abcdef0123456789abcdef", 1, int32(os.Getpid()), "10.0.0.2", 1500); err != nil {
		t.Fatal(err)
	}
	if _, err := unix.FcntlInt(uintptr(endpoint.fd), unix.F_GETFD, 0); err != nil {
		t.Fatal("native proof closed borrowed FD", err)
	}
	after, err := unix.FcntlInt(uintptr(endpoint.fd), unix.F_GETFL, 0)
	if err != nil || before != after {
		t.Fatal("native proof mutated borrowed socket flags", err)
	}
	if err := CheckMcrfOriginalCarrierPacketEndpoint(int32(endpoint.fd), "0123456789abcdef0123456789abcdef", 1, int32(os.Getpid()+1), "10.0.0.2", 1500); err == nil {
		t.Fatal("foreign creator accepted")
	}
}

func TestMcrfOriginalCarrierEndpointPreservesNativeDFFragments(t *testing.T) {
	endpoint, peer := endpointPair(t)
	endpoint.carrierStack = true
	packet := make([]byte, 36)
	packet[0] = 0x45
	packet[3] = 36
	packet[6] = 0x60
	copy(packet[12:16], []byte{10, 0, 0, 2})
	copy(packet[16:20], []byte{198, 18, 0, 2})
	if err := validateMcrfRawIPv4(packet, endpoint.local, endpoint.mtu, false); err == nil {
		t.Fatal("raw fast-path unexpectedly accepts fragments")
	}
	state, err := endpoint.SendOriginalPacket(context.Background(), packet)
	if err != nil || state != adapter.RawPacketAccepted {
		t.Fatal("carrier fragment", state, err)
	}
	buffer := make([]byte, 1984)
	n, _, flags, _, err := unix.Recvmsg(peer, buffer, nil, 0)
	if err != nil || flags != 0 || !bytes.Equal(buffer[:n], packet) {
		t.Fatal("native carrier fragment bytes changed", err)
	}
	reply := append([]byte(nil), packet...)
	copy(reply[12:16], packet[16:20])
	copy(reply[16:20], packet[12:16])
	reply[6] = 0x40
	reply[7] = 2
	if err = unix.Sendmsg(peer, reply, nil, nil, 0); err != nil {
		t.Fatal(err)
	}
	got, err := endpoint.ReadOriginalPacket(context.Background())
	if err != nil || !bytes.Equal(got, reply) {
		t.Fatal("native carrier return fragment bytes changed", err)
	}
}
