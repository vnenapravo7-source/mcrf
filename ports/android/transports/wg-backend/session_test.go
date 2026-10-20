package wgbackend

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"github.com/sagernet/wireguard-go/tun"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// A private packet peer, not an OS interface: host tests need neither admin nor TUN.
type packetTun struct {
	input, output chan []byte
	done          chan struct{}
	events        chan tun.Event
	once          sync.Once
}

func newPacketTun() *packetTun {
	return &packetTun{input: make(chan []byte, 8), output: make(chan []byte, 8), done: make(chan struct{}), events: make(chan tun.Event)}
}
func (t *packetTun) File() *os.File           { return nil }
func (t *packetTun) MTU() (int, error)        { return 1280, nil }
func (t *packetTun) Name() (string, error)    { return "mcrf-owned-packet-peer", nil }
func (t *packetTun) Events() <-chan tun.Event { return t.events }
func (t *packetTun) BatchSize() int           { return 1 }
func (t *packetTun) Close() error             { t.once.Do(func() { close(t.done); close(t.events) }); return nil }
func (t *packetTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case packet := <-t.input:
		sizes[0] = copy(bufs[0][offset:], packet)
		return 1, nil
	case <-t.done:
		return 0, os.ErrClosed
	}
}
func (t *packetTun) Write(bufs [][]byte, offset int) (int, error) {
	for i, buf := range bufs {
		packet := append([]byte(nil), buf[offset:]...)
		select {
		case t.output <- packet:
		case <-t.done:
			return i, os.ErrClosed
		}
	}
	return len(bufs), nil
}

type preparation struct {
	allow bool
	calls atomic.Int32
}

func (p *preparation) PrepareSocket(fd int32) bool { p.calls.Add(1); return p.allow && fd >= 0 }
func privateKey(t *testing.T) (string, string) {
	t.Helper()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal("Owned key generation failed")
	}
	return hex.EncodeToString(key.Bytes()), hex.EncodeToString(key.PublicKey().Bytes())
}
func listenPort(t *testing.T, s *Session) int {
	t.Helper()
	state, err := s.core.IpcGet()
	if err != nil {
		t.Fatal("Owned device status unavailable")
	}
	for _, line := range strings.Split(state, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port, err := strconv.Atoi(strings.TrimPrefix(line, "listen_port="))
			if err == nil && port > 0 {
				return port
			}
		}
	}
	t.Fatal("Owned UDP port unavailable")
	return 0
}
func packet(from, to byte) []byte {
	p := make([]byte, 64)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	p[8] = 64
	p[9] = 17
	copy(p[12:16], []byte{10, 77, 0, from})
	copy(p[16:20], []byte{10, 77, 0, to})
	copy(p[20:], []byte("MCRF owned encrypted roundtrip"))
	return p
}
func packet6(from, to byte) []byte {
	p := make([]byte, 96)
	p[0] = 0x60
	binary.BigEndian.PutUint16(p[4:6], uint16(len(p)-40))
	p[6], p[7] = 17, 64
	p[8], p[9], p[23] = 0xfd, 0x77, from
	p[24], p[25], p[39] = 0xfd, 0x77, to
	copy(p[40:], []byte("MCRF owned encrypted IPv6 roundtrip"))
	return p
}
func roundtrip(t *testing.T, awg string) {
	t.Helper()
	privA, pubA := privateKey(t)
	privB, pubB := privateKey(t)
	ta, tb := newPacketTun(), newPacketTun()
	pa, pb := &preparation{allow: true}, &preparation{allow: true}
	ba, _ := protectedBind(pa)
	bb, _ := protectedBind(pb)
	a, err := startDevice(ta, ba, "private_key="+privA+"\nlisten_port=0\n"+awg+"replace_peers=true\npublic_key="+pubB+"\nallowed_ip=10.77.0.2/32\nallowed_ip=fd77::2/128\n")
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := startDevice(tb, bb, fmt.Sprintf("private_key=%s\nlisten_port=0\n%sreplace_peers=true\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=10.77.0.1/32\nallowed_ip=fd77::1/128\n", privB, awg, pubA, listenPort(t, a)))
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if a.core.IpcSet(fmt.Sprintf("public_key=%s\nendpoint=127.0.0.1:%d\n", pubB, listenPort(t, b))) != nil {
		t.Fatal("Owned peer endpoint update failed")
	}
	for _, flow := range []struct {
		source, destination *packetTun
		from, to            byte
	}{{ta, tb, 1, 2}, {tb, ta, 2, 1}} {
		for _, sent := range [][]byte{packet(flow.from, flow.to), packet6(flow.from, flow.to)} {
			flow.source.input <- sent
			select {
			case received := <-flow.destination.output:
				if !bytes.Equal(sent, received) {
					t.Fatal("Encrypted roundtrip payload changed")
				}
			case <-time.After(5 * time.Second):
				t.Fatal("Owned encrypted UDP roundtrip timeout")
			}
		}
	}
	if pa.calls.Load() == 0 || pb.calls.Load() == 0 {
		t.Fatal("UDP sockets bypassed prepare callback")
	}
	// Authenticated transport is insufficient: peer source must also match AllowedIPs.
	for _, forged := range [][]byte{packet(3, 2), packet6(3, 2)} {
		ta.input <- forged
		select {
		case <-tb.output:
			t.Fatal("Peer accepted a source outside AllowedIPs")
		case <-time.After(100 * time.Millisecond):
		}
	}
	// A captured destination deliberately omitted from the sole peer's AllowedIPs
	// must be dropped, not sent to that peer. Probe both inner address families
	// after the working encrypted path above, so an unestablished peer cannot pass.
	for _, blocked := range [][]byte{packet(1, 3), packet6(1, 3)} {
		ta.input <- blocked
		select {
		case <-tb.output:
			t.Fatal("Peer received a destination omitted from AllowedIPs")
		case <-time.After(150 * time.Millisecond):
		}
	}
	// Dropping blocked traffic must not break a later permitted packet.
	for _, allowed := range [][]byte{packet(1, 2), packet6(1, 2)} {
		ta.input <- allowed
		select {
		case received := <-tb.output:
			if !bytes.Equal(allowed, received) {
				t.Fatal("Blocked destination escaped or permitted payload changed")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("Permitted traffic failed after a blocked destination")
		}
	}
	a.Close()
	a.Close()
	b.Close()
	select {
	case <-ta.done:
	default:
		t.Fatal("TUN duplicate not closed")
	}
	select {
	case <-tb.done:
	default:
		t.Fatal("Second TUN duplicate not closed")
	}
}
func TestWireGuardEncryptedUDP(t *testing.T) { roundtrip(t, "") }
func TestAmneziaWGEncryptedUDP(t *testing.T) {
	roundtrip(t, "jc=0\njmin=40\njmax=80\ns1=15\ns2=20\nh1=123456\nh2=234567\nh3=345678\nh4=456789\n")
}
func TestAmneziaVariantsEncryptedDualStack(t *testing.T) {
	base := "jc=0\njmin=40\njmax=80\ns1=16\ns2=20\ns3=16\ns4=16\nh1=123456-123460\nh2=234567-234570\nh3=345678-345680\nh4=456789-456790\ni1=<r 8>\n"
	header := "header_protection_key=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20\ncontent_padding_addition=0-8\n"
	for _, variant := range []struct{ name, fields string }{
		{"AWG2", base},
		{"AWG3", base + header},
		{"AWG3_1", base + header + "random_trailers=true\ndisable_cookies=true\nrekey_after_time=120\nrekey_timeout=5\nreject_after_time=180\nkeepalive_timeout=10\nmax_handshake_attempts=5\n"},
	} {
		t.Run(variant.name, func(t *testing.T) { roundtrip(t, variant.fields) })
	}
}
func TestRejectedProtectCannotStartAndClosesTun(t *testing.T) {
	p := &preparation{}
	bind, _ := protectedBind(p)
	tunnel := newPacketTun()
	private, _ := privateKey(t)
	session, err := startDevice(tunnel, bind, "private_key="+private+"\nlisten_port=0\n")
	if session != nil || err == nil || p.calls.Load() == 0 {
		t.Fatal("Rejected socket preparation accepted")
	}
	select {
	case <-tunnel.done:
	default:
		t.Fatal("Failure leaked owned TUN")
	}
}
func TestInvalidConfigurationIsSanitizedAndClosed(t *testing.T) {
	bind, _ := protectedBind(&preparation{allow: true})
	tunnel := newPacketTun()
	session, err := startDevice(tunnel, bind, "private_key=private-test-secret\n")
	if session != nil || err == nil || err.Error() != "WG_CONFIG_REJECTED" {
		t.Fatal("Config rejection leaked raw input")
	}
	select {
	case <-tunnel.done:
	default:
		t.Fatal("Invalid config leaked TUN")
	}
}
func TestMissingProtectorAndOversizeRefused(t *testing.T) {
	if _, err := protectedBind(nil); err == nil {
		t.Fatal("Missing protector accepted")
	}
	bind, _ := protectedBind(&preparation{allow: true})
	tunnel := newPacketTun()
	if _, err := startDevice(tunnel, bind, strings.Repeat("x", 65537)); err == nil {
		t.Fatal("Oversize config accepted")
	}
	select {
	case <-tunnel.done:
	default:
		t.Fatal(errors.New("Oversize config leaked owned TUN"))
	}
}
