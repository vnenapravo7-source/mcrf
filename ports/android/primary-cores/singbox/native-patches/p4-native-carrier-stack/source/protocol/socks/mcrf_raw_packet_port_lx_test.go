//go:build with_lx_raw_packet_port && with_lx_raw_packet && (linux || android)

package socks

import (
	"context"
	"encoding/binary"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	"net/netip"
	"sync"
	"testing"
	"time"
)

type portEndpointFixture struct {
	mu      sync.Mutex
	blocks  bool
	sent    [][]byte
	replies chan []byte
}

type portJudgeFixture struct {
	tun.Handler
	port        tun.Port
	destination netip.AddrPort
	calls       int
}

func (j *portJudgeFixture) JudgeFlow(_ uint8, _ netip.AddrPort, d netip.AddrPort, _ []byte) tun.FlowVerdict {
	j.calls++
	if d.Port() == 9 {
		return tun.FlowVerdict{Action: tun.ActionAccept}
	}
	return tun.FlowVerdict{Action: tun.ActionFlow, Port: j.port, Destination: j.destination}
}

type portWritebackFixture struct{ received chan []byte }

func (*portWritebackFixture) ReturnHeadroom() int { return 0 }
func (w *portWritebackFixture) WriteReturnPackets(p [][]byte) error {
	for _, b := range p {
		w.received <- append([]byte(nil), b...)
	}
	return nil
}
func TestOriginalPortNativeDispatcherNATAndFallback(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	endpoint := &portEndpointFixture{replies: make(chan []byte, 4)}
	o := &originalPacketOutbound{ctx: ctx, stop: cancel, endpoint: endpoint, start: 2896, count: 1872}
	judge := &portJudgeFixture{port: o, destination: netip.MustParseAddrPort("198.51.100.2:443")}
	back := &portWritebackFixture{received: make(chan []byte, 4)}
	dispatcher := tun.NewForwardDispatcher(judge, back, logger.NOP(), time.Minute, time.Minute)
	defer dispatcher.Close()
	packet := make([]byte, 31)
	packet[0] = 0x45
	packet[8] = 64
	packet[9] = 17
	binary.BigEndian.PutUint16(packet[2:4], 31)
	copy(packet[12:16], []byte{172, 19, 0, 2})
	copy(packet[16:20], []byte{198, 18, 0, 1})
	binary.BigEndian.PutUint16(packet[20:22], 41000)
	binary.BigEndian.PutUint16(packet[22:24], 443)
	binary.BigEndian.PutUint16(packet[24:26], 11)
	copy(packet[28:], []byte{1, 2, 3})
	if !dispatcher.Dispatch(packet) {
		t.Fatal("native route not admitted")
	}
	dispatcher.Flush()
	if len(endpoint.sent) != 1 {
		t.Fatal("not sent")
	}
	sent := endpoint.sent[0]
	selector := binary.BigEndian.Uint16(sent[20:22])
	if selector < 2896 || selector >= 4768 || string(sent[12:16]) != string([]byte{10, 0, 0, 2}) || string(sent[16:20]) != string([]byte{198, 51, 100, 2}) || string(sent[28:]) != string([]byte{1, 2, 3}) {
		t.Fatal("native NAT/selector/destination corrupted")
	}
	reply := append([]byte(nil), sent...)
	copy(reply[12:16], sent[16:20])
	copy(reply[16:20], sent[12:16])
	copy(reply[20:22], sent[22:24])
	copy(reply[22:24], sent[20:22])
	endpoint.replies <- reply
	select {
	case received := <-back.received:
		if string(received[12:16]) != string([]byte{198, 18, 0, 1}) || string(received[16:20]) != string([]byte{172, 19, 0, 2}) || binary.BigEndian.Uint16(received[22:24]) != 41000 || string(received[28:]) != string([]byte{1, 2, 3}) {
			t.Fatal("reverse NAT did not restore original tuple")
		}
	case <-time.After(time.Second):
		t.Fatal("missing return")
	}
	other := append([]byte(nil), packet...)
	// Real dispatcher reverse-NAT must also restore the tuple quoted by PMTU ICMP.
	icmp := make([]byte, 28+len(sent))
	icmp[0] = 0x45
	icmp[8] = 64
	icmp[9] = 1
	binary.BigEndian.PutUint16(icmp[2:4], uint16(len(icmp)))
	copy(icmp[12:16], sent[16:20])
	copy(icmp[16:20], sent[12:16])
	icmp[20] = 3
	icmp[21] = 4
	binary.BigEndian.PutUint16(icmp[26:28], 1280)
	copy(icmp[28:], sent)
	endpoint.replies <- icmp
	select {
	case received := <-back.received:
		if received[9] != 1 || string(received[16:20]) != string([]byte{172, 19, 0, 2}) || string(received[40:44]) != string([]byte{172, 19, 0, 2}) || binary.BigEndian.Uint16(received[48:50]) != 41000 {
			t.Fatal("PMTU error lost original tuple")
		}
	case <-time.After(time.Second):
		t.Fatal("native PMTU feedback dropped")
	}
	binary.BigEndian.PutUint16(other[22:24], 9)
	if dispatcher.Dispatch(other) {
		t.Fatal("ordinary route diverted")
	}
	dispatcher.Flush()
	if len(endpoint.sent) != 1 {
		t.Fatal("fallback reached raw backend")
	}
	if judge.calls != 2 {
		t.Fatal("flow policy was bypassed")
	}
}

func (*portEndpointFixture) Identity() string         { return "fixture" }
func (*portEndpointFixture) Generation() uint64       { return 1 }
func (*portEndpointFixture) LocalAddress() netip.Addr { return netip.MustParseAddr("10.0.0.2") }
func (*portEndpointFixture) MTU() int                 { return 1400 }
func (e *portEndpointFixture) SendOriginalPacket(ctx context.Context, p []byte) (adapter.RawPacketSendState, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.blocks {
		return adapter.RawPacketWouldBlock, nil
	}
	e.sent = append(e.sent, append([]byte(nil), p...))
	return adapter.RawPacketAccepted, nil
}
func (e *portEndpointFixture) ReadOriginalPacket(ctx context.Context) ([]byte, error) {
	select {
	case p := <-e.replies:
		return p, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

type portReturnFixture struct{ received chan []byte }

func (*portReturnFixture) ReturnHeadroom() int { return 4 }
func (r *portReturnFixture) ReturnPackets(p [][]byte) [][]byte {
	for _, b := range p {
		r.received <- append([]byte(nil), b...)
	}
	return nil
}
func newPortFixture(t *testing.T) (*originalPacketOutbound, *portEndpointFixture, *portReturnFixture) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	e := &portEndpointFixture{replies: make(chan []byte, 4)}
	r := &portReturnFixture{received: make(chan []byte, 4)}
	o := &originalPacketOutbound{ctx: ctx, stop: cancel, endpoint: e, start: 1024, count: 1872}
	if err := o.AttachReturn(r); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { o.Close() })
	return o, e, r
}
func TestOriginalPortBackpressureBoundedAndCloseRetires(t *testing.T) {
	o, e, r := newPortFixture(t)
	e.blocks = true
	begin := time.Now()
	err := o.WritePackets([][]byte{{1, 2, 3}})
	if !errors.Is(err, context.DeadlineExceeded) || time.Since(begin) > time.Second {
		t.Fatal("unbounded congested write", err)
	}
	if err = o.DetachReturn(r); err != nil {
		t.Fatal(err)
	}
	if o.PreMatchFlow("tcp", netip.MustParseAddr("198.51.100.1")) != adapter.PreMatchContinue {
		t.Fatal("retired endpoint admitted new flow")
	}
	if err = o.WritePackets([][]byte{{1}}); err == nil {
		t.Fatal("write after retirement")
	}
}

func TestOriginalPortCloseBeforeAttachmentCannotAdmitFlow(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	o := &originalPacketOutbound{ctx: ctx, stop: cancel, endpoint: &portEndpointFixture{}, start: 1024, count: 1872}
	if err := o.Close(); err != nil {
		t.Fatal(err)
	}
	if o.PreMatchFlow("tcp", netip.MustParseAddr("198.51.100.1")) != adapter.PreMatchContinue {
		t.Fatal("closed unbound endpoint admitted flow")
	}
	if err := o.AttachReturn(&portReturnFixture{}); err == nil {
		t.Fatal("closed endpoint reattached")
	}
}
func TestOriginalPortPreservesPacketAndRejectsForeignReplySlot(t *testing.T) {
	o, e, r := newPortFixture(t)
	packet := make([]byte, 28)
	packet[0] = 0x45
	packet[9] = 17
	binary.BigEndian.PutUint16(packet[2:4], 28)
	binary.BigEndian.PutUint16(packet[24:26], 8)
	binary.BigEndian.PutUint16(packet[22:24], 1024+1872)
	e.replies <- append([]byte(nil), packet...)
	binary.BigEndian.PutUint16(packet[22:24], 1024)
	e.replies <- append([]byte(nil), packet...)
	select {
	case received := <-r.received:
		if string(received[4:]) != string(packet) {
			t.Fatal("wrong packet or headroom")
		}
	case <-time.After(time.Second):
		t.Fatal("missing owned reply")
	}
	select {
	case <-r.received:
		t.Fatal("foreign slot crossed backend")
	case <-time.After(20 * time.Millisecond):
	}
	if err := o.WritePackets([][]byte{packet}); err != nil {
		t.Fatal(err)
	}
	packet[0] = 0
	if len(e.sent) != 1 || e.sent[0][0] != 0x45 {
		t.Fatal("packet not preserved")
	}
	if o.PreMatchFlow("tcp", netip.MustParseAddr("2001:db8::1")) != adapter.PreMatchContinue {
		t.Fatal("IPv6 admitted")
	}
}
