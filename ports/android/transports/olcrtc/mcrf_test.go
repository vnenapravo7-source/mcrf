package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

func mcrfOlcFixture() map[string]any {
	return map[string]any{"provider": "jitsi", "transport": "datachannel", "room": "https://meet.example.test/owned", "key": strings.Repeat("ab", 32), "device_id": "owned-private-id", "dns": "127.0.0.1:5353", "socks": "127.0.0.1:12345", "socks_user": "local", "socks_pass": "private"}
}
func mcrfOlcRead(value map[string]any) (client.Config, error) {
	data, _ := json.Marshal(value)
	return readConfig(strings.NewReader(string(data)))
}
func TestMcrfOlcStrictPrivateSchema(t *testing.T) {
	value := mcrfOlcFixture()
	if _, err := mcrfOlcRead(value); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"type", "name", "probe_only", "DeviceID", "log", "device_id_path"} {
		value[key] = "untrusted"
		if _, err := mcrfOlcRead(value); err == nil {
			t.Fatalf("unknown %s accepted", key)
		}
		delete(value, key)
	}
	data, _ := json.Marshal(value)
	for _, bad := range []string{string(data) + " {}", strings.TrimSuffix(string(data), "}") + `,"room":"other"}`, `{"room":null}`, strings.Repeat(" ", 65537)} {
		if _, err := readConfig(strings.NewReader(bad)); err == nil {
			t.Fatal("ambiguous/oversized profile accepted")
		}
	}
	value["socks"] = "0.0.0.0:12345"
	if _, err := mcrfOlcRead(value); err == nil {
		t.Fatal("public listener accepted")
	}
}
func TestMcrfOlcTransportOptionsPreserveOriginalMapping(t *testing.T) {
	value := mcrfOlcFixture()
	value["transport"] = "vp8channel"
	value["options"] = map[string]any{"vp8-fps": "45", "vp8-batch": 32}
	cfg, err := mcrfOlcRead(value)
	if err != nil {
		t.Fatal(err)
	}
	options, ok := cfg.TransportOptions.(client.VP8Options)
	if !ok || options.FPS != 45 || options.BatchSize != 32 || cfg.SOCKSUser != "local" || cfg.DeviceID != "owned-private-id" || cfg.DeviceIDPath != "" {
		t.Fatal("original options/private ownership changed")
	}
	for _, options := range []map[string]any{{"unknown": 1}, {"vp8-fps": 0}, {"video-codec": "invalid"}, {"video-qr-recovery": "invalid"}} {
		value["options"] = options
		if _, err := mcrfOlcRead(value); err == nil {
			t.Fatal("malformed option silently ignored")
		}
	}
	data, _ := json.Marshal(mcrfOlcFixture())
	bad := strings.TrimSuffix(string(data), "}") + `,"options":{"fps":30,"fps":45}}`
	if _, err := readConfig(strings.NewReader(bad)); err == nil {
		t.Fatal("duplicate nested option accepted")
	}
}
func TestMcrfOlcStopCancelsTheOwnedListener(t *testing.T) {
	input, control := io.Pipe()
	defer input.Close()
	defer control.Close()
	started := make(chan string, 1)
	done := make(chan error, 1)
	runner := func(ctx context.Context, ready func()) error {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return err
		}
		defer listener.Close()
		started <- listener.Addr().String()
		ready()
		<-ctx.Done()
		return ctx.Err()
	}
	go func() { done <- ownSession(context.Background(), input, runner, func() {}) }()
	var address string
	select {
	case address = <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("fixture startup stalled")
	}
	if _, err := io.WriteString(control, "STOP\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("STOP did not cancel owner")
	}
	conn, err := net.DialTimeout("tcp", address, time.Second)
	if err == nil {
		conn.Close()
		t.Fatal("STOP left listener open")
	}
}
func TestMcrfOlcEOFIsCancellationWithoutReady(t *testing.T) {
	ready := false
	runner := func(ctx context.Context, _ func()) error { <-ctx.Done(); return ctx.Err() }
	done := make(chan error, 1)
	go func() {
		done <- ownSession(context.Background(), strings.NewReader(""), runner, func() { ready = true })
	}()
	select {
	case err := <-done:
		if err != nil || ready {
			t.Fatal("EOF produced readiness/failure")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("EOF did not cancel owner")
	}
}
func TestMcrfOlcStartupFailureIsNotSwallowed(t *testing.T) {
	original := errors.New("owned fixture failure")
	input, control := io.Pipe()
	defer input.Close()
	defer control.Close()
	err := ownSession(context.Background(), input, func(context.Context, func()) error { return original }, func() { t.Error("failed startup announced READY") })
	if !errors.Is(err, original) {
		t.Fatal("startup cause swallowed")
	}
	for _, value := range []struct {
		err  error
		code string
	}{
		{fmt.Errorf("provider: %w", x509.UnknownAuthorityError{}), "OLC_TLS_UNTRUSTED"},
		{fmt.Errorf("provider: %w", handshake.ErrRejected), "OLC_PEER_REJECTED"},
		{fmt.Errorf("provider: %w", handshake.ErrProtocolVersion), "OLC_PROTOCOL_VERSION"},
		{errors.New("provider: status 403: private-room-token"), "OLC_HTTP_403"},
		{errors.New("private-room-token"), "OLC_SESSION_FAILED"},
	} {
		if actual := failureCode(value.err); actual != value.code || strings.Contains(actual, "private") {
			t.Fatal("confirmed cause lost or secret exposed")
		}
	}
}
