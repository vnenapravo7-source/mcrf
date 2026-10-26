// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"strconv"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
)

// These are actual original SDK client/server protocols, not SOCKS echo tests.
// All listeners and targets are owned loopback; no account/VPS/provider needed.
func wireCertificate(t *testing.T) (string, string) {
	t.Helper()
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "unit.invalid"}, DNSNames: []string{"unit.invalid"}, IPAddresses: []net.IP{net.IPv4(127, 0, 0, 1)}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	priv, e := x509.MarshalPKCS8PrivateKey(key)
	if e != nil {
		t.Fatal(e)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: priv}))
}
func wirePort(t *testing.T, udp bool) int {
	t.Helper()
	if udp {
		c, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if e != nil {
			t.Fatal(e)
		}
		defer c.Close()
		return c.LocalAddr().(*net.UDPAddr).Port
	}
	c, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	return c.Addr().(*net.TCPAddr).Port
}
func wireEcho(t *testing.T) (M.Socksaddr, func()) {
	t.Helper()
	ln, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		c, e := ln.AcceptTCP()
		if e != nil {
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(15 * time.Second))
		io.Copy(c, c)
	}()
	return M.SocksaddrFromNet(ln.Addr()), func() { ln.Close(); <-done }
}
func wireSdk(t *testing.T, kind string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	certificate, key := wireCertificate(t)
	port := wirePort(t, kind == "tuic")
	password := "owned-fixture-only-password"
	uuid := "fce8eeec-a4e4-4321-8849-ccd59ab87111"
	inbound := map[string]any{"type": kind, "listen": "127.0.0.1", "listen_port": port, "tls": map[string]any{"enabled": true, "certificate": []string{certificate}, "key": []string{key}}}
	out := map[string]any{"type": kind, "server": "127.0.0.1", "server_port": port, "password": password, "tls": map[string]any{"enabled": true, "server_name": "unit.invalid", "certificate": []string{certificate}}}
	switch kind {
	case "tuic":
		inbound["users"] = []any{map[string]string{"uuid": uuid, "password": password}}
		out["uuid"] = uuid
		inbound["tls"].(map[string]any)["alpn"] = []string{"h3"}
		out["tls"].(map[string]any)["alpn"] = []string{"h3"}
	case "anytls":
		inbound["users"] = []any{map[string]string{"name": "fixture", "password": password}}
	case "naive":
		inbound["users"] = []any{map[string]string{"username": "fixture", "password": password}}
		inbound["network"] = "tcp"
		out["username"] = "fixture"
	default:
		t.Fatal("unknown wire fixture")
	}
	data, _ := json.Marshal(map[string]any{"log": map[string]any{"disabled": true}, "inbounds": []any{inbound}, "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"final": "direct"}})
	serverOptions, e := sjson.UnmarshalExtendedContext[option.Options](include.Context(ctx), data)
	if e != nil {
		t.Fatal(e)
	}
	server, e := box.New(box.Options{Context: include.Context(ctx), Options: serverOptions})
	if e != nil {
		t.Fatal(e)
	}
	defer server.Close()
	if e = server.Start(); e != nil {
		t.Fatal(e)
	}
	config := fixtureConfig(kind)
	config["outbound"] = out
	data, _ = json.Marshal(config)
	c, e := readConfig(newPacketReader(data))
	if e != nil {
		t.Fatal(e)
	}
	client, dialer, e := createCore(ctx, c)
	if e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	if e = client.Start(); e != nil {
		t.Fatal(e)
	}
	target, stop := wireEcho(t)
	defer stop()
	conn, e := dialer.DialContext(ctx, "tcp", target)
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	payload := []byte("owned-" + kind + "-original-wire-" + strconv.Itoa(port))
	if e = writeAll(conn, payload); e != nil {
		t.Fatal(e)
	}
	answer := make([]byte, len(payload))
	if _, e = io.ReadFull(conn, answer); e != nil || string(answer) != string(payload) {
		t.Fatalf("actual %s payload failed: %v", kind, e)
	}
}
func TestMcrfProtocolsTuicOriginalWire(t *testing.T)        { wireSdk(t, "tuic") }
func TestMcrfProtocolsAnyTlsOriginalWire(t *testing.T)      { wireSdk(t, "anytls") }
func TestMcrfProtocolsNaiveCronetOriginalWire(t *testing.T) { wireSdk(t, "naive") }
