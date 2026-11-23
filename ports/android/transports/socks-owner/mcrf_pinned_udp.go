package socks

import (
	"fmt"
	"io"
	"net"
	"net/netip"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

func (c *Client) mcrfPinnedUDP(control net.Conn, target M.Socksaddr) (*AssociatePacketConn, error) {
	local, localOK := control.LocalAddr().(*net.TCPAddr)
	peer, peerOK := control.RemoteAddr().(*net.TCPAddr)
	if !localOK || !peerOK || !local.IP.IsLoopback() || !peer.IP.IsLoopback() {
		return nil, fmt.Errorf("socks5: private UDP requires a loopback control")
	}
	network := "udp4"
	if local.IP.To4() == nil {
		network = "udp6"
	}
	udp, err := net.ListenUDP(network, &net.UDPAddr{IP: local.IP, Zone: local.Zone})
	if err != nil {
		return nil, err
	}
	response, err := clientHandshake5(control, socks5.CommandUDPAssociate,
		M.SocksaddrFromNet(udp.LocalAddr()), c.username, c.password, true)
	if err != nil {
		udp.Close()
		return nil, err
	}
	relay, err := mcrfPrivateRelay(response.Bind, peer.IP)
	if err != nil {
		udp.Close()
		return nil, err
	}
	connected := &mcrfRelayConn{udp: udp, relay: relay, expected: relay.AddrPort()}
	go func() {
		_, _ = io.Copy(io.Discard, control)
		_ = udp.Close()
		_ = control.Close()
	}()
	return NewAssociatePacketConn(connected, target, control), nil
}

func mcrfPrivateRelay(bound M.Socksaddr, peer net.IP) (*net.UDPAddr, error) {
	if !peer.IsLoopback() || bound.IsDomain() || !bound.Addr.IsValid() || bound.Port == 0 {
		return nil, fmt.Errorf("socks5: invalid private UDP relay")
	}
	relay := bound.UDPAddr()
	if relay.IP.IsUnspecified() {
		// Older private workers may omit BND.ADDR. Use the authenticated
		// TCP peer and retain the exact relay port returned on that control.
		relay.IP = peer
	}
	if !relay.IP.Equal(peer) {
		return nil, fmt.Errorf("socks5: private UDP relay differs from control peer")
	}
	return relay, nil
}

type mcrfRelayConn struct {
	udp      *net.UDPConn
	relay    *net.UDPAddr
	expected netip.AddrPort
}

func (c *mcrfRelayConn) Read(data []byte) (int, error) {
	for {
		size, peer, err := c.udp.ReadFromUDPAddrPort(data)
		if err != nil {
			return 0, err
		}
		if peer.Port() == c.expected.Port() && peer.Addr().Unmap() == c.expected.Addr().Unmap() {
			return size, nil
		}
	}
}

func (c *mcrfRelayConn) Write(data []byte) (int, error) {
	return c.udp.WriteToUDP(data, c.relay)
}

func (c *mcrfRelayConn) RemoteAddr() net.Addr {
	return c.relay
}

func (c *mcrfRelayConn) LocalAddr() net.Addr { return c.udp.LocalAddr() }
func (c *mcrfRelayConn) Close() error { return c.udp.Close() }
func (c *mcrfRelayConn) SetDeadline(deadline time.Time) error { return c.udp.SetDeadline(deadline) }
func (c *mcrfRelayConn) SetReadDeadline(deadline time.Time) error { return c.udp.SetReadDeadline(deadline) }
func (c *mcrfRelayConn) SetWriteDeadline(deadline time.Time) error { return c.udp.SetWriteDeadline(deadline) }
