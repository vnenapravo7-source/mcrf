// SPDX-License-Identifier: GPL-3.0-or-later
package client

import (
	"errors"
	"net"
	"strconv"
	"syscall"
)

const McrfUnderlayEnv = "MCRF_MASTERDNS_UNDERLAY"

func (c *Client) McrfBindUnderlay(value string) error {
	return c.mcrfBindUnderlay(value, bindMasterDNSProcess, bindMasterDNSSocket)
}

func (c *Client) mcrfBindUnderlay(value string, process func(uint64) error, socket func(uint64, uintptr) error) error {
	if value == "" {
		return nil
	}
	handle, err := strconv.ParseUint(value, 10, 64)
	if err != nil || handle == 0 {
		return errors.New("invalid Android physical network handle")
	}
	if err := process(handle); err != nil {
		return err
	}
	c.mcrfSocketControl = func(_, _ string, conn syscall.RawConn) error {
		var bindErr error
		if err := conn.Control(func(fd uintptr) { bindErr = socket(handle, fd) }); err != nil {
			return err
		}
		return bindErr
	}
	return nil
}

func (c *Client) mcrfListenTunnelUDP() (*net.UDPConn, error) {
	if c.mcrfSocketControl == nil {
		return net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero})
	}
	listener := net.ListenConfig{Control: c.mcrfSocketControl}
	conn, err := listener.ListenPacket(c.mcrfRootContext(), "udp", "0.0.0.0:0")
	if err != nil {
		return nil, err
	}
	return conn.(*net.UDPConn), nil
}

func (c *Client) mcrfDialResolverUDP(address *net.UDPAddr) (*net.UDPConn, error) {
	if c.mcrfSocketControl == nil {
		return net.DialUDP("udp", nil, address)
	}
	dialer := net.Dialer{Control: c.mcrfSocketControl}
	conn, err := dialer.DialContext(c.mcrfRootContext(), "udp", address.String())
	if err != nil {
		return nil, err
	}
	return conn.(*net.UDPConn), nil
}
