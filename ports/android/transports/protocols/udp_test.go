// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type fixturePacket struct{ *net.UDPConn }

func (c fixturePacket) WriteTo(p []byte, a net.Addr) (int, error) {
	v := M.SocksaddrFromNet(a)
	if !v.Addr.IsValid() {
		return 0, errLocal
	}
	return c.WriteToUDP(p, net.UDPAddrFromAddrPort(netip.AddrPortFrom(v.Addr, v.Port)))
}

type fixtureDialer struct{ opens atomic.Int32 }

func (d *fixtureDialer) DialContext(ctx context.Context, network string, a M.Socksaddr) (net.Conn, error) {
	return nil, errLocal
}
func (d *fixtureDialer) ListenPacket(ctx context.Context, a M.Socksaddr) (net.PacketConn, error) {
	d.opens.Add(1)
	c, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		return nil, e
	}
	return fixturePacket{c}, nil
}
func TestMcrfProtocolsUdpSourcePortPin(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	diag := &diagnostics{output: io.Discard}
	dialer := &fixtureDialer{}
	ln, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer ln.Close()
	done := make(chan error, 1)
	ready := make(chan struct{})
	user, password := strings.Repeat("a", 32), strings.Repeat("b", 32)
	go func() { done <- serve(ctx, ln, user, password, dialer, true, diag, func() { close(ready) }) }()
	<-ready
	sender, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer sender.Close()
	rogue, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer rogue.Close()
	echo, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		p := make([]byte, 100)
		for {
			n, from, e := echo.ReadFromUDP(p)
			if e != nil {
				return
			}
			echo.WriteToUDP(p[:n], from)
		}
	}()
	control, e := net.DialTimeout("tcp", ln.Addr().String(), time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer control.Close()
	control.SetDeadline(time.Now().Add(3 * time.Second))
	writeAll(control, []byte{5, 1, 2})
	var h [3]byte
	io.ReadFull(control, h[:2])
	auth := append([]byte{1, byte(len(user))}, user...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, password...)
	writeAll(control, auth)
	io.ReadFull(control, h[:2])
	if h[1] != 0 {
		t.Fatal("fixture local auth failed")
	}
	req, _ := appendAddress([]byte{5, 3, 0}, M.SocksaddrFromNet(sender.LocalAddr()))
	writeAll(control, req)
	if _, e = io.ReadFull(control, h[:]); e != nil || h[1] != 0 {
		t.Fatalf("ASSOCIATE: %v", e)
	}
	relay, e := readAddress(control)
	if e != nil {
		t.Fatal(e)
	}
	relayAddress := net.UDPAddrFromAddrPort(netip.AddrPortFrom(relay.Addr, relay.Port))
	frame, _ := appendAddress([]byte{0, 0, 0}, M.SocksaddrFromNet(echo.LocalAddr()))
	frame = append(frame, []byte("owned-udp")...)
	rogue.WriteToUDP(frame, relayAddress)
	rogue.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
	buffer := make([]byte, 128)
	if _, _, e = rogue.ReadFromUDP(buffer); e == nil || dialer.opens.Load() != 0 {
		t.Fatal("unauthed source opened upstream")
	}
	sender.WriteToUDP(frame, relayAddress)
	sender.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, e := sender.ReadFromUDP(buffer)
	if e != nil || !strings.HasSuffix(string(buffer[:n]), "owned-udp") || dialer.opens.Load() != 1 {
		t.Fatalf("pinned source relay: %v", e)
	}
	control.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("UDP association not retired")
	}
}
func TestMcrfProtocolsDescriptor(t *testing.T) {
	requireNaiveBuild()
	var d struct {
		Kind, Upstream, Ingress string
		Capabilities            []string
	}
	if json.Unmarshal([]byte(descriptor), &d) != nil || d.Kind != "protocols" || d.Upstream != "3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d" || d.Ingress != "authenticated-loopback-socks5-tcp-udp" {
		t.Fatal("wrong descriptor")
	}
	for _, required := range []string{"naive-cronet", "ssh-host-key", "udp-authenticated-source-port-v1", "android-child-physical-network-v1"} {
		found := false
		for _, v := range d.Capabilities {
			found = found || v == required
		}
		if !found {
			t.Fatal("missing descriptor capability")
		}
	}
}
