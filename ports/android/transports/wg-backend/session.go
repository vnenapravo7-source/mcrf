// Package wgbackend is the optional WG/AWG data-plane bridge, independent of sing-box.
// The Android host owns permission, routes/DNS and the original TUN descriptor.
package wgbackend

import (
	"context"
	"errors"
	"strings"
	"sync"
	"syscall"

	"github.com/sagernet/wireguard-go/conn"
	"github.com/sagernet/wireguard-go/device"
	"github.com/sagernet/wireguard-go/tun"
)

// Protector must synchronously protect AND bind the socket to the captured physical
// network. Returning false prevents the socket from being used, without a fallback.
type Protector interface{ PrepareSocket(fd int32) bool }

type Session struct {
	mu   sync.Mutex
	core *device.Device
	dns *dnsNat
}

func protectedBind(protector Protector) (conn.Bind, error) {
	if protector == nil {
		return nil, errors.New("WG_PROTECTOR_REQUIRED")
	}
	return conn.NewStdNetBind(func(network, address string, raw syscall.RawConn) error {
		prepared := false
		err := raw.Control(func(fd uintptr) {
			if fd <= 0x7fffffff {
				prepared = protector.PrepareSocket(int32(fd))
			}
		})
		if err != nil || !prepared {
			return errors.New("WG_SOCKET_PREPARE_REJECTED")
		}
		return nil
	}), nil
}

// Takes ownership of only the supplied private TUN duplicate and bind, even on failure.
// Does not expose IpcGet, which includes private keys, nor any control socket.
func startDevice(tunnel tun.Device, bind conn.Bind, configuration string) (*Session, error) {
	if tunnel == nil || bind == nil {
		if tunnel != nil {
			_ = tunnel.Close()
		}
		if bind != nil {
			_ = bind.Close()
		}
		return nil, errors.New("WG_INPUT_REQUIRED")
	}
	if len(configuration) == 0 || len(configuration) > 65536 || strings.ContainsRune(configuration, 0) {
		_ = tunnel.Close()
		_ = bind.Close()
		return nil, errors.New("WG_CONFIG_REJECTED")
	}
	core := device.NewDevice(context.Background(), tunnel, bind, device.NewLogger(device.LogLevelSilent, ""), 1)
	if err := core.IpcSet(configuration); err != nil {
		core.Close()
		return nil, errors.New("WG_CONFIG_REJECTED")
	}
	if err := core.Up(); err != nil {
		core.Close()
		return nil, errors.New("WG_START_REJECTED")
	}
	return &Session{core: core}, nil
}

// Idempotent shutdown closes the owned UDP sockets and owned TUN duplicate.
// Android process retirement remains an additional host-side gate.
func (s *Session) Close() {
	if s == nil {
		return
	}
	s.mu.Lock()
	core := s.core
	s.core = nil
	dns:=s.dns;s.dns=nil
	s.mu.Unlock()
	if dns!=nil{dns.close()}
	if core != nil {
		core.Close()
	}
}

// Mapping only, never a substitute for the Android original-owner callback.
func(s *Session) DnsClient(protocol,translatedPort int32)*DnsClient {
	if s==nil{return nil};s.mu.Lock();dns:=s.dns;s.mu.Unlock();if dns==nil{return nil};return dns.client(protocol,translatedPort)
}
