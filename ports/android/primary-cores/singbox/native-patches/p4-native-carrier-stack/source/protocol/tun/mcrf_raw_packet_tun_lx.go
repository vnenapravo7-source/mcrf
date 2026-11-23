//go:build with_lx_raw_packet && !with_lx_raw_packet_port && (linux || android)

package tun

import (
	"context"
	"io"

	"github.com/sagernet/sing-box/adapter"
	singtun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/service"
)

func wrapMcrfRawPacketTun(ctx context.Context, router adapter.Router, inbound, inboundType string, underlying singtun.Tun) (singtun.Tun, func(), error) {
	provider := service.FromContext[adapter.OriginalPacketEndpointProvider](ctx)
	if provider == nil || !provider.HasOriginalPacketEndpoints() {
		return underlying, nil, nil
	}
	ingress, err := adapter.NewRawPacketIngress(ctx, router, provider, inbound, inboundType, func(packet []byte) error {
		n, writeErr := underlying.Write(packet)
		if writeErr == nil && n != len(packet) {
			writeErr = io.ErrShortWrite
		}
		return writeErr
	})
	if err != nil {
		return nil, nil, err
	}
	if linuxTun, ok := underlying.(singtun.LinuxTUN); ok {
		return &mcrfRawPacketLinuxTun{LinuxTUN: linuxTun, ingress: ingress}, ingress.Close, nil
	}
	return &mcrfRawPacketTun{Tun: underlying, ingress: ingress}, ingress.Close, nil
}

type mcrfRawPacketTun struct {
	singtun.Tun
	ingress *adapter.RawPacketIngress
}

func (t *mcrfRawPacketTun) Read(packet []byte) (int, error) {
	for {
		n, err := t.Tun.Read(packet)
		if err != nil || n == 0 {
			return n, err
		}
		consumed, err := t.ingress.DispatchOriginal(packet[:n])
		if err != nil {
			return 0, err
		}
		if !consumed {
			return n, nil
		}
	}
}

type mcrfRawPacketLinuxTun struct {
	singtun.LinuxTUN
	ingress *adapter.RawPacketIngress
}

func (t *mcrfRawPacketLinuxTun) Read(packet []byte) (int, error) {
	return (&mcrfRawPacketTun{Tun: t.LinuxTUN, ingress: t.ingress}).Read(packet)
}

func (t *mcrfRawPacketLinuxTun) BatchRead(buffers [][]byte, offset int, readN []int) (int, error) {
	for {
		n, err := t.LinuxTUN.BatchRead(buffers, offset, readN)
		if err != nil || n == 0 {
			return n, err
		}
		kept := 0
		for index := 0; index < n; index++ {
			packetLength := readN[index]
			if packetLength < 0 || offset < 0 || offset+packetLength > len(buffers[index]) {
				return 0, io.ErrShortBuffer
			}
			consumed, dispatchErr := t.ingress.DispatchOriginal(buffers[index][offset : offset+packetLength])
			if dispatchErr != nil {
				return 0, dispatchErr
			}
			if !consumed {
				if kept != index {
					buffers[kept], readN[kept] = buffers[index], packetLength
				}
				kept++
			}
		}
		if kept > 0 {
			return kept, nil
		}
	}
}

var _ singtun.Tun = (*mcrfRawPacketTun)(nil)
var _ singtun.LinuxTUN = (*mcrfRawPacketLinuxTun)(nil)
