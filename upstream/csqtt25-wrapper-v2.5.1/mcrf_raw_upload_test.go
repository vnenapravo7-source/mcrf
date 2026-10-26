package main

import (
	"context"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Actual Android bridge netstack, with an old1300 RAWCONF and a controlled
// peer. This is not a claimed TURN/Internet speed measurement.
func TestMcrfRawMTUOnActualBulkUpload(t *testing.T) {
	client, clientNet, err := startRawNetstack("RAWCONF:10.200.0.1|10.200.0.2|1300")
	if err != nil {
		t.Fatal(err)
	}
	server, serverNet, err := startRawNetstack("RAWCONF:10.200.0.2|10.200.0.1|1300")
	if err != nil {
		client.Close()
		t.Fatal(err)
	}
	var workers sync.WaitGroup
	var maxPacket, dataPackets atomic.Int64
	for index, pair := range [][2]*rawPacketDevice{{client, server}, {server, client}} {
		workers.Add(1)
		go func(from, to *rawPacketDevice, upload bool) {
			defer workers.Done()
			buffer := make([]byte, 65536)
			for {
				n, e := from.Read(buffer)
				if e != nil {
					return
				}
				if upload {
					for old := maxPacket.Load(); int64(n) > old; old = maxPacket.Load() {
						if maxPacket.CompareAndSwap(old, int64(n)) {
							break
						}
					}
					if n > 80 {
						dataPackets.Add(1)
					}
				}
				if _, e = to.Write(buffer[:n]); e != nil {
					return
				}
			}
		}(pair[0], pair[1], index == 0)
	}
	defer func() { client.Close(); server.Close(); workers.Wait() }()
	listener, err := serverNet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.200.0.2"), Port: 30081})
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
		conn.SetDeadline(time.Now().Add(10 * time.Second))
		_, e = io.CopyN(io.Discard, conn, size)
		if e == nil {
			_, e = conn.Write([]byte{1})
		}
		served <- e
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := clientNet.DialContext(ctx, "tcp", "10.200.0.2:30081")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err = conn.Write(make([]byte, size)); err != nil {
		t.Fatal(err)
	}
	var reply [1]byte
	if _, err = io.ReadFull(conn, reply[:]); err != nil || reply[0] != 1 {
		t.Fatal("bulk reply", err)
	}
	if err = <-served; err != nil {
		t.Fatal(err)
	}
	if maxPacket.Load() > 1280 || dataPackets.Load() < 2 {
		t.Fatalf("actual upload packets: max=%d payloadPackets=%d", maxPacket.Load(), dataPackets.Load())
	}
}
