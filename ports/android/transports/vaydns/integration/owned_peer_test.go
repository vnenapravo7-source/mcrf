package integration

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/net2share/vaydns/client"
	"github.com/net2share/vaydns/noise"
)

func fixtureBinary(t *testing.T, variable string) string {
	t.Helper()
	path := os.Getenv(variable)
	if path == "" {
		t.Fatal("common coordinator must supply owned fixture binary: " + variable)
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() {
		t.Fatal("owned fixture binary missing")
	}
	return path
}

func startOwnedProcess(t *testing.T, command *exec.Cmd) chan error {
	t.Helper()
	command.Stdout = io.Discard
	command.Stderr = io.Discard
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- command.Wait(); close(done) }()
	return done
}

type ownedBackend struct {
	address     string
	connections atomic.Int64
}

func ownedSOCKSBackend(t *testing.T) *ownedBackend {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	backend := &ownedBackend{address: ln.Addr().String()}
	var workers sync.WaitGroup
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			conn, e := ln.Accept()
			if e != nil {
				return
			}
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer conn.Close()
				stop := context.AfterFunc(ctx, func() { conn.Close() })
				defer stop()
				conn.SetDeadline(time.Now().Add(10 * time.Second))
				backend.connections.Add(1)
				var greeting [3]byte
				if _, e := io.ReadFull(conn, greeting[:]); e != nil || greeting != [3]byte{5, 1, 2} {
					return
				}
				conn.Write([]byte{5, 2})
				var header [2]byte
				if _, e := io.ReadFull(conn, header[:]); e != nil || header[0] != 1 {
					return
				}
				user := make([]byte, int(header[1]))
				if _, e := io.ReadFull(conn, user); e != nil {
					return
				}
				var length [1]byte
				if _, e := io.ReadFull(conn, length[:]); e != nil {
					return
				}
				password := make([]byte, int(length[0]))
				if _, e := io.ReadFull(conn, password); e != nil {
					return
				}
				if string(user) != "remote" || string(password) != "owned-password" {
					conn.Write([]byte{1, 1})
					return
				}
				conn.Write([]byte{1, 0})
				// Readiness authenticates then closes without a CONNECT. Data streams
				// can CONNECT only to this synthetic destination; never forward it.
				var request [10]byte
				if _, e := io.ReadFull(conn, request[:]); e != nil {
					return
				}
				if request != [10]byte{5, 1, 0, 1, 198, 51, 100, 7, 1, 187} {
					return
				}
				conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 1})
				var payload [5]byte
				if _, e := io.ReadFull(conn, payload[:]); e == nil && string(payload[:]) == "OWNED" {
					conn.Write(payload[:])
				}
			}()
		}
	}()
	t.Cleanup(func() {
		cancel()
		ln.Close()
		<-acceptDone
		done := make(chan struct{})
		go func() { workers.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("backend workers did not retire")
		}
	})
	return backend
}

func ownedTunnel(t *testing.T, backend *ownedBackend) *client.Tunnel {
	t.Helper()
	privateKey, err := noise.GeneratePrivkey()
	if err != nil {
		t.Fatal(err)
	}
	keyFile := filepath.Join(t.TempDir(), "owned-peer-key")
	if err = os.WriteFile(keyFile, []byte(hex.EncodeToString(privateKey)), 0600); err != nil {
		t.Fatal(err)
	}
	reserve, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserve.LocalAddr().String()
	reserve.Close()
	ctx, cancel := context.WithCancel(context.Background())
	command := exec.CommandContext(ctx, fixtureBinary(t, "MCRF_VAYDNS_TEST_SERVER"), "-udp", address, "-privkey-file", keyFile, "-domain", "t.example.test", "-upstream", backend.address)
	done := startOwnedProcess(t, command)
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("owned peer did not retire")
		}
	})
	resolver, err := client.NewResolver(client.ResolverTypeUDP, address)
	if err != nil {
		t.Fatal(err)
	}
	server, err := client.NewTunnelServer("t.example.test", hex.EncodeToString(noise.PubkeyFromPrivkey(privateKey)))
	if err != nil {
		t.Fatal(err)
	}
	tunnel, err := client.NewTunnel(resolver, server)
	if err != nil {
		t.Fatal(err)
	}
	return tunnel
}

func serveOwned(t *testing.T, tunnel *client.Tunnel, password string) (string, chan struct{}, chan error, context.CancelFunc) {
	t.Helper()
	ln, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	bridge, err := client.McrfSOCKS("local", "owned-local-password", "remote", password)
	if err != nil {
		t.Fatal(err)
	}
	ready := make(chan struct{}, 1)
	bridge.Ready = func() {
		select {
		case ready <- struct{}{}:
		default:
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- tunnel.McrfServe(ctx, ln, bridge); close(done) }()
	return ln.Addr().String(), ready, done, cancel
}

func awaitReady(t *testing.T, ready chan struct{}, done chan error) {
	t.Helper()
	select {
	case <-ready:
	case e := <-done:
		t.Fatal("bridge stopped before ready", e)
	case <-time.After(20 * time.Second):
		t.Fatal("owned Noise/SOCKS handshake timeout")
	}
}

func localAuth(t *testing.T, address, password string) (net.Conn, byte) {
	t.Helper()
	conn, err := net.DialTimeout("tcp4", address, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	conn.Write([]byte{5, 1, 2})
	var reply [2]byte
	if _, err = io.ReadFull(conn, reply[:]); err != nil || reply != [2]byte{5, 2} {
		t.Fatal("local greeting", err)
	}
	request := append([]byte{1, 5}, []byte("local")...)
	request = append(request, byte(len(password)))
	request = append(request, []byte(password)...)
	conn.Write(request)
	if _, err = io.ReadFull(conn, reply[:]); err != nil {
		t.Fatal(err)
	}
	return conn, reply[1]
}

func stopBridge(t *testing.T, cancel context.CancelFunc, done chan error) {
	t.Helper()
	cancel()
	select {
	case e := <-done:
		if e != nil && !errors.Is(e, context.Canceled) {
			t.Error(e)
		}
	case <-time.After(12 * time.Second):
		t.Error("bridge retirement exceeded bounded stream wait")
	}
}

func TestMcrfVayOwnedDNSNoiseSOCKSData(t *testing.T) {
	backend := ownedSOCKSBackend(t)
	tunnel := ownedTunnel(t, backend)
	address, ready, done, cancel := serveOwned(t, tunnel, "owned-password")
	defer stopBridge(t, cancel, done)
	awaitReady(t, ready, done)
	conn, status := localAuth(t, address, "owned-local-password")
	if status != 0 {
		t.Fatal("local auth rejected")
	}
	defer conn.Close()
	conn.Write([]byte{5, 1, 0, 1, 198, 51, 100, 7, 1, 187})
	var reply [10]byte
	if _, err := io.ReadFull(conn, reply[:]); err != nil || reply[1] != 0 {
		t.Fatal("owned CONNECT", err)
	}
	conn.Write([]byte("OWNED"))
	var echo [5]byte
	if _, err := io.ReadFull(conn, echo[:]); err != nil || string(echo[:]) != "OWNED" {
		t.Fatal("encrypted tunnel data", err)
	}
}

func TestMcrfVayLocalRejectionDoesNotOpenDNSStream(t *testing.T) {
	backend := ownedSOCKSBackend(t)
	tunnel := ownedTunnel(t, backend)
	address, ready, done, cancel := serveOwned(t, tunnel, "owned-password")
	defer stopBridge(t, cancel, done)
	awaitReady(t, ready, done)
	before := backend.connections.Load()
	conn, status := localAuth(t, address, "wrong-password")
	if status == 0 {
		t.Fatal("wrong local password accepted")
	}
	var tail [1]byte
	if _, err := conn.Read(tail[:]); err != io.EOF {
		t.Fatal("rejected local handler did not close", err)
	}
	if backend.connections.Load() != before {
		t.Fatal("unauthorized local client opened a remote stream")
	}
}

func TestMcrfVayRemoteRejectionStopsWithoutRetry(t *testing.T) {
	backend := ownedSOCKSBackend(t)
	tunnel := ownedTunnel(t, backend)
	_, ready, done, cancel := serveOwned(t, tunnel, "wrong-password")
	defer stopBridge(t, cancel, done)
	select {
	case err := <-done:
		if !errors.Is(err, client.ErrMcrfRemoteAuth) {
			t.Fatal(err)
		}
	case <-ready:
		t.Fatal("READY before backend authorization")
	case <-time.After(20 * time.Second):
		t.Fatal("terminal refusal did not stop")
	}
	if backend.connections.Load() != 1 {
		t.Fatal("rejected remote credentials were retried")
	}
}

func TestMcrfVayStdinEOFStopsDuringLostNetwork(t *testing.T) {
	blackhole, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blackhole.Close()
	reserve, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	socks := reserve.Addr().String()
	reserve.Close()
	key, err := noise.GeneratePrivkey()
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(map[string]string{"domain": "t.example.test", "key": hex.EncodeToString(noise.PubkeyFromPrivkey(key)), "udp": blackhole.LocalAddr().String(), "socks": socks, "socks_user": "local", "socks_pass": "owned-password"})
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(t.TempDir(), "private-config.json")
	if err = os.WriteFile(file, data, 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, fixtureBinary(t, "MCRF_VAYDNS_TEST_CLIENT"), "--mcrf-config", file)
	stdin, err := command.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	done := startOwnedProcess(t, command)
	stdin.Close()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal("EOF stop failed", err)
		}
	case <-time.After(3 * time.Second):
		cancel()
		<-done
		t.Fatal("EOF did not bound lost-network stop")
	}
}
