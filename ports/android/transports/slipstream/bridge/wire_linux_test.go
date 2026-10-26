//go:build linux || android

package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func wireBinary(t *testing.T, key string) string {
	t.Helper()
	path := os.Getenv(key)
	info, err := os.Lstat(path)
	if path == "" || err != nil || !info.Mode().IsRegular() {
		t.Fatalf("common coordinator must supply owned pinned binary: %s", key)
	}
	return path
}

func wireCertificate(t *testing.T, dir string) (string, string, string) {
	t.Helper()
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test.example.com"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certFile, keyFile := filepath.Join(dir, "server.pem"), filepath.Join(dir, "key.pem")
	value := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	if err = os.WriteFile(certFile, []byte(value), 0600); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}), 0600); err != nil {
		t.Fatal(err)
	}
	return value, certFile, keyFile
}

func wireBackend(t *testing.T) (string, *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	var accepted atomic.Int64
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
				conn.SetDeadline(time.Now().Add(15 * time.Second))
				accepted.Add(1)
				var greeting [3]byte
				if _, e = io.ReadFull(conn, greeting[:]); e != nil || greeting != [3]byte{5, 1, 0} {
					return
				}
				if mcrfWrite(conn, []byte{5, 0}) != nil {
					return
				}
				var request [10]byte
				if _, e = io.ReadFull(conn, request[:]); e != nil || request != [10]byte{5, 1, 0, 1, 198, 51, 100, 7, 1, 187} {
					return
				}
				if mcrfWrite(conn, []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 1}) != nil {
					return
				}
				var payload [5]byte
				if _, e = io.ReadFull(conn, payload[:]); e == nil && string(payload[:]) == "OWNED" {
					mcrfWrite(conn, payload[:])
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
		case <-time.After(time.Second):
			t.Error("owned SOCKS backend did not stop")
		}
	})
	return ln.Addr().String(), &accepted
}

func wireFixture(t *testing.T) (config, string, string, *atomic.Int64) {
	return wireFixtureServer(t, false)
}

func wireFixtureServer(t *testing.T, originalC bool) (config, string, string, *atomic.Int64) {
	t.Helper()
	dir, err := os.MkdirTemp("", "mcrf-wire-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	certificate, cert, key := wireCertificate(t, dir)
	backend, count := wireBackend(t)
	reserved, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	resolver := reserved.LocalAddr().String()
	_, port, _ := net.SplitHostPort(resolver)
	reserved.Close()
	ctx, cancel := context.WithCancel(context.Background())
	server := wireBinary(t, "MCRF_SLIPSTREAM_TEST_SERVER")
	args := []string{"--dns-listen-port", port, "--target-address", backend, "--domain", "t.example.test", "--cert", cert, "--key", key}
	if originalC {
		server = wireBinary(t, "MCRF_SLIPSTREAM_TEST_CSERVER")
	} else {
		args = append([]string{"--dns-listen-host", "127.0.0.1"}, args...)
	}
	command := exec.CommandContext(ctx, server, args...)
	command.Stdout = io.Discard
	command.Stderr = io.Discard
	command.Env = []string{"RUST_LOG=off"}
	done, err := fixtureStart(command, nil)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("owned Slipstream DNS peer did not stop")
		}
	})
	local, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := local.Addr().String()
	local.Close()
	value := fixtureConfig(t)
	value["certificate"] = certificate
	value["resolver"] = resolver
	value["socks"] = address
	c, err := parseFixture(t, value)
	if err != nil {
		t.Fatal(err)
	}
	return c, wireBinary(t, "MCRF_SLIPSTREAM_TEST_CORE"), dir, count
}

func TestMcrfSlipstreamOwnedWireData(t *testing.T) {
	c, core, dir, _ := wireFixture(t)
	wireOwnedData(t, c, core, dir)
}

func TestMcrfSlipstreamOriginalCServerInterop(t *testing.T) {
	c, core, dir, _ := wireFixtureServer(t, true)
	wireOwnedData(t, c, core, dir)
}

func wireOwnedData(t *testing.T, c config, core, dir string) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- run(ctx, c, core, dir); close(done) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("wrapper/core did not stop")
		}
	})
	var local net.Conn
	deadline := time.Now().Add(40 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case err := <-done:
			t.Fatalf("tunnel stopped before ready: %v", err)
		default:
		}
		conn, err := net.DialTimeout("tcp4", c.SOCKS, 100*time.Millisecond)
		if err == nil {
			local = conn
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if local == nil {
		t.Fatal("owned pinned tunnel did not become ready")
	}
	defer local.Close()
	local.SetDeadline(time.Now().Add(15 * time.Second))
	if err := mcrfWrite(local, []byte{5, 1, 2}); err != nil {
		t.Fatal(err)
	}
	var reply [2]byte
	if _, err := io.ReadFull(local, reply[:]); err != nil || reply != [2]byte{5, 2} {
		t.Fatal("local method")
	}
	request := append([]byte{1, byte(len(c.User))}, []byte(c.User)...)
	request = append(request, byte(len(c.Password)))
	request = append(request, []byte(c.Password)...)
	if mcrfWrite(local, request) != nil {
		t.Fatal("local auth write")
	}
	if _, err := io.ReadFull(local, reply[:]); err != nil || reply != [2]byte{1, 0} {
		t.Fatal("local auth")
	}
	if mcrfWrite(local, []byte{5, 1, 0, 1, 198, 51, 100, 7, 1, 187}) != nil {
		t.Fatal("CONNECT write")
	}
	var connected [10]byte
	if _, err := io.ReadFull(local, connected[:]); err != nil || connected[1] != 0 {
		t.Fatalf("owned CONNECT: error=%v reply=%x",err,connected)
	}
	if mcrfWrite(local, []byte("OWNED")) != nil {
		t.Fatal("owned payload write")
	}
	var payload [5]byte
	if _, err := io.ReadFull(local, payload[:]); err != nil || string(payload[:]) != "OWNED" {
		t.Fatal("owned payload did not cross QUIC/DNS")
	}
}

func TestMcrfSlipstreamWrongLeafStopsBeforeRemoteSOCKS(t *testing.T) {
	c, core, dir, count := wireFixture(t)
	wrongDir, err := os.MkdirTemp("", "mcrf-wrong-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(wrongDir)
	c.Certificate, _, _ = wireCertificate(t, wrongDir)
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	err = run(ctx, c, core, dir)
	if err == nil || !strings.Contains(err.Error(), "CERTIFICATE_MISMATCH") {
		t.Fatalf("pin mismatch not preserved: %v", err)
	}
	if count.Load() != 0 {
		t.Fatal("wrong certificate reached remote SOCKS")
	}
}
