//go:build (linux || android) && with_gvisor && with_lx_raw_packet && mcrf_native_carrier_fixture

package csqtt

import (
	"context"
	"github.com/sagernet/gvisor/pkg/buffer"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/link/channel"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/sing-tun"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"testing"
)

// Test-only remote IP peer. The installed module owns the client stack; this
// child retains the real FD/RelayIP/UDP path and supplies an isolated echo peer.
func TestMcrfModuleCarrierChild(t *testing.T) {
	if os.Getenv("MCRF_ORIGINAL_PACKET_CHILD") == "" {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	link := channel.New(256, 1300, "")
	remote, err := tun.NewGVisorStackWithOptions(link, stack.NICOptions{}, true)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		remote.Close()
		for _, ep := range remote.CleanupEndpoints() {
			ep.Abort()
		}
		remote.Wait()
		link.Close()
	}()
	address := tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2"))
	if e := remote.AddProtocolAddress(tun.DefaultNIC, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: tcpip.AddressWithPrefix{Address: address, PrefixLen: 32}}, stack.AddressProperties{}); e != nil {
		t.Fatal(e)
	}
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	listener, err := gonet.ListenTCP(remote, tcpip.FullAddress{NIC: tun.DefaultNIC, Addr: address, Port: 18080}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	echo, err := gonet.DialUDP(remote, &tcpip.FullAddress{NIC: tun.DefaultNIC, Addr: address, Port: 18081}, nil, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	var mu sync.Mutex
	var destination *net.UDPAddr
	go func() {
		for {
			data := make([]byte, 65535)
			n, peer, e := udp.ReadFromUDP(data)
			if e != nil {
				return
			}
			mu.Lock()
			destination = peer
			mu.Unlock()
			packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data[:n])})
			link.InjectInbound(ipv4.ProtocolNumber, packet)
			packet.DecRef()
		}
	}()
	go func() {
		for {
			packet := link.ReadContext(ctx)
			if packet == nil {
				return
			}
			var data []byte
			for _, part := range packet.AsSlices() {
				data = append(data, part...)
			}
			packet.DecRef()
			mu.Lock()
			peer := destination
			mu.Unlock()
			if peer != nil {
				if _, e := udp.WriteToUDP(data, peer); e != nil {
					return
				}
			}
		}
	}()
	go func() {
		for {
			conn, e := listener.Accept()
			if e != nil {
				return
			}
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(c, c) }(conn)
		}
	}()
	go func() {
		data := make([]byte, 8192)
		for {
			n, peer, e := echo.ReadFrom(data)
			if e != nil {
				return
			}
			if _, e = echo.WriteTo(data[:n], peer); e != nil {
				return
			}
		}
	}()
	if err = os.Setenv("MCRF_ORIGINAL_PACKET_TEST_MODE", "relay@"+udp.LocalAddr().String()); err != nil {
		t.Fatal(err)
	}
	TestMcrfOriginalPacketPortChild(t)
}
