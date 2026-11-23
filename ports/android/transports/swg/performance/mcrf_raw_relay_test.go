package main

import (
	"context"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"wg-turn-client/internal/csqtt"
)

// Exercise the UDP boundary used by Rust's read_udp/write_udp, rather than
// connecting two netstacks directly. Completion requires a remote ACK after
// all bytes have been received; a successful buffered Write is insufficient.
func TestMcrfRawRelayTCPAcknowledged(t *testing.T) {
	client, network, err := startRawNetstack("RAWCONF:198.18.0.1|198.18.0.2|1300")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	peer, peerNet, err := startRawNetstack("RAWCONF:198.18.0.2|198.18.0.1|1300")
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	dispatcher, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer dispatcher.Close()
	socket, err := net.DialUDP("udp4", nil, dispatcher.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var workers sync.WaitGroup
	var addressMu sync.Mutex
	var address *net.UDPAddr
	workers.Add(2)
	go func() {
		defer workers.Done()
		buf := make([]byte, 65535)
		for {
			n, from, e := dispatcher.ReadFromUDP(buf)
			if e != nil {
				return
			}
			addressMu.Lock()
			address = from
			addressMu.Unlock()
			if _, e = peer.Write(buf[:n]); e != nil {
				return
			}
		}
	}()
	go func() {
		defer workers.Done()
		buf := make([]byte, 65535)
		for {
			n, e := peer.Read(buf)
			if e != nil {
				return
			}
			addressMu.Lock()
			to := address
			addressMu.Unlock()
			if to == nil {
				return
			}
			if _, e = dispatcher.WriteToUDP(buf[:n], to); e != nil {
				return
			}
		}
	}()
	relayDone := make(chan error, 1)
	go func() { relayDone <- csqtt.RelayIP(ctx, client, socket) }()
	defer func() {
		cancel()
		dispatcher.Close()
		peer.Close()
		workers.Wait()
		<-relayDone
	}()
	listener, err := peerNet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("198.18.0.2"), Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	const size = 256 << 10
	served := make(chan error, 1)
	go func() {
		conn, e := listener.Accept()
		if e != nil {
			served <- e
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(4 * time.Second))
		_, e = io.CopyN(io.Discard, conn, size)
		if e == nil {
			_, e = conn.Write([]byte("ACK"))
		}
		served <- e
	}()
	conn, err := network.DialContext(ctx, "tcp", "198.18.0.2:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err = conn.Write(make([]byte, size)); err != nil {
		t.Fatal(err)
	}
	var ack [3]byte
	if _, err = io.ReadFull(conn, ack[:]); err != nil || string(ack[:]) != "ACK" {
		t.Fatalf("remote ACK: %q %v", ack, err)
	}
	if err = <-served; err != nil {
		t.Fatal(err)
	}
}
