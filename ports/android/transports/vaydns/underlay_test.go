package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/net2share/vaydns/client"
)

func TestMcrfUnderlayAdmissionRejectsInvalidAndUnavailableNetwork(t *testing.T) {
	previous := bindProcessNetwork
	t.Cleanup(func() { bindProcessNetwork = previous })
	var captured uint64
	unavailable := errors.New("lost network")
	bindProcessNetwork = func(handle uint64) error {
		captured = handle
		return unavailable
	}
	for _, value := range []string{"", "0", "01", "-1", " 2", "9223372036854775808"} {
		t.Setenv(underlayEnvironment, value)
		if handle, err := requestedUnderlay(); err == nil || handle != 0 || captured != 0 {
			t.Fatalf("invalid handle admitted: %q", value)
		}
	}
	t.Setenv(underlayEnvironment, "42")
	if handle, err := requestedUnderlay(); !errors.Is(err, unavailable) || handle != 0 || captured != 42 {
		t.Fatal("process bind failure did not prevent admission")
	}
	bindProcessNetwork = func(uint64) error { return nil }
	if handle, err := requestedUnderlay(); err != nil || handle != 42 {
		t.Fatalf("valid network rejected: handle=%d error=%v", handle, err)
	}
}

func TestMcrfUnderlayFailedSocketBindBlocksDoHDoTAndUDP(t *testing.T) {
	previous := bindSocketNetwork
	t.Cleanup(func() { bindSocketNetwork = previous })
	blocked := errors.New("test socket bind rejected")
	bindSocketNetwork = func(handle uint64, _ uintptr) error {
		if handle != 42 {
			t.Errorf("wrong network: %d", handle)
		}
		return blocked
	}
	control := underlayControl(42)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	httpTransport := underlayHTTP(control)
	defer httpTransport.CloseIdleConnections()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, "GET", "https://"+listener.Addr().String()+"/dns-query", nil)
	if err != nil {
		t.Fatal(err)
	}
	if response, err := httpTransport.RoundTrip(request); !errors.Is(err, blocked) {
		if response != nil {
			response.Body.Close()
		}
		t.Fatalf("DoH did not reject the failed socket bind: %v", err)
	}

	resolver, err := client.NewResolver(client.ResolverTypeDOT, listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	resolver.DialerControl = control
	tunnel := client.Tunnel{Resolver: resolver}
	if err := tunnel.InitiateResolverConnection(); !errors.Is(err, blocked) {
		t.Fatalf("DoT did not reject the failed socket bind: %v", err)
	}

	packet := net.ListenConfig{Control: control}
	if connection, err := packet.ListenPacket(context.Background(), "udp4", "127.0.0.1:0"); !errors.Is(err, blocked) {
		if connection != nil {
			connection.Close()
		}
		t.Fatalf("UDP did not reject the failed socket bind: %v", err)
	}
}
