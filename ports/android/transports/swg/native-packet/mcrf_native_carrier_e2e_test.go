//go:build (linux || android) && with_gvisor && with_lx_raw_packet && mcrf_native_carrier_fixture

package csqtt

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

	"github.com/sagernet/gvisor/pkg/buffer"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/link/channel"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	W "github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing-tun"
	M "github.com/sagernet/sing/common/metadata"
)

// An owned original-IP UDP dispatcher replaces only the remote service. Actual
// native gVisor, SCM_RIGHTS into a separate Go child, RelayIP and UDP sockets are
// exercised together. No TURN/provider traffic, crypto claim or speed test.
func TestMcrfNativeCarrierAcrossOwnedGoProcessAndUDP(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	link := channel.New(256, 1300, "")
	remote, err := tun.NewGVisorStackWithOptions(link, stack.NICOptions{}, true)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		remote.Close()
		for _, endpoint := range remote.CleanupEndpoints() {
			endpoint.Abort()
		}
		remote.Wait()
		link.Close()
	}()
	if e := remote.AddProtocolAddress(tun.DefaultNIC, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: tcpip.AddressWithPrefix{Address: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), PrefixLen: 32}}, stack.AddressProperties{}); e != nil {
		t.Fatal(e)
	}
	dispatcher, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer dispatcher.Close()
	var received atomic.Uint32
	var lastNativeFlags atomic.Uint32
	var addressMu sync.Mutex
	var returnAddress *net.UDPAddr
	var pumps sync.WaitGroup
	pumps.Add(2)
	go func() {
		defer pumps.Done()
		for {
			data := make([]byte, 65535)
			n, address, e := dispatcher.ReadFromUDP(data)
			if e != nil {
				return
			}
			received.Add(1)
			addressMu.Lock()
			returnAddress = address
			addressMu.Unlock()
			packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data[:n])})
			link.InjectInbound(ipv4.ProtocolNumber, packet)
			packet.DecRef()
		}
	}()
	go func() {
		defer pumps.Done()
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
			addressMu.Lock()
			address := returnAddress
			addressMu.Unlock()
			if address != nil {
				if _, e := dispatcher.WriteToUDP(data, address); e != nil {
					return
				}
			}
		}
	}()
	command, control, peer, owner, output := originalPacketControlFixture(t, "relay@"+dispatcher.LocalAddr().String())
	device, err := W.NewMcrfCarrierStack(ctx, netip.MustParseAddr("198.18.0.1"), 1300)
	if err != nil {
		t.Fatal(err)
	}
	defer device.Close()
	pumps.Add(2)
	go func() {
		defer pumps.Done()
		for {
			data := make([]byte, 65535)
			sizes := []int{0}
			n, e := device.Read([][]byte{data}, sizes, 0)
			if n > 0 {
				if sizes[0] >= 20 {
					lastNativeFlags.Store(uint32(data[6])<<8 | uint32(data[7]))
				}
				if _, writeError := peer.Write(data[:sizes[0]]); writeError != nil {
					return
				}
			}
			if e != nil {
				return
			}
		}
	}()
	go func() {
		defer pumps.Done()
		for {
			data := make([]byte, 65535)
			n, e := peer.Read(data)
			if e != nil {
				return
			}
			if _, e = device.Write([][]byte{data[:n]}, 0); e != nil {
				return
			}
		}
	}()
	defer func() {
		cancel()
		_ = device.Close()
		_ = peer.Close()
		_ = dispatcher.Close()
		done := make(chan struct{})
		go func() { pumps.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("owned native/UDP pumps did not retire")
		}
	}()
	// Ownership ACK only after the receiving native device and packet pumps exist.
	if _, err = io.WriteString(control, "MCRF_PACKET_OWNED|"+owner.Nonce+"|17\n"); err != nil {
		t.Fatal(err)
	}
	listener, err := gonet.ListenTCP(remote, tcpip.FullAddress{NIC: tun.DefaultNIC, Addr: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), Port: 18080}, ipv4.ProtocolNumber)
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
		_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
		data := make([]byte, 64)
		_, e = io.ReadFull(connection, data)
		if e == nil {
			_, e = connection.Write(data)
		}
		echo <- e
	}()
	connection, err := device.DialContext(ctx, "tcp", M.ParseSocksaddr("198.18.0.2:18080"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
	request := bytes.Repeat([]byte{0x72}, 64)
	if _, err = connection.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 64)
	if _, err = io.ReadFull(connection, reply); err != nil || !bytes.Equal(reply, request) {
		t.Fatal("native/child/UDP TCP mismatch", err)
	}
	if err = <-echo; err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	udp, err := gonet.DialUDP(remote, &tcpip.FullAddress{NIC: tun.DefaultNIC, Addr: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), Port: 18081}, nil, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	_ = udp.SetDeadline(time.Now().Add(3 * time.Second))
	client, err := device.DialContext(ctx, "udp", M.ParseSocksaddr("198.18.0.2:18081"))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	large := bytes.Repeat([]byte{0x5a, 0xc3, 0x81, 0x02}, 1024)
	if _, err = client.Write(large); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 8192)
	n, address, err := udp.ReadFrom(data)
	if err != nil || !bytes.Equal(data[:n], large) {
		_ = device.Close()
		_ = peer.Close()
		childError := command.Wait()
		t.Fatal("native/child/UDP fragmented request", err, "native IP flags/offset", lastNativeFlags.Load(), "child", childError, output.String())
	}
	if _, err = udp.WriteTo(data[:n], address); err != nil {
		t.Fatal(err)
	}
	n, err = client.Read(data)
	if err != nil || !bytes.Equal(data[:n], large) {
		t.Fatal("native/child/UDP fragmented reply", err)
	}
	if received.Load() < 5 {
		t.Fatal("owned UDP dispatcher was bypassed")
	}
	_ = client.Close()
	_ = udp.Close()
	_ = device.Close()
	_ = peer.Close()
	if err = command.Wait(); err != nil {
		t.Fatal("Go packet relay child did not retire", err, output.String())
	}
}
