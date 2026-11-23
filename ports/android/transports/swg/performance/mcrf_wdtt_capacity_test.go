package main

import (
	"context"
	"errors"
	"flag"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMcrfWdttRejectsPrivateConfigBeforeDNS(t *testing.T) {
	const input = "MCRF_OWNED_WDTT_STARTUP_ARGUMENT"
	if mode, child := os.LookupEnv(input); child {
		mcrfWdttSetupResolver = func() { panic("DNS attempted before configuration validation") }
		_ = os.Unsetenv(mcrfWdttUnderlayEnv)
		flag.CommandLine = flag.NewFlagSet("wdtt", flag.ExitOnError)
		os.Args = []string{"wdtt", mode, os.Getenv("MCRF_OWNED_WDTT_BAD_CONFIG")}
		main()
		t.Fatal("accepted a nonexistent configuration")
	}
	for mode, expected := range map[string]string{
		"--mcrf-config":        "MCRF: invalid private configuration",
		"--mcrf-enroll-masque": "MCRF: MASQUE registration unavailable",
	} {
		t.Run(mode, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			child := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestMcrfWdttRejectsPrivateConfigBeforeDNS$")
			child.Env = append(os.Environ(), input+"="+mode,
				"MCRF_OWNED_WDTT_BAD_CONFIG="+filepath.Join(t.TempDir(), "nonexistent.json"))
			output, err := child.CombinedOutput()
			var exit *exec.ExitError
			if !errors.As(err, &exit) || exit.ExitCode() != 1 || !strings.Contains(string(output), expected) {
				t.Fatalf("WDTT startup did not reject before DNS: %v\n%s", err, output)
			}
		})
	}
}

// Real WDTT SOCKS listener, not just a semaphore model. No VK, DNS queries or
// remote connections: authenticated clients wait before their CONNECT request.
func TestMcrfWdtt512ClientsAndOverflowBackpressure(t *testing.T) {
	reserve, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserve.Addr().String()
	reserve.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ready := make(chan string, 1)
	done := make(chan error, 1)
	go func() {
		done <- runSocks5Server(ctx, address, nil, true, "u", "p", false, "", func(addr string) { ready <- addr })
	}()
	select {
	case address = <-ready:
	case err = <-done:
		t.Fatal(err)
	case <-time.After(3 * time.Second):
		t.Fatal("listener startup timeout")
	}
	clients := make([]net.Conn, 0, 512)
	defer func() {
		for _, client := range clients {
			client.Close()
		}
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(3 * time.Second):
			t.Error("listener shutdown stalled")
		}
	}()
	for i := 0; i < 512; i++ {
		client, e := net.DialTimeout("tcp4", address, time.Second)
		if e != nil {
			t.Fatalf("connect %d: %v", i, e)
		}
		clients = append(clients, client)
		client.SetDeadline(time.Now().Add(2 * time.Second))
		client.Write([]byte{5, 1, 2})
		var reply [2]byte
		if _, e = io.ReadFull(client, reply[:]); e != nil || reply != [2]byte{5, 2} {
			t.Fatalf("client %d reset before capacity: %v", i, e)
		}
		client.Write([]byte{1, 1, 'u', 1, 'p'})
		if _, e = io.ReadFull(client, reply[:]); e != nil || reply != [2]byte{1, 0} {
			t.Fatalf("client %d authentication failed: %v", i, e)
		}
		client.SetDeadline(time.Time{})
	}
	extra, err := net.DialTimeout("tcp4", address, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer extra.Close()
	extra.Write([]byte{5, 1, 2})
	extra.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	var reply [2]byte
	_, err = io.ReadFull(extra, reply[:])
	timeout, ok := err.(net.Error)
	if !ok || !timeout.Timeout() {
		t.Fatal("overflow reset or admitted instead of queued", err)
	}
	clients[0].Close()
	extra.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err = io.ReadFull(extra, reply[:]); err != nil || reply != [2]byte{5, 2} {
		t.Fatal("queued client did not resume", err)
	}
}
