package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"syscall"
	"time"
)

const dnsttUnderlayEnv = "MCRF_DNSTT_UNDERLAY"

type dnsttUnderlay struct {
	control func(string, string, syscall.RawConn) error
}

var mobileResolverUnderlay dnsttUnderlay

func newDNSTTUnderlay(value string, bindProcess func(uint64) error, bindSocket func(uint64, uintptr) error) (dnsttUnderlay, error) {
	if value == "" {
		return dnsttUnderlay{}, nil
	}
	handle, err := strconv.ParseUint(value, 10, 64)
	if err != nil || handle == 0 {
		return dnsttUnderlay{}, errors.New("invalid Android physical network handle")
	}
	if err := bindProcess(handle); err != nil {
		return dnsttUnderlay{}, fmt.Errorf("bind DNSTT resolver process: %w", err)
	}
	return dnsttUnderlay{control: func(_ string, _ string, connection syscall.RawConn) error {
		var bindError error
		if err := connection.Control(func(fd uintptr) { bindError = bindSocket(handle, fd) }); err != nil {
			return err
		}
		return bindError
	}}, nil
}

func (underlay dnsttUnderlay) dialer() *net.Dialer {
	return &net.Dialer{Control: underlay.control}
}

func (underlay dnsttUnderlay) httpDialer() *net.Dialer {
	return &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second, Control: underlay.control}
}

func (underlay dnsttUnderlay) listenUDP(ctx context.Context) (net.PacketConn, error) {
	listener := net.ListenConfig{Control: underlay.control}
	return listener.ListenPacket(ctx, "udp", ":0")
}
