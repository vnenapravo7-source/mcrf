package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestPrivateSOCKSUDPBoundSourceRejectsFirstSender(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var dials atomic.Int32
	done := make(chan error, 1)
	go func() {
		done <- serveBridgeSOCKS(ctx, listener, func(_ context.Context, network, address string) (net.Conn, error) {
			if network != "udp" || address != "198.18.0.2:53" {
				return nil, errors.New("unexpected synthetic destination")
			}
			dials.Add(1)
			a, b := net.Pipe()
			go func() {
				defer b.Close()
				_, _ = io.Copy(b, b)
			}()
			return a, nil
		}, "fixture-user", "fixture-password")
	}()
	control, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	_ = control.SetDeadline(time.Now().Add(2 * time.Second))
	write := func(data []byte) {
		if _, err := control.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	read := func(expected []byte) {
		response := make([]byte, len(expected))
		if _, err := io.ReadFull(control, response); err != nil || !bytes.Equal(response, expected) {
			t.Fatal("unexpected private SOCKS handshake response", err)
		}
	}
	write([]byte{5, 1, 2})
	read([]byte{5, 2})
	user, password := "fixture-user", "fixture-password"
	auth := append([]byte{1, byte(len(user))}, user...)
	auth = append(auth, byte(len(password)))
	write(append(auth, password...))
	read([]byte{1, 0})

	owner, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	foreign, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer foreign.Close()
	write(append([]byte{5, 3, 0}, encodeSOCKSAddress(owner.LocalAddr().String())...))
	read([]byte{5, 0, 0})
	address, err := readSOCKSAddress(control)
	if err != nil {
		t.Fatal(err)
	}
	relay, err := net.ResolveUDPAddr("udp4", address)
	if err != nil {
		t.Fatal(err)
	}
	header := append([]byte{0, 0, 0}, encodeSOCKSAddress("198.18.0.2:53")...)
	wrong := append(bytes.Clone(header), "foreign"...)
	if _, err := foreign.WriteToUDP(wrong, relay); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 128)
	_ = foreign.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, _, err := foreign.ReadFromUDP(buffer); err == nil || dials.Load() != 0 {
		t.Fatal("first sender became a SOCKS association peer")
	}
	payload := append(bytes.Clone(header), "owner"...)
	if _, err := owner.WriteToUDP(payload, relay); err != nil {
		t.Fatal(err)
	}
	_ = owner.SetReadDeadline(time.Now().Add(time.Second))
	n, _, err := owner.ReadFromUDP(buffer)
	if err != nil || !bytes.Equal(buffer[:n], payload) || dials.Load() != 1 {
		t.Fatal("prebound SOCKS peer did not complete a round trip", err)
	}
	_ = control.Close()
	cancel()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("SOCKS association failed to retire")
	}
}
