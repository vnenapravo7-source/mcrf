package main

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"syscall"

	tlsclient "github.com/bogdanfinn/tls-client"
)

const mcrfUnderlayEnv = "MCRF_QWDTT_UNDERLAY"
const mcrfWdttUnderlayEnv = "MCRF_WDTT_UNDERLAY"

var mcrfUnderlayHandle uint64
var mcrfBindProcessNetwork = func(uint64) error { return errors.New("TURN underlay unavailable") }
var mcrfBindSocketNetwork = func(uint64, uintptr) error { return errors.New("TURN underlay unavailable") }

func mcrfRequestedUnderlay(value, mode string) (uint64, error) {
	if value == "" {
		return 0, nil
	}
	handle, err := strconv.ParseUint(value, 10, 63)
	if (mode != "qwdtt" && mode != "wdtt") || err != nil || handle == 0 || strconv.FormatUint(handle, 10) != value {
		return 0, errors.New("invalid TURN underlay")
	}
	return handle, nil
}

func mcrfConfigureUnderlay() error {
	mode := os.Getenv("MCRF_BS_MODE")
	if mode == "wdtt" {
		return errors.New("invalid QWDTT mode")
	}
	return mcrfConfigureProviderNetwork(mcrfUnderlayEnv, mode)
}

func mcrfConfigureWdttUnderlay() error {
	return mcrfConfigureProviderNetwork(mcrfWdttUnderlayEnv, "wdtt")
}

func mcrfConfigureProviderNetwork(key, mode string) error {
	value, exists := os.LookupEnv(key)
	if exists && value == "" {
		return errors.New("invalid TURN underlay")
	}
	handle, err := mcrfRequestedUnderlay(value, mode)
	if err != nil || handle == 0 {
		return err
	}
	// This binds the Android system resolver. Explicit Go TCP and UDP dials
	// also bind their own socket before connecting.
	if err := mcrfBindProcessNetwork(handle); err != nil {
		return err
	}
	mcrfUnderlayHandle = handle
	return nil
}

func mcrfUnderlayDialer(dialer net.Dialer) net.Dialer {
	handle := mcrfUnderlayHandle
	if handle == 0 {
		return dialer
	}
	prior, priorContext := dialer.Control, dialer.ControlContext
	dialer.ControlContext = func(ctx context.Context, network, address string, raw syscall.RawConn) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if priorContext != nil {
			if err := priorContext(ctx, network, address, raw); err != nil {
				return err
			}
		} else if prior != nil {
			if err := prior(network, address, raw); err != nil {
				return err
			}
		}
		if !strings.HasPrefix(network, "tcp") && !strings.HasPrefix(network, "udp") {
			return errors.New("invalid TURN underlay socket")
		}
		peer, err := netip.ParseAddrPort(address)
		if err != nil || peer.Port() == 0 || peer.Addr().IsUnspecified() {
			return errors.New("invalid TURN underlay peer")
		}
		if peer.Addr().Unmap().IsLoopback() {
			return nil
		}
		return mcrfBindUnderlaySocket(raw, handle)
	}
	return dialer
}

func mcrfBindUnderlaySocket(raw syscall.RawConn, handle uint64) error {
	var bindError error
	if err := raw.Control(func(fd uintptr) { bindError = mcrfBindSocketNetwork(handle, fd) }); err != nil {
		return err
	}
	return bindError
}

// MASQUE/QUIC needs an unconnected UDP socket. Bind it before its first send.
func mcrfUnderlayListenUDP4(ctx context.Context) (*net.UDPConn, error) {
	handle := mcrfUnderlayHandle
	if handle == 0 {
		return net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})
	}
	listen := net.ListenConfig{Control: func(_, _ string, raw syscall.RawConn) error {
		return mcrfBindUnderlaySocket(raw, handle)
	}}
	socket, err := listen.ListenPacket(ctx, "udp4", "0.0.0.0:0")
	if err != nil {
		return nil, err
	}
	return socket.(*net.UDPConn), nil
}

func mcrfNewUnderlayHTTPClient(logger tlsclient.Logger, options ...tlsclient.HttpClientOption) (tlsclient.HttpClient, error) {
	if mcrfUnderlayHandle != 0 {
		options = append(options, tlsclient.WithDialer(mcrfUnderlayDialer(net.Dialer{})))
	}
	return tlsclient.NewHttpClient(logger, options...)
}
