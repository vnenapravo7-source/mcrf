package socks

import (
	"errors"
	"io"
	"net"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/varbin"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

func TestMcrfPrivateUDPPrebindAndControlEOF(t *testing.T) {
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	control, err := net.DialTCP("tcp4", nil, listener.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	server, err := listener.AcceptTCP()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	deadline := time.Now().Add(3 * time.Second)
	control.SetDeadline(deadline)
	server.SetDeadline(deadline)
	relay.SetDeadline(deadline)
	target := M.ParseSocksaddr("192.0.2.6:53")
	requestPeer := make(chan M.Socksaddr, 1)
	// The fixture parses the exact authenticated control request before
	// making any relay address available to the client.
	go func() {
		var greeting [3]byte
		if _, err := io.ReadFull(server, greeting[:]); err != nil || greeting != [3]byte{5, 1, 2} {
			server.Close()
			return
		}
		server.Write([]byte{5, 2})
		reader := varbin.StubReader(server)
		auth, err := socks5.ReadUsernamePasswordAuthRequest(reader)
		if err != nil || auth.Username != "owned" || auth.Password != "fixture" {
			server.Close()
			return
		}
		server.Write([]byte{1, 0})
		request, err := socks5.ReadRequest(reader)
		if err != nil || request.Command != socks5.CommandUDPAssociate {
			server.Close()
			return
		}
		requestPeer <- request.Destination
		bound := M.ParseSocksaddr("0.0.0.0:0")
		bound.Port = uint16(relay.LocalAddr().(*net.UDPAddr).Port)
		socks5.WriteResponse(server, socks5.Response{Bind: bound})
	}()
	client := &Client{username: "owned", password: "fixture"}
	packet, err := client.mcrfPinnedUDP(control, target)
	if err != nil {
		t.Fatal(err)
	}
	defer packet.Close()
	declared := <-requestPeer
	if !declared.Addr.IsLoopback() || declared.Port == 0 {
		t.Fatalf("unbound association: %s", declared.String())
	}
	payload := []byte{0x12, 0x34}
	if _, err = packet.Write(payload); err != nil {
		t.Fatal(err)
	}
	var wire [512]byte
	n, actual, err := relay.ReadFromUDP(wire[:])
	if err != nil || M.SocksaddrFromNet(actual).Unwrap() != declared.Unwrap() {
		t.Fatal("UDP source differs from the authenticated request", err)
	}
	foreign, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer foreign.Close()
	wrong := append([]byte(nil), wire[:n]...)
	wrong[n-1] ^= 0xff
	foreign.WriteToUDP(wrong, actual)
	relay.WriteToUDP(wire[:n], actual)
	packet.SetReadDeadline(deadline)
	if n, err = packet.Read(wire[:]); err != nil || string(wire[:n]) != string(payload) {
		t.Fatal("received data from outside the agreed relay", err)
	}
	server.Close()
	if _, err = packet.Read(wire[:]); !errors.Is(err, net.ErrClosed) {
		t.Fatal("UDP socket survived control EOF", err)
	}
}

func TestMcrfPrivateUDPRelayReply(t *testing.T) {
	for _, test := range []struct {
		wire, control string
		allowed       bool
	}{
		{"0.0.0.0:14567", "127.0.0.1", true},
		{"[::]:14567", "::1", true},
		{"127.0.0.1:14567", "127.0.0.1", true},
		{"127.0.0.2:14567", "127.0.0.1", false},
		{"192.0.2.10:14567", "127.0.0.1", false},
		{"fixture.test:14567", "127.0.0.1", false},
		{"127.0.0.1:0", "127.0.0.1", false},
		{"0.0.0.0:0", "127.0.0.1", false},
	} {
		t.Run(test.wire, func(t *testing.T) {
			peer := net.ParseIP(test.control)
			relay, err := mcrfPrivateRelay(M.ParseSocksaddr(test.wire), peer)
			if test.allowed {
				if err != nil || relay == nil || !relay.IP.Equal(peer) || relay.Port != 14567 {
					t.Fatalf("relay %v, error %v", relay, err)
				}
			} else if err == nil || relay != nil {
				t.Fatal("invalid private relay admitted")
			}
		})
	}
}
