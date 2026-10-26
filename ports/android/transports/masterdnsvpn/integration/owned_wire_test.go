package integration

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/internal/logger"
	"masterdnsvpn-go/internal/security"
	udpserver "masterdnsvpn-go/internal/udpserver"
)

func masterFixture(t *testing.T) (string, *ownedBackend) {
	t.Helper()
	backend := ownedSOCKSBackend(t)
	reserved, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserved.LocalAddr().String()
	_, port, _ := net.SplitHostPort(address)
	number, _ := strconv.Atoi(port)
	reserved.Close()
	_, forwardPort, _ := net.SplitHostPort(backend.address)
	forwardNumber, _ := strconv.Atoi(forwardPort)
	// Non-tunnel DNS must never go to a public upstream during an owned test.
	blackhole, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { blackhole.Close() })
	doc := map[string]any{"UDP_HOST": "127.0.0.1", "UDP_PORT": number, "UDP_READERS": 1, "DNS_REQUEST_WORKERS": 2, "DEFERRED_SESSION_WORKERS": 1,
		"DOMAIN": []string{"t.example.test"}, "DATA_ENCRYPTION_METHOD": 5, "PROTOCOL_TYPE": "SOCKS5", "USE_EXTERNAL_SOCKS5": true,
		"FORWARD_IP": "127.0.0.1", "FORWARD_PORT": forwardNumber, "SOCKS5_AUTH": true, "SOCKS5_USER": "remote", "SOCKS5_PASS": "owned-password",
		"DNS_UPSTREAM_SERVERS": []string{blackhole.LocalAddr().String()}, "LOG_LEVEL": "ERROR"}
	data, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.LoadServerConfigFromJSONBase64(base64.StdEncoding.EncodeToString(data))
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConfigDir = t.TempDir()
	codec, err := security.NewCodec(5, "owned-master-key")
	if err != nil {
		t.Fatal(err)
	}
	server := udpserver.New(cfg, logger.McrfQuiet(), codec)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- server.Run(ctx); close(done) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("owned MasterDnsVPN DNS peer did not retire")
		}
	})
	return address, backend
}

func masterClient(t *testing.T, resolver string) string {
	t.Helper()
	reserved, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserved.Addr().String()
	reserved.Close()
	doc := map[string]any{"domains": []string{"t.example.test"}, "resolvers": []string{resolver}, "encryption_method": 5, "encryption_key": "owned-master-key", "socks": address, "socks_user": "local", "socks_pass": "owned-local-password", "workers": 1}
	data, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	p, err := config.McrfRead(strings.NewReader(string(data)))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.McrfConfig(p, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	codec, err := security.NewCodec(cfg.DataEncryptionMethod, cfg.EncryptionKey)
	if err != nil {
		t.Fatal(err)
	}
	app := client.New(cfg, logger.McrfQuiet(), codec)
	if err = app.BuildConnectionMap(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan struct{})
	var once sync.Once
	done := make(chan error, 1)
	go func() {
		done <- app.McrfRun(ctx, func(stage string) {
			if stage == "ready" {
				once.Do(func() { close(ready) })
			}
		})
		close(done)
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("owned MasterDnsVPN client did not retire")
		}
	})
	select {
	case <-ready:
	case err := <-done:
		t.Fatalf("client stopped before actual ready: %v", err)
	case <-time.After(30 * time.Second):
		t.Fatal("owned encrypted DNS session did not become ready")
	}
	return address
}

func TestMcrfMasterOwnedEncryptedDNSAndSOCKSData(t *testing.T) {
	resolver, _ := masterFixture(t)
	address := masterClient(t, resolver)
	conn, err := net.Dial("tcp4", address)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err = conn.Write([]byte{5, 1, 2}); err != nil {
		t.Fatal(err)
	}
	var reply [2]byte
	if _, err = io.ReadFull(conn, reply[:]); err != nil || reply != [2]byte{5, 2} {
		t.Fatal("local method negotiation failed")
	}
	password := "owned-local-password"
	auth := append([]byte{1, 5}, []byte("local")...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, []byte(password)...)
	if _, err = conn.Write(auth); err != nil {
		t.Fatal(err)
	}
	if _, err = io.ReadFull(conn, reply[:]); err != nil || reply != [2]byte{1, 0} {
		t.Fatal("local authentication failed")
	}
	if _, err = conn.Write([]byte{5, 1, 0, 1, 198, 51, 100, 7, 1, 187}); err != nil {
		t.Fatal(err)
	}
	var connected [10]byte
	if _, err = io.ReadFull(conn, connected[:]); err != nil || connected[1] != 0 {
		t.Fatal("owned remote CONNECT failed")
	}
	if _, err = conn.Write([]byte("OWNED")); err != nil {
		t.Fatal(err)
	}
	var payload [5]byte
	if _, err = io.ReadFull(conn, payload[:]); err != nil || string(payload[:]) != "OWNED" {
		t.Fatal("payload did not cross the original encrypted DNS transport")
	}
}

func TestMcrfMasterLocalRejectionDoesNotOpenRemoteSOCKS(t *testing.T) {
	resolver, backend := masterFixture(t)
	address := masterClient(t, resolver)
	before := backend.connections.Load()
	conn, err := net.Dial("tcp4", address)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(time.Second))
	if _, err = conn.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	var reply [2]byte
	if _, err = io.ReadFull(conn, reply[:]); err != nil || reply != [2]byte{5, 255} {
		t.Fatal("unauthenticated method was accepted")
	}
	conn.Close()
	time.Sleep(50 * time.Millisecond)
	if backend.connections.Load() != before {
		t.Fatal("rejected local client created a remote SOCKS stream")
	}
}
