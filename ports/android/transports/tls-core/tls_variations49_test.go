//go:build with_utls

package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	stdtls "crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"reflect"
	"strconv"
	"testing"
	"time"

	boxTLS "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

func TestP049TLSAllIdentitiesActualTLSAndALPN(t *testing.T) {
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	now := time.Now()
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(49), Subject: pkix.Name{CommonName: "fixture.invalid"},
		DNSNames: []string{"fixture.invalid"}, NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	der, e := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	roots := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	cert := stdtls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	names := []string{"chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
		"firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized", "custom", ""}
	for _, fp := range names {
		for i, protocols := range [][]string{nil, {}, {"http/1.1"}, {"h2", "http/1.1"}, {"http/1.1", "h2"}} {
			t.Run(fp+"/alpn-"+strconv.Itoa(i), func(t *testing.T) {
				a, b := ownedTLSTCPPair49(t)
				defer a.Close()
				done := make(chan error, 1)
				serverConfig := &stdtls.Config{Certificates: []stdtls.Certificate{cert}, MinVersion: stdtls.VersionTLS12, MaxVersion: stdtls.VersionTLS13, NextProtos: []string{"http/1.1", "h2"}}
				serverConfig.GetConfigForClient = func(h *stdtls.ClientHelloInfo) (*stdtls.Config, error) {
					if protocols != nil && !reflect.DeepEqual(h.SupportedProtos, protocols) {
						if len(protocols) > 0 || len(h.SupportedProtos) > 0 {
							return nil, io.ErrUnexpectedEOF
						}
					}
					return nil, nil
				}
				go func() {
					defer b.Close()
					_ = b.SetDeadline(time.Now().Add(4 * time.Second))
					server := stdtls.Server(b, serverConfig)
					e := server.Handshake()
					if e == nil {
						var request [1]byte
						_, e = io.ReadFull(server, request[:])
						if e == nil && request[0] != 49 {
							e = io.ErrUnexpectedEOF
						}
					}
					if e == nil {
						_, e = server.Write([]byte{50})
					}
					done <- e
				}()
				raw := map[string]any{"enabled": true, "server_name": "fixture.invalid", "certificate": []string{roots},
					"utls": map[string]any{"enabled": true, "fingerprint": fp}}
				if protocols != nil {
					raw["alpn"] = protocols
				}
				data, _ := json.Marshal(raw)
				var options option.OutboundTLSOptions
				if json.Unmarshal(data, &options) != nil {
					t.Fatal("fixture options")
				}
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				defer cancel()
				config, e := boxTLS.NewClient(ctx, logger.NOP(), "fixture.invalid", options)
				if e != nil {
					t.Fatal("valid ordinary TLS config", fp, e)
				}
				_ = a.SetDeadline(time.Now().Add(3 * time.Second))
				client, e := config.Client(a)
				if e == nil {
					e = client.HandshakeContext(ctx)
				}
				if e != nil {
					t.Fatal("actual pinned ordinary TLS handshake", fp, e)
				}
				if _, e = client.Write([]byte{49}); e != nil {
					t.Fatal(e)
				}
				var reply [1]byte
				if _, e = io.ReadFull(client, reply[:]); e != nil || reply[0] != 50 {
					t.Fatal("no owned authenticated application response", e)
				}
				select {
				case e := <-done:
					if e != nil {
						t.Fatal("owned ordinary TLS server", e)
					}
				case <-time.After(time.Second):
					t.Fatal("owned ordinary TLS server did not retire")
				}
			})
		}
	}
}

func ownedTLSTCPPair49(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer ln.Close()
	_ = ln.SetDeadline(time.Now().Add(time.Second))
	a, e := net.DialTimeout("tcp4", ln.Addr().String(), time.Second)
	if e != nil {
		t.Fatal(e)
	}
	b, e := ln.AcceptTCP()
	if e != nil {
		a.Close()
		t.Fatal(e)
	}
	t.Cleanup(func() { a.Close(); b.Close() })
	return a, b
}
