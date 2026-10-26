package tunnel

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"strconv"
	"testing"
	"time"
)

func TestMcrfSstpCloseUnblocksStalledDisconnect(t *testing.T) {
	local, peer := net.Pipe()
	defer peer.Close()
	s := &Session{tlsConn: tls.Client(local, &tls.Config{ServerName: "fixture.test", MinVersion: tls.VersionTLS12}), done: make(chan struct{})}
	defer local.Close()
	done := make(chan struct{})
	go func() { s.McrfClose(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Close waited for the ordinary 30-second write deadline")
	}
	select {
	case <-s.done:
	default:
		t.Fatal("session was not retired")
	}
}

func TestMcrfSstpCancelDuringPostTLSHTTPHandshake(t *testing.T) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture.test"}, DNSNames: []string{"fixture.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	httpSeen := make(chan struct{})
	peerDone := make(chan struct{})
	go func() {
		defer close(peerDone)
		raw, e := ln.Accept()
		if e != nil {
			return
		}
		defer raw.Close()
		stop := context.AfterFunc(ctx, func() { raw.Close() })
		defer stop()
		peer := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS12})
		reader := bufio.NewReader(peer)
		for {
			line, e := reader.ReadString('\n')
			if e != nil {
				return
			}
			if line == "\r\n" {
				break
			}
		}
		close(httpSeen)
		io.Copy(io.Discard, peer)
	}()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	number, _ := strconv.Atoi(port)
	done := make(chan error, 1)
	go func() {
		_, _, e := Dial(ctx, Config{Server: host, Port: number, SNI: "fixture.test", Username: "fixture-user", Password: "fixture-password", TLSMode: TLSCustomCA, CACerts: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})})
		done <- e
	}()
	t.Cleanup(func() {
		cancel()
		ln.Close()
		select {
		case <-peerDone:
		case <-time.After(time.Second):
			t.Error("owned TLS peer did not retire")
		}
	})
	select {
	case <-httpSeen:
	case err := <-done:
		t.Fatalf("did not reach post-TLS handshake: %v", err)
	case <-time.After(3 * time.Second):
		t.Fatal("owned TLS handshake did not reach HTTP")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled incomplete handshake succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("post-TLS handshake ignored cancellation")
	}
}
