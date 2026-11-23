//go:build !linux && !android

package main

import (
	"context"
	"errors"
	"os"
	"wg-turn-client/internal/csqtt"
)

type mcrfOriginalPacketUnsupported struct{}

func mcrfReadOriginalPacketControl(probe bool) (*mcrfOriginalPacketUnsupported, error) {
	if _, exists := os.LookupEnv("MCRF_ORIGINAL_PACKET_CONTROL"); exists {
		return nil, errors.New("MCRF_PACKET_PLATFORM_UNSUPPORTED")
	}
	return nil, nil
}
func mcrfRunOriginalPacketBridge(context.Context, mcrfOriginalPacketUnsupported, csqtt.TunnelConfig, string, <-chan struct{}, *csqtt.PacketAuth) error {
	return errors.New("MCRF_PACKET_PLATFORM_UNSUPPORTED")
}
