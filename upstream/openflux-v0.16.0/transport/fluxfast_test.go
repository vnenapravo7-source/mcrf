package transport

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

const (
	ffTypeData    = 0
	ffTypePing    = 1
	ffTypePong    = 2
	ffTypePathReq = 3
	ffTypePathRes = 4
)

func testFluxFastConfig(chunk int) FluxFastConfig {
	c := DefaultFluxFastConfig()
	c.Chunk = chunk
	c.MTU = 1280
	c.MaxRecordBytes = 64 * 1024
	c.QueueBytes = 1 << 20
	c.HandshakeTimeout = 2 * time.Second
	c.KeepAliveInterval = time.Hour
	c.DeadAfter = time.Hour
	c.ReassemblyTimeout = 300 * time.Millisecond
	c.QuietTakeover = 100 * time.Millisecond
	return c
}

func ffPacket(typ byte, connID uint64, seq uint32, frag, total byte, payload []byte) []byte {
	p := make([]byte, 20+len(payload))
	p[0], p[1] = 0xF1, 0xFA
	p[2] = byte(1<<4) | typ
	if total > 1 {
		p[3] = 1
	}
	binary.BigEndian.PutUint64(p[4:12], connID)
	binary.BigEndian.PutUint32(p[12:16], seq)
	p[16], p[17] = frag, total
	binary.BigEndian.PutUint16(p[18:20], uint16(len(payload)))
	copy(p[20:], payload)
	return p
}

func startFluxFastPair(t *testing.T, chunk int) (*FluxFastTransport, *FluxFastTransport, chan []byte, chan []byte) {
	t.Helper()
	exitCfg := testFluxFastConfig(chunk)
	exitCfg.IsExit = true
	exitCfg.ListenAddr = "127.0.0.1:0"
	exit := NewFluxFastTransport(DefaultConfig(), exitCfg)
	exitGot := make(chan []byte, 32)
	exit.Receive(func(p []byte) {
		exitGot <- append([]byte(nil), p...)
	})
	if err := exit.Start(); err != nil {
		t.Fatalf("exit start: %v", err)
	}
	t.Cleanup(func() { _ = exit.Stop() })

	clientCfg := testFluxFastConfig(chunk)
	clientCfg.DialAddr = exit.LocalAddr()
	client := NewFluxFastTransport(DefaultConfig(), clientCfg)
	clientGot := make(chan []byte, 32)
	client.Receive(func(p []byte) { clientGot <- append([]byte(nil), p...) })
	if err := client.Start(); err != nil {
		t.Fatalf("client start: %v", err)
	}
	t.Cleanup(func() { _ = client.Stop() })
	return exit, client, exitGot, clientGot
}

func waitPayload(t *testing.T, ch chan []byte, want []byte, within time.Duration) {
	t.Helper()
	select {
	case got := <-ch:
		if !bytes.Equal(got, want) {
			t.Fatalf("got %d bytes %q, want %d bytes %q", len(got), got, len(want), want)
		}
	case <-time.After(within):
		t.Fatalf("no payload within %v", within)
	}
}

func expectNoPayload(t *testing.T, ch chan []byte, within time.Duration) {
	t.Helper()
	select {
	case got := <-ch:
		t.Fatalf("unexpected payload of %d bytes", len(got))
	case <-time.After(within):
	}
}

func TestFluxFastRoundTrip(t *testing.T) {
	exit, client, exitGot, clientGot := startFluxFastPair(t, 1180)
	hello := []byte("hello over udp")
	if err := client.Send(hello); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, hello, 2*time.Second)

	reply := []byte("and back")
	if err := exit.Send(reply); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, clientGot, reply, 2*time.Second)

	deadline := time.Now().Add(time.Second)
	for !client.IsConnected() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !client.IsConnected() {
		t.Fatal("client did not become connected after hearing the exit")
	}
}

func TestFluxFastFragmentsReassemble(t *testing.T) {
	_, client, exitGot, _ := startFluxFastPair(t, 64)
	payload := bytes.Repeat([]byte("abcdefgh"), 40)
	if err := client.Send(payload); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, payload, 2*time.Second)
}

func TestFluxFastOutOfOrderAndDuplicateFragments(t *testing.T) {
	cfg := testFluxFastConfig(64)
	c := NewFluxFastTransport(DefaultConfig(), cfg)
	got := make(chan []byte, 4)
	c.Receive(func(p []byte) { got <- append([]byte(nil), p...) })

	peer := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9000}
	c.peerID = 77
	c.peerAddr = peer

	payload := bytes.Repeat([]byte("0123456789"), 25)
	total := (len(payload) + 63) / 64
	packets := make([][]byte, total)
	for i := 0; i < total; i++ {
		lo, hi := i*64, min((i+1)*64, len(payload))
		packets[i] = ffPacket(ffTypeData, 77, 5, byte(i), byte(total), payload[lo:hi])
	}
	for _, i := range []int{2, 0, 3, 1, 2, total - 1} {
		c.ingest(packets[i], peer)
	}
	waitPayload(t, got, payload, time.Second)
	expectNoPayload(t, got, 50*time.Millisecond)
}

func TestFluxFastReassemblyTimeout(t *testing.T) {
	cfg := testFluxFastConfig(64)
	cfg.ReassemblyTimeout = 50 * time.Millisecond
	c := NewFluxFastTransport(DefaultConfig(), cfg)
	got := make(chan []byte, 4)
	c.Receive(func(p []byte) { got <- append([]byte(nil), p...) })
	peer := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9001}
	c.peerID = 79
	c.peerAddr = peer

	payload := bytes.Repeat([]byte("x"), 130)
	total := (len(payload) + 63) / 64
	c.ingest(ffPacket(ffTypeData, 79, 9, 0, byte(total), payload[:64]), peer)
	c.reap(time.Now().Add(time.Second))
	c.ingest(ffPacket(ffTypeData, 79, 9, 1, byte(total), payload[64:]), peer)
	expectNoPayload(t, got, 100*time.Millisecond)
}

func TestFluxFastRejectsMalformedDatagrams(t *testing.T) {
	cfg := testFluxFastConfig(1180)
	c := NewFluxFastTransport(DefaultConfig(), cfg)
	got := make(chan []byte, 4)
	c.Receive(func(p []byte) { got <- append([]byte(nil), p...) })
	peer := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9002}
	c.peerID = 80
	c.peerAddr = peer

	good := ffPacket(ffTypeData, 80, 1, 0, 1, []byte("ok"))
	bad := [][]byte{
		{},
		{0xF1},
		append([]byte{0, 0}, good[2:]...),
		func() []byte { p := append([]byte(nil), good...); p[2] = 2 << 4; return p }(),
		func() []byte { p := append([]byte(nil), good...); binary.BigEndian.PutUint16(p[18:20], 5); return p }(),
		ffPacket(ffTypeData, 80, 2, 0, 0, []byte("x")),
		ffPacket(ffTypeData, 80, 3, 2, 1, []byte("x")),
		ffPacket(ffTypePing, 80, 4, 0, 1, []byte("payload")),
	}
	for _, p := range bad {
		c.ingest(p, peer)
	}
	expectNoPayload(t, got, 100*time.Millisecond)
}

func TestFluxFastPathChallengeOnAddressChange(t *testing.T) {
	exit, client, exitGot, _ := startFluxFastPair(t, 1180)
	hello := []byte("first path")
	if err := client.Send(hello); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, hello, 2*time.Second)

	newPeer, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer newPeer.Close()
	exitAddr, err := net.ResolveUDPAddr("udp", exit.LocalAddr())
	if err != nil {
		t.Fatal(err)
	}

	moved := []byte("after the move")
	packet := ffPacket(ffTypeData, client.connID, 42, 0, 1, moved)
	if _, err := newPeer.WriteToUDP(packet, exitAddr); err != nil {
		t.Fatal(err)
	}

	buf := make([]byte, 2048)
	_ = newPeer.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := newPeer.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no path challenge: %v", err)
	}
	if n < 20 || buf[2]&0x0F != ffTypePathReq || binary.BigEndian.Uint16(buf[18:20]) != 8 {
		t.Fatalf("expected an 8-byte path challenge, got type=%d len=%d", buf[2]&0x0F, n)
	}
	token := append([]byte(nil), buf[20:28]...)

	expectNoPayload(t, exitGot, 100*time.Millisecond)

	wrong := append([]byte(nil), token...)
	wrong[0] ^= 0xFF
	if _, err := newPeer.WriteToUDP(ffPacket(ffTypePathRes, client.connID, 0, 0, 1, wrong), exitAddr); err != nil {
		t.Fatal(err)
	}
	expectNoPayload(t, exitGot, 50*time.Millisecond)

	if _, err := newPeer.WriteToUDP(ffPacket(ffTypePathRes, client.connID, 0, 0, 1, token), exitAddr); err != nil {
		t.Fatal(err)
	}
	if _, err := newPeer.WriteToUDP(packet, exitAddr); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, moved, 2*time.Second)
}

func TestFluxFastTakeoverNeedsPathValidation(t *testing.T) {
	exit, client, exitGot, _ := startFluxFastPair(t, 1180)
	hello := []byte("old peer")
	if err := client.Send(hello); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, hello, 2*time.Second)

	newPeer, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer newPeer.Close()
	exitAddr, err := net.ResolveUDPAddr("udp", exit.LocalAddr())
	if err != nil {
		t.Fatal(err)
	}

	time.Sleep(150 * time.Millisecond) // the old peer goes quiet past QuietTakeover
	payload := []byte("new connection id")
	packet := ffPacket(ffTypeData, 0x1234567890ABCDEF, 7, 0, 1, payload)
	if _, err := newPeer.WriteToUDP(packet, exitAddr); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2048)
	_ = newPeer.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := newPeer.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no path challenge for a new connection id: %v", err)
	}
	if n < 20 || buf[2]&0x0F != ffTypePathReq {
		t.Fatalf("expected a path challenge, got type=%d", buf[2]&0x0F)
	}
	token := append([]byte(nil), buf[20:28]...)
	expectNoPayload(t, exitGot, 100*time.Millisecond)

	if _, err := newPeer.WriteToUDP(ffPacket(ffTypePathRes, 0x1234567890ABCDEF, 0, 0, 1, token), exitAddr); err != nil {
		t.Fatal(err)
	}
	if _, err := newPeer.WriteToUDP(packet, exitAddr); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, payload, 2*time.Second)
}

func TestFluxFastPingPongConnects(t *testing.T) {
	cfg := testFluxFastConfig(1180)
	cfg.IsExit = true
	cfg.ListenAddr = "127.0.0.1:0"
	cfg.KeepAliveInterval = 30 * time.Millisecond
	cfg.DeadAfter = time.Hour
	exit := NewFluxFastTransport(DefaultConfig(), cfg)
	if err := exit.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = exit.Stop() })

	ccfg := testFluxFastConfig(1180)
	ccfg.DialAddr = exit.LocalAddr()
	ccfg.KeepAliveInterval = 30 * time.Millisecond
	client := NewFluxFastTransport(DefaultConfig(), ccfg)
	if err := client.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Stop() })

	deadline := time.Now().Add(2 * time.Second)
	for !client.IsConnected() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !client.IsConnected() {
		t.Fatal("ping/pong did not connect the client")
	}
	if !exit.IsConnected() {
		t.Fatal("ping/pong did not connect the exit")
	}
}

func TestFluxFastSendTooLarge(t *testing.T) {
	_, client, _, _ := startFluxFastPair(t, 1180)
	err := client.Send(make([]byte, DefaultFluxFastConfig().MaxRecordBytes+1))
	if err == nil || !bytes.Contains([]byte(err.Error()), []byte("too large")) {
		t.Fatalf("Send oversized = %v, want ErrRecordTooLarge", err)
	}
}

func TestFluxFastRestartedClientIsServed(t *testing.T) {
	exit, old, exitGot, _ := startFluxFastPair(t, 1180)
	first := []byte("the old socket")
	if err := old.Send(first); err != nil {
		t.Fatal(err)
	}
	waitPayload(t, exitGot, first, 2*time.Second)
	_ = old.Stop()

	// The test's QuietTakeover: the exit may hand the port to a new
	// connection id only after the old one went quiet.
	time.Sleep(150 * time.Millisecond)

	cfg := testFluxFastConfig(1180)
	cfg.DialAddr = exit.LocalAddr()
	fresh := NewFluxFastTransport(DefaultConfig(), cfg)
	if err := fresh.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = fresh.Stop() })

	// Nothing is retransmitted, so keep offering the record until the exit
	// has taken the new connection id.
	payload := []byte("the restarted client")
	delivered := false
	deadline := time.Now().Add(3 * time.Second)
	for !delivered && time.Now().Before(deadline) {
		if err := fresh.Send(payload); err != nil {
			t.Fatal(err)
		}
		select {
		case got := <-exitGot:
			if !bytes.Equal(got, payload) {
				t.Fatalf("got %q, want %q", got, payload)
			}
			delivered = true
		case <-time.After(50 * time.Millisecond):
		}
	}
	if !delivered {
		t.Fatal("the exit never took the restarted client")
	}

	// The exit's confirmation is what tells the fresh client it is live
	// again; without it the carrier stays "not connected" while data flows.
	deadline = time.Now().Add(time.Second)
	for !fresh.IsConnected() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !fresh.IsConnected() {
		t.Fatal("the restarted client never heard the exit at its new connection id")
	}
}

func TestFluxFastResolveUDPAddr(t *testing.T) {
	got, err := resolveFluxFastAddr("127.0.0.1:8445", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !got.IP.Equal(net.IPv4(127, 0, 0, 1)) || got.Port != 8445 {
		t.Fatalf("resolved %v:%d", got.IP, got.Port)
	}
	v6, err := resolveFluxFastAddr("[::1]:8445", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !v6.IP.Equal(net.IPv6loopback) || v6.Port != 8445 {
		t.Fatalf("resolved %v:%d", v6.IP, v6.Port)
	}
	wild, err := resolveFluxFastAddr(":8445", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if wild.Port != 8445 || wild.IP != nil {
		t.Fatalf("wildcard resolved to %v:%d", wild.IP, wild.Port)
	}
	for _, bad := range []string{"127.0.0.1", "127.0.0.1:", "127.0.0.1:99999"} {
		if _, err := resolveFluxFastAddr(bad, time.Second); err == nil {
			t.Fatalf("resolveFluxFastAddr(%q) = nil error", bad)
		}
	}
}

func TestFluxFastCarrierInterfaces(t *testing.T) {
	cfg := testFluxFastConfig(100)
	t1 := NewFluxFastTransport(DefaultConfig(), cfg)
	if got := t1.CarrierMTU(); got != 1280 {
		t.Fatalf("CarrierMTU = %d, want 1280", got)
	}
	if got := t1.PreferredBatchBytes(); got != 300 {
		t.Fatalf("PreferredBatchBytes = %d, want 300", got)
	}
	if !t1.CheapBytes() {
		t.Fatal("CheapBytes = false")
	}
	if !t1.Preferred() {
		t.Fatal("Preferred = false")
	}
}
