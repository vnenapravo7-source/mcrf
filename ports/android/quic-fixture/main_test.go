package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestOwnedQUICFixedLanesAndBindingFailure(t *testing.T) {
	for _, item := range []struct{ lane, source, target string }{
		{"p4", "172.19.0.1:0", "198.18.0.2:18444"},
		{"wg", "10.77.0.2:0", "10.77.0.1:18444"},
	} {
		if source, target, ok := laneAddresses(item.lane); !ok || source != item.source || target != item.target {
			t.Fatal("owned lane tuple changed")
		}
	}
	if _, _, ok := laneAddresses("outside"); ok {
		t.Fatal("unknown lane accepted")
	}
	if status, code := bindFailure(fmt.Errorf("control: %w", errVPNBinding)); status != "QUIC_BIND_DENIED" || code != 20 {
		t.Fatal("VPN socket denial lost")
	}
	if status, code := bindFailure(syscall.EADDRNOTAVAIL); status != "QUIC_FIXTURE_FAILED" || code != 2 {
		t.Fatal("source address setup error counted as VPN enforcement")
	}
}

func TestOwnedQUICConnectedUDPRejectsWrongPeer(t *testing.T) {
	listen := func() *net.UDPConn {
		t.Helper()
		socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { socket.Close() })
		return socket
	}
	peer, other := listen(), listen()
	connection, err := net.DialUDP("udp4", nil, peer.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	socket := connectedSocket{connection}
	if err = socket.SetDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err = socket.WriteTo([]byte("wrong"), other.LocalAddr()); err == nil {
		t.Fatal("packet written to a foreign tuple")
	}
	destination := connection.LocalAddr().(*net.UDPAddr)
	if _, err = other.WriteToUDP([]byte("wrong"), destination); err != nil {
		t.Fatal(err)
	}
	if _, err = peer.WriteToUDP([]byte("owned"), destination); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 16)
	n, source, err := socket.ReadFrom(data)
	if err != nil || string(data[:n]) != "owned" || source.String() != peer.LocalAddr().String() {
		t.Fatal("connected socket did not admit only its kernel peer")
	}
}

func TestOwnedQUICPinnedEcho(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	commands, send := io.Pipe()
	defer send.Close()
	result, resultWriter := io.Pipe()
	defer result.Close()
	done := make(chan error, 1)
	go func() {
		defer resultWriter.Close()
		done <- serve(ctx, server, commands, &reports{out: resultWriter})
	}()
	scanner := bufio.NewScanner(result)
	read := func(event string) map[string]any {
		t.Helper()
		if !scanner.Scan() {
			t.Fatal("fixture output closed")
		}
		var record map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil || record["event"] != event {
			t.Fatalf("unexpected fixture event: %v", record["event"])
		}
		return record
	}
	ready := read("ready")
	cert := ready["certificate"].(string)
	pin := ready["certificateSha256"].(string)
	if _, err := makeClientTLS(cert, strings.Repeat("0", 64)); err == nil {
		t.Fatal("mismatched certificate digest accepted")
	}
	trusted, err := makeClientTLS(cert, pin)
	if err != nil {
		t.Fatal(err)
	}
	_, foreignDER, err := makeServerTLS()
	if err != nil {
		t.Fatal(err)
	}
	foreign, err := makeClientTLS(base64.RawURLEncoding.EncodeToString(foreignDER), pinDigest(foreignDER))
	if err != nil {
		t.Fatal(err)
	}
	nonce := strings.Repeat("01", 16)
	dial := func(ownPeer bool) string {
		t.Helper()
		socket, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
		if err != nil {
			t.Fatal(err)
		}
		defer socket.Close()
		config := foreign
		if ownPeer {
			config = trusted
		}
		request, finish := context.WithTimeout(ctx, exchangeDeadline)
		defer finish()
		status, _ := exchange(request, connectedSocket{socket}, socket.RemoteAddr(), config, nonce)
		return status
	}
	if status := dial(false); status != "QUIC_HANDSHAKE_FAILED" {
		t.Fatalf("wrong certificate: %s", status)
	}
	if _, err = io.WriteString(send, "SNAPSHOT\n"); err != nil {
		t.Fatal(err)
	}
	if state := read("snapshot"); state["accepted"] != float64(0) || state["datagrams"].(float64) <= 0 {
		t.Fatal("wrong certificate reached the echo stream")
	}
	if status := dial(true); status != "QUIC_COMPLETE" {
		t.Fatalf("pinned QUIC stream: %s", status)
	}
	if state := read("accepted"); state["nonce"] != nonce || state["quicVersion"] != float64(1) ||
		state["tls"] != "1.3" || state["alpn"] != alpn {
		t.Fatal("wrong negotiated server receipt")
	}
	if _, err = io.WriteString(send, "STOP\n"); err != nil {
		t.Fatal(err)
	}
	if state := read("stopped"); state["accepted"] != float64(1) {
		t.Fatal("unexpected completed associations")
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
}
