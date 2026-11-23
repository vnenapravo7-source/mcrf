package s3

import (
	"context"
	"errors"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestMcrfUnderlayRejectsInvalidOrUnavailableProcessNetwork(t *testing.T) {
	previous, handle := bindProcessNetwork, physicalNetwork
	t.Cleanup(func() { bindProcessNetwork, physicalNetwork = previous, handle })
	physicalNetwork = 0
	unavailable := errors.New("owned network unavailable")
	var captured uint64
	bindProcessNetwork = func(handle uint64) error {
		captured = handle
		return unavailable
	}
	for _, value := range []string{"", "0", "01", "-1", " 2", "9223372036854775808"} {
		t.Setenv(underlayEnvironment, value)
		if err := ConfigureMcrfUnderlay(); err == nil || captured != 0 || physicalNetwork != 0 {
			t.Fatalf("invalid handle accepted: %q", value)
		}
	}
	t.Setenv(underlayEnvironment, "42")
	if err := ConfigureMcrfUnderlay(); !errors.Is(err, unavailable) || captured != 42 || physicalNetwork != 0 {
		t.Fatal("failed process bind did not block admission")
	}
}

func TestMcrfS3HTTPClientRejectsUnavailableSocketBinding(t *testing.T) {
	previous, handle := bindSocketNetwork, physicalNetwork
	t.Cleanup(func() { bindSocketNetwork, physicalNetwork = previous, handle })
	physicalNetwork = 42
	unavailable := errors.New("owned socket rejected")
	bindSocketNetwork = func(handle uint64, _ uintptr) error {
		if handle != 42 {
			t.Errorf("wrong physical network: %d", handle)
		}
		return unavailable
	}
	peer, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, "GET", "https://"+peer.Addr().String(), nil)
	if err != nil {
		t.Fatal(err)
	}
	client := newHTTPClient(2)
	defer client.CloseIdleConnections()
	if response, err := client.Do(request); !errors.Is(err, unavailable) {
		if response != nil {
			response.Body.Close()
		}
		t.Fatalf("HTTP dial did not reject the unavailable physical socket: %v", err)
	}
}
