//go:build linux || android

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"regexp"

	"wg-turn-client/internal/csqtt"
)

const mcrfOriginalPacketEnvironment = "MCRF_ORIGINAL_PACKET_CONTROL"

// The modern CSQTTR Android TUN uses 1500 (including the supplied 2.5.3 APK).
// This IP endpoint also advertises the value to the host's TCP/IP adapter; the
// legacy SOCKS-only bridge's historical 1300 must not constrain this path.
const mcrfOriginalPacketMTU = 1500

// An ephemeral host-only environment value, never copied from a public server
// document. The actual handoff additionally proves direct parent PID/UID and
// module ownership before reporting its distinct readiness signal.
func mcrfReadOriginalPacketControl(probe bool) (*csqtt.OriginalPacketControl, error) {
	text, exists := os.LookupEnv(mcrfOriginalPacketEnvironment)
	if !exists {
		return nil, nil
	}
	if probe || len(text) > 512 {
		return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
	}
	decoder := json.NewDecoder(bytes.NewBufferString(text))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
	}
	fields := map[string]json.RawMessage{}
	for decoder.More() {
		token, err = decoder.Token()
		key, ok := token.(string)
		if err != nil || !ok {
			return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
		}
		if _, duplicate := fields[key]; duplicate {
			return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
		}
		var raw json.RawMessage
		if decoder.Decode(&raw) != nil {
			return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
		}
		fields[key] = raw
	}
	if token, err = decoder.Token(); err != nil || token != json.Delim('}') {
		return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF || len(fields) != 4 {
		return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
	}
	for _, key := range []string{"name", "nonce", "generation", "parent_pid"} {
		if raw, ok := fields[key]; !ok || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
			return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
		}
	}
	var control csqtt.OriginalPacketControl
	if json.Unmarshal([]byte(text), &control) != nil || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(control.Nonce) ||
		control.Nonce != os.Getenv("MCRF_PROCESS_OWNER") || control.Name != "mcrf.carrier."+control.Nonce ||
		control.Generation == 0 || control.Generation > 1<<63-1 || control.ParentPID <= 0 || control.ParentPID != os.Getppid() {
		return nil, errors.New("MCRF_PACKET_CONTROL_CONFIG")
	}
	return &control, nil
}

// Called only after GETCONF from the already-owned original child. No second
// child, native wire/crypto change, SOCKS listener or wrapper netstack is used.
func mcrfRunOriginalPacketBridge(parent context.Context, control csqtt.OriginalPacketControl, tunnel csqtt.TunnelConfig, listen string, childDone <-chan struct{}, auth *csqtt.PacketAuth) error {
	if auth == nil {
		return errors.New("MCRF_PACKET_RELAY_AUTH_REQUIRED")
	}
	address, err := netip.ParseAddrPort(listen)
	if err != nil || address.Addr() != netip.MustParseAddr("127.0.0.1") || address.Port() == 0 {
		return errors.New("MCRF_PACKET_RELAY_ADDRESS")
	}
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() {
		select {
		case <-childDone:
			cancel()
		case <-ctx.Done():
		}
	}()
	device, err := csqtt.OpenOriginalPacketPort(ctx, control, tunnel.Address, mcrfOriginalPacketMTU)
	if err != nil {
		return err
	}
	defer device.Close()
	socket, err := net.DialUDP("udp4", nil, net.UDPAddrFromAddrPort(address))
	if err != nil {
		return errors.New("MCRF_PACKET_RELAY_SOCKET")
	}
	defer socket.Close()
	if tuneErr := mcrfConfigureIPRelaySocket(socket); tuneErr != nil {
		// Optional buffer sizing cannot invalidate a functioning owned socket.
		fmt.Println("[IP-адаптер] Буфер сокета оставлен системным")
	}
	if ctx.Err() != nil {
		if parent.Err() != nil {
			return nil
		}
		return errors.New("MCRF_PACKET_CHILD_STOPPED")
	}
	fmt.Println("MCRF_ORIGINAL_PACKET_READY") // ownership, not reachability
	_ = csqtt.RelayIP(ctx, device, auth.Socket(socket))
	if parent.Err() != nil {
		return nil
	}
	return errors.New("MCRF_PACKET_RELAY_STOPPED")
}
