//go:build with_gvisor && with_lx_raw_packet

package wireguard

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
	"github.com/sagernet/sing-tun"
	M "github.com/sagernet/sing/common/metadata"
)

func TestMcrfNativeCarrierStackTCPAndFragmentedUDP(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	left, err := NewMcrfCarrierStack(ctx, netip.MustParseAddr("198.18.0.1"), 1300)
	if err != nil {
		t.Fatal(err)
	}
	right, err := NewMcrfCarrierStack(ctx, netip.MustParseAddr("198.18.0.2"), 1300)
	if err != nil {
		_ = left.Close()
		t.Fatal(err)
	}
	var pumps sync.WaitGroup
	var fragments atomic.Uint32
	var oversized atomic.Bool
	for _, pair := range [][2]Device{{left, right}, {right, left}} {
		pumps.Add(1)
		go func(from, to Device) {
			defer pumps.Done()
			for {
				packet := make([]byte, 65535)
				sizes := []int{0}
				n, readErr := from.Read([][]byte{packet}, sizes, 0)
				if n > 0 {
					if sizes[0] > 1300 {
						oversized.Store(true)
					}
					if sizes[0] >= 20 && (packet[6]&0x3f != 0 || packet[7] != 0) {
						fragments.Add(1)
					}
					if _, e := to.Write([][]byte{packet[:sizes[0]]}, 0); e != nil {
						return
					}
				}
				if readErr != nil {
					return
				}
			}
		}(pair[0], pair[1])
	}
	defer func() {
		_ = left.Close()
		_ = right.Close()
		done := make(chan struct{})
		go func() { pumps.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("native packet pumps did not retire")
		}
	}()

	listener, err := gonet.ListenTCP(right.(*stackDevice).stack, tcpip.FullAddress{
		NIC: tun.DefaultNIC, Addr: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), Port: 18080,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	echo := make(chan error, 1)
	go func() {
		connection, e := listener.Accept()
		if e != nil {
			echo <- e
			return
		}
		defer connection.Close()
		_ = connection.SetDeadline(time.Now().Add(5 * time.Second))
		payload := make([]byte, 64)
		_, e = io.ReadFull(connection, payload)
		if e == nil {
			_, e = connection.Write(payload)
		}
		echo <- e
	}()
	connection, err := left.DialContext(ctx, "tcp", M.ParseSocksaddr("198.18.0.2:18080"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if connection.LocalAddr().(*net.TCPAddr).Port < 16000 {
		t.Fatal("native TCP collided with raw selectors")
	}
	_ = connection.SetDeadline(time.Now().Add(5 * time.Second))
	payload := bytes.Repeat([]byte{0x5a}, 64)
	if _, err = connection.Write(payload); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, len(payload))
	if _, err = io.ReadFull(connection, reply); err != nil || !bytes.Equal(reply, payload) {
		t.Fatal("native TCP reply mismatch", err)
	}
	if err = <-echo; err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()

	peer, err := right.ListenPacket(ctx, M.ParseSocksaddr("198.18.0.1:1"))
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	port := peer.LocalAddr().(*net.UDPAddr).Port
	if port < 16000 {
		t.Fatal("native UDP collided with raw selectors")
	}
	client, err := left.DialContext(ctx, "udp", M.SocksaddrFrom(netip.MustParseAddr("198.18.0.2"), uint16(port)))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_ = peer.SetDeadline(time.Now().Add(5 * time.Second))
	// Larger than the carrier MTU: real native IPv4 fragmentation/reassembly,
	// never migrating a pinned flow into a different SOCKS/netstack backend.
	large := bytes.Repeat([]byte{0x19, 0x83, 0x42, 0xa5}, 1024)
	if _, err = client.Write(large); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 8192)
	n, address, err := peer.ReadFrom(buffer)
	if err != nil || !bytes.Equal(buffer[:n], large) {
		t.Fatal("fragmented UDP request mismatch", err)
	}
	if _, err = peer.WriteTo(buffer[:n], address); err != nil {
		t.Fatal(err)
	}
	n, err = client.Read(buffer)
	if err != nil || !bytes.Equal(buffer[:n], large) {
		t.Fatal("fragmented UDP reply mismatch", err)
	}
	if fragments.Load() < 4 || oversized.Load() {
		t.Fatal("native packet path did not fragment within carrier MTU")
	}
}

func TestMcrfNativeCarrierStackRejectsInvalidOrCancelledOwner(t *testing.T) {
	for _, address := range []string{"::1", "0.0.0.0", "127.0.0.1", "224.0.0.1"} {
		if d, err := NewMcrfCarrierStack(context.Background(), netip.MustParseAddr(address), 1300); err == nil {
			_ = d.Close()
			t.Fatal("invalid carrier address accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if d, err := NewMcrfCarrierStack(ctx, netip.MustParseAddr("198.18.0.1"), 1300); err == nil {
		_ = d.Close()
		t.Fatal("cancelled owner accepted")
	}
}

func TestMcrfNativeCarrierStackCancellationClosesBlockedRead(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	d, err := NewMcrfCarrierStack(ctx, netip.MustParseAddr("198.18.0.1"), 1300)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	defer d.Close()
	read := make(chan error, 1)
	go func() { _, e := d.Read([][]byte{make([]byte, 1984)}, []int{0}, 0); read <- e }()
	cancel()
	select {
	case e := <-read:
		if e == nil {
			t.Fatal("cancelled packet reader succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("owner cancellation did not retire native stack")
	}
}

// Query real constructed stacks. The third NewGVisorStackWithOptions argument
// is allowRawEndpoint, NOT isLocalStack; guard against misreading that API.
func TestMcrfCarrierTCPPolicyAndUnchangedNetworkBuffers(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	options := DeviceOptions{Context: ctx, MTU: 1300, Address: []netip.Prefix{netip.MustParsePrefix("198.18.0.3/32")}}
	baseline, err := newStackDevice(options)
	if err != nil {
		t.Fatal(err)
	}
	defer baseline.Close()
	upgraded, err := NewMcrfCarrierStack(ctx, netip.MustParseAddr("198.18.0.4"), 1300)
	if err != nil {
		t.Fatal(err)
	}
	defer upgraded.Close()
	for _, example := range []struct {
		name       string
		device     *stackDevice
		congestion string
	}{
		{"upstream-control", baseline, "reno"}, {"carrier", upgraded.(*stackDevice), "cubic"},
	} {
		var send tcpip.TCPSendBufferSizeRangeOption
		var receive tcpip.TCPReceiveBufferSizeRangeOption
		var cc tcpip.CongestionControlOption
		if e := example.device.stack.TransportProtocolOption(tcp.ProtocolNumber, &send); e != nil {
			t.Fatal(e)
		}
		if e := example.device.stack.TransportProtocolOption(tcp.ProtocolNumber, &receive); e != nil {
			t.Fatal(e)
		}
		if e := example.device.stack.TransportProtocolOption(tcp.ProtocolNumber, &cc); e != nil {
			t.Fatal(e)
		}
		if send.Default != 1<<20 || send.Max != 6<<20 || receive.Default != 1<<20 || receive.Max != 8<<20 {
			t.Fatalf("%s unexpected network buffers: send=%+v receive=%+v", example.name, send, receive)
		}
		if string(cc) != example.congestion {
			t.Fatalf("%s congestion=%s", example.name, cc)
		}
		t.Logf("%s send=%+v receive=%+v congestion=%s", example.name, send, receive, cc)
	}
}
