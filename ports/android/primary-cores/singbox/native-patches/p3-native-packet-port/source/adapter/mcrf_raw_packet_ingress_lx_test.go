//go:build with_lx_raw_packet && (linux || android)

package adapter

import (
	"context"
	"errors"
	"net/netip"
	"sync"
	"testing"
	"time"

	singtun "github.com/sagernet/sing-tun"
)

type rawIngressTestRouter struct {
	Router
	result PreMatchResult
	seen   InboundContext
	first  []byte
	calls  int
}

func (r *rawIngressTestRouter) PreMatch(metadata InboundContext, first []byte) PreMatchResult {
	r.calls++
	r.seen = metadata
	r.first = append([]byte(nil), first...)
	return r.result
}

type rawIngressTestOutbound struct {
	Outbound
	singtun.Port
	tag string
}

func (o *rawIngressTestOutbound) Tag() string { return o.tag }
func (o *rawIngressTestOutbound) PreMatchFlow(string, netip.Addr) PreMatchAction { return PreMatchFlow }
var _ FlowOutbound = (*rawIngressTestOutbound)(nil)

type rawIngressTestProvider struct {
	endpoint OriginalPacketEndpoint
	enabled  bool
}

func (p *rawIngressTestProvider) HasOriginalPacketEndpoints() bool { return p.enabled }
func (p *rawIngressTestProvider) OriginalPacketEndpoint(tag string) (OriginalPacketEndpoint, bool) {
	return p.endpoint, p.endpoint != nil && tag == "bs-one"
}

type rawIngressTestEndpoint struct {
	blocks     int
	mu         sync.Mutex
	accepted   [][]byte
	replies    chan []byte
	local      netip.Addr
	generation uint64
	closed     chan struct{}
}

func (e *rawIngressTestEndpoint) Identity() string { return "owned-endpoint-1" }
func (e *rawIngressTestEndpoint) Generation() uint64 {
	if e.generation == 0 {
		return 7
	}
	return e.generation
}
func (e *rawIngressTestEndpoint) LocalAddress() netip.Addr { return e.local }
func (e *rawIngressTestEndpoint) MTU() int                 { return 1400 }
func (e *rawIngressTestEndpoint) SendOriginalPacket(_ context.Context, p []byte) (RawPacketSendState, error) {
	e.mu.Lock()
	if e.blocks != 0 {
		if e.blocks > 0 {
			e.blocks--
		}
		e.mu.Unlock()
		return RawPacketWouldBlock, nil
	}
	e.accepted = append(e.accepted, append([]byte(nil), p...))
	e.mu.Unlock()
	return RawPacketAccepted, nil
}
func (e *rawIngressTestEndpoint) ReadOriginalPacket(ctx context.Context) ([]byte, error) {
	select {
	case p := <-e.replies:
		return p, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-e.closed:
		return nil, errors.New("closed")
	}
}

func rawIngressTestIPv4(src, dst [4]byte, protocol byte, payload []byte) []byte {
	p := make([]byte, 20+len(payload))
	p[0] = 0x45
	p[2] = byte(len(p) >> 8)
	p[3] = byte(len(p))
	p[8] = 64
	p[9] = protocol
	copy(p[12:16], src[:])
	copy(p[16:20], dst[:])
	copy(p[20:], payload)
	return p
}
func rawIngressTestUDP(src, dst uint16, data ...byte) []byte {
	length := 8 + len(data)
	p := []byte{byte(src >> 8), byte(src), byte(dst >> 8), byte(dst), byte(length >> 8), byte(length), 0, 0}
	return append(p, data...)
}

func TestRawPacketIngressBackpressureRetainsPacketAndNeverFallsThrough(t *testing.T) {
	for _, blocked := range []int{3, -1} {
		endpoint := &rawIngressTestEndpoint{blocks: blocked, local: netip.MustParseAddr("10.0.0.2"), replies: make(chan []byte), closed: make(chan struct{})}
		router := &rawIngressTestRouter{result: PreMatchResult{Action: PreMatchFlow, Outbound: &rawIngressTestOutbound{tag: "bs-one"}}}
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		ingress, err := NewRawPacketIngress(ctx, router, &rawIngressTestProvider{endpoint: endpoint, enabled: true}, "tun", "tun", func([]byte) error { return nil })
		if err != nil {
			cancel()
			t.Fatal(err)
		}
		packet := rawIngressTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 4}, 17, rawIngressTestUDP(41000, 443, 1, 2, 3))
		consumed, err := ingress.DispatchOriginal(packet)
		if blocked > 0 {
			if !consumed || err != nil || len(endpoint.accepted) != 1 || string(endpoint.accepted[0]) != string(packet) {
				t.Fatal("backpressure changed/lost packet", consumed, err)
			}
		} else if consumed || !errors.Is(err, context.DeadlineExceeded) || len(endpoint.accepted) != 0 {
			t.Fatal("cancelled pinned packet fell through", consumed, err)
		}
		if router.calls != 1 {
			t.Fatal("route was reevaluated under pressure")
		}
		cancel()
		ingress.Close()
	}
}

func TestRawPacketIngressUsesOrderedMaincorePreMatchAndReturnsPinnedReplies(t *testing.T) {
	local := netip.MustParseAddr("10.0.0.2")
	endpoint := &rawIngressTestEndpoint{local: local, replies: make(chan []byte, 1), closed: make(chan struct{})}
	outbound := &rawIngressTestOutbound{tag: "bs-one"}
	router := &rawIngressTestRouter{result: PreMatchResult{Action: PreMatchFlow, Outbound: outbound}}
	provider := &rawIngressTestProvider{endpoint: endpoint, enabled: true}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var written [][]byte
	var writeMu sync.Mutex
	ingress, err := NewRawPacketIngress(ctx, router, provider, "tun-in", "tun", func(p []byte) error {
		writeMu.Lock()
		written = append(written, append([]byte(nil), p...))
		writeMu.Unlock()
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ingress.Close()
	packet := rawIngressTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 4}, 17, rawIngressTestUDP(41000, 443, 1, 2, 3))
	consumed, err := ingress.DispatchOriginal(packet)
	if err != nil || !consumed {
		t.Fatalf("dispatch consumed=%v err=%v", consumed, err)
	}
	if router.calls != 1 || router.seen.Inbound != "tun-in" || router.seen.InboundType != "tun" || router.seen.Network != "udp" || router.seen.Destination.Port != 443 || string(router.first) != string(packet[28:]) {
		t.Fatalf("PreMatch metadata/payload mismatch: calls=%d metadata=%+v first=%v", router.calls, router.seen, router.first)
	}
	if len(endpoint.accepted) != 1 || string(endpoint.accepted[0]) != string(packet) {
		t.Fatal("original IP bytes were not sent intact")
	}
	reply := rawIngressTestIPv4([4]byte{198, 51, 100, 4}, [4]byte{10, 0, 0, 2}, 17, rawIngressTestUDP(443, 41000, 9))
	endpoint.replies <- reply
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		writeMu.Lock()
		n := len(written)
		writeMu.Unlock()
		if n > 0 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	writeMu.Lock()
	defer writeMu.Unlock()
	if len(written) != 1 || string(written[0]) != string(reply) {
		t.Fatalf("reverse packet not reinjected unchanged: %d", len(written))
	}
	if consumed, err = ingress.DispatchOriginal(packet); err != nil || !consumed || router.calls != 1 {
		t.Fatalf("pinned flow re-evaluated or changed route: consumed=%v err=%v calls=%d", consumed, err, router.calls)
	}
}

func TestRawPacketIngressFallsThroughForNonFlowRewriteOrMissingEndpoint(t *testing.T) {
	packet := rawIngressTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 4}, 17, rawIngressTestUDP(41000, 443))
	outbound := &rawIngressTestOutbound{tag: "bs-one"}
	for _, tc := range []struct {
		name     string
		result   PreMatchResult
		provider *rawIngressTestProvider
	}{
		{"continue", PreMatchResult{Action: PreMatchContinue}, &rawIngressTestProvider{enabled: true}},
		{"rewrite", PreMatchResult{Action: PreMatchFlow, Outbound: outbound, Destination: netip.MustParseAddrPort("203.0.113.9:443")}, &rawIngressTestProvider{enabled: true}},
		{"no-raw-endpoint", PreMatchResult{Action: PreMatchFlow, Outbound: outbound}, &rawIngressTestProvider{enabled: true}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			router := &rawIngressTestRouter{result: tc.result}
			ingress, err := NewRawPacketIngress(context.Background(), router, tc.provider, "tun", "tun", func([]byte) error { return nil })
			if err != nil {
				t.Fatal(err)
			}
			defer ingress.Close()
			consumed, err := ingress.DispatchOriginal(packet)
			if err != nil || consumed {
				t.Fatalf("should remain in maincore: consumed=%v err=%v", consumed, err)
			}
		})
	}
}

func TestRawPacketIngressRejectsPinnedEndpointChangeAndMalformedPackets(t *testing.T) {
	local := netip.MustParseAddr("10.0.0.2")
	endpoint := &rawIngressTestEndpoint{local: local, replies: make(chan []byte), closed: make(chan struct{})}
	router := &rawIngressTestRouter{result: PreMatchResult{Action: PreMatchFlow, Outbound: &rawIngressTestOutbound{tag: "bs-one"}}}
	ingress, err := NewRawPacketIngress(context.Background(), router, &rawIngressTestProvider{endpoint: endpoint, enabled: true}, "tun", "tun", func([]byte) error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer ingress.Close()
	packet := rawIngressTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 4}, 17, rawIngressTestUDP(41000, 443))
	if consumed, err := ingress.DispatchOriginal(packet); err != nil || !consumed {
		t.Fatalf("first dispatch consumed=%v err=%v", consumed, err)
	}
	bad := append([]byte(nil), packet...)
	bad[3]--
	if consumed, err := ingress.DispatchOriginal(bad); err != nil || consumed {
		t.Fatalf("malformed packet diverted: consumed=%v err=%v", consumed, err)
	}
	endpoint.generation = 8
	if _, err := ingress.DispatchOriginal(packet); err == nil {
		t.Fatal("pinned flow accepted a replaced endpoint generation")
	}
}
