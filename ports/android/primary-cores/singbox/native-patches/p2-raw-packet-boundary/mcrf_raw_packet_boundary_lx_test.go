//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"net/netip"
	"testing"
)

type mcrfRawTestMaincore struct {
	decision      mcrfRawDecision
	byDestination map[uint16]mcrfRawDecision
	packets       [][]byte
	metadata      []mcrfRawPacketMetadata
	replies       [][]byte
	uid           []int
	evalErr       error
	returnNil     bool
	evaluations   int
	continued     int
}

type mcrfRawTestEvaluation struct {
	main     *mcrfRawTestMaincore
	decision mcrfRawDecision
	metadata mcrfRawPacketMetadata
}

func (e *mcrfRawTestEvaluation) Decision() mcrfRawDecision { return e.decision }
func (e *mcrfRawTestEvaluation) ContinueOriginal(ctx context.Context, packet []byte) error {
	e.main.continued++
	return e.main.ProcessOriginal(ctx, packet, e.metadata)
}
func (m *mcrfRawTestMaincore) EvaluateOriginal(_ context.Context, _ []byte, flow mcrfRawPacketFlow, metadata mcrfRawPacketMetadata) (mcrfRawPolicyEvaluation, error) {
	m.evaluations++
	if m.evalErr != nil {
		return nil, m.evalErr
	}
	if m.returnNil {
		return nil, nil
	}
	decision := m.decision
	if selected, ok := m.byDestination[flow.DestinationPort]; ok {
		decision = selected
	}
	return &mcrfRawTestEvaluation{main: m, decision: decision, metadata: metadata}, nil
}
func (m *mcrfRawTestMaincore) ProcessOriginal(_ context.Context, packet []byte, metadata mcrfRawPacketMetadata) error {
	m.packets = append(m.packets, append([]byte(nil), packet...))
	m.metadata = append(m.metadata, metadata)
	return nil
}
func (m *mcrfRawTestMaincore) InjectOriginalReply(_ context.Context, packet []byte, uid int) error {
	m.replies = append(m.replies, append([]byte(nil), packet...))
	m.uid = append(m.uid, uid)
	return nil
}

type mcrfRawTestBackend struct {
	id          string
	identity    string
	packets     [][]byte
	err         error
	state       mcrfRawSendState
	accept      bool
	acceptCalls int
}

func (b *mcrfRawTestBackend) RawEndpointID() string { return b.id }
func (b *mcrfRawTestBackend) RawEndpointIdentity() string {
	if b.identity != "" {
		return b.identity
	}
	return "fd:" + b.id
}
func (b *mcrfRawTestBackend) AcceptsOriginalPacket(_ mcrfRawPacketFlow, _ []byte) bool {
	b.acceptCalls++
	return b.accept
}
func (b *mcrfRawTestBackend) SendRawPacket(_ context.Context, _ uint64, packet []byte) (mcrfRawSendState, error) {
	b.packets = append(b.packets, append([]byte(nil), packet...))
	return b.state, b.err
}

func mcrfRawTestIPv4(src, dst [4]byte, protocol byte, transport []byte) []byte {
	packet := make([]byte, 20+len(transport))
	packet[0] = 0x45
	packet[2] = byte(len(packet) >> 8)
	packet[3] = byte(len(packet))
	packet[8] = 64
	packet[9] = protocol
	copy(packet[12:16], src[:])
	copy(packet[16:20], dst[:])
	copy(packet[20:], transport)
	return packet
}
func mcrfRawTestUDP(src, dst uint16) []byte {
	return []byte{byte(src >> 8), byte(src), byte(dst >> 8), byte(dst), 0, 8, 0, 0}
}

func TestMcrfRawPacketBoundaryUsesFinalMaincoreDecisionAndPreservesBytes(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 53))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a", NeedsMetadata: mcrfRawMetaUID | mcrfRawMetaDNS | mcrfRawMetaDomain}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{Known: mcrfRawMetaUID | mcrfRawMetaDNS | mcrfRawMetaDomain, UID: 10123, DNS: true, Domain: "example.test"})
	if err != nil || outcome != mcrfRawBackendAccepted || len(main.packets) != 0 || len(backend.packets) != 1 || string(backend.packets[0]) != string(packet) {
		t.Fatalf("outcome=%v err=%v main=%d backend=%d packetChanged=%v", outcome, err, len(main.packets), len(backend.packets), len(backend.packets) == 1 && string(backend.packets[0]) != string(packet))
	}
}

func TestMcrfRawPacketBoundaryKeepsAddressOrMTUMismatchInMaincore(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", state: mcrfRawSendAccepted, accept: false}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore || len(main.packets) != 1 || len(backend.packets) != 0 {
		t.Fatalf("incompatible backend was selected: outcome=%v err=%v main=%d backend=%d", outcome, err, len(main.packets), len(backend.packets))
	}
}

func TestMcrfRawPacketBoundaryReportsBackpressureWithoutFailover(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendWouldBlock}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); outcome != mcrfRawBackendBackpressure || !errors.Is(err, errMcrfRawBackpressure) || len(main.packets) != 0 {
		t.Fatalf("backpressure failed over: outcome=%v err=%v main=%d", outcome, err, len(main.packets))
	}
}

func TestMcrfRawPacketBoundaryRejectsSharedEndpointIdentity(t *testing.T) {
	main := &mcrfRawTestMaincore{}
	a := &mcrfRawTestBackend{id: "00000000000000000000000000000001", identity: "fd:shared"}
	b := &mcrfRawTestBackend{id: "00000000000000000000000000000002", identity: "fd:shared"}
	if _, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": a, "csqtt-b": b}, 1, 8); err == nil {
		t.Fatal("shared packet endpoint admitted for two backends")
	}
}

func TestMcrfRawPacketBoundaryRejectsUnsupportedGenerationRange(t *testing.T) {
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001"}
	if _, err := newMcrfRawPacketBoundary(&mcrfRawTestMaincore{}, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1<<63, 1); err == nil {
		t.Fatal("generation outside native signed 63-bit contract accepted")
	}
}

func TestMcrfRawPacketBoundaryKeepsUnknownRequiredMetadataInMaincore(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	for _, tc := range []struct {
		name string
		mask mcrfRawMetadataMask
	}{
		{"uid", mcrfRawMetaUID}, {"domain", mcrfRawMetaDomain}, {"dns", mcrfRawMetaDNS},
		{"fakeip", mcrfRawMetaFakeIP}, {"sniff", mcrfRawMetaSniff},
	} {
		t.Run(tc.name, func(t *testing.T) {
			main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a", NeedsMetadata: tc.mask}}
			backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
			boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
			if err != nil {
				t.Fatal(err)
			}
			outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{})
			if err != nil || outcome != mcrfRawStayedInMaincore || len(main.packets) != 1 || main.evaluations != 1 || main.continued != 1 || len(backend.packets) != 0 {
				t.Fatalf("outcome=%v err=%v main=%d eval=%d continued=%d backend=%d", outcome, err, len(main.packets), main.evaluations, main.continued, len(backend.packets))
			}
		})
	}
}

func TestMcrfRawPacketBoundaryRejectsUIDChangeAndDoesNotFallbackAfterSendFailure(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendRejected, err: errors.New("send failed")}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	if outcome, sendErr := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{Known: mcrfRawMetaUID, UID: 10123}); outcome != mcrfRawBackendRejected || sendErr == nil || len(main.packets) != 0 {
		t.Fatalf("send failure fell back: outcome=%v err=%v main=%d", outcome, sendErr, len(main.packets))
	}
	backend.err = nil
	backend.state = mcrfRawSendAccepted
	if _, err = boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{Known: mcrfRawMetaUID, UID: 10124}); !errors.Is(err, errMcrfRawUIDChanged) || len(main.packets) != 0 {
		t.Fatalf("UID mutation was rerouted: err=%v main=%d", err, len(main.packets))
	}
}

func TestMcrfRawPacketBoundaryRechecksPinnedPacketAdmissionWithoutFallback(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil {
		t.Fatal(err)
	}
	backend.accept = false // The pinned backend no longer admits this packet's source/family/MTU.
	if outcome, sendErr := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); outcome != mcrfRawBackendRejected || !errors.Is(sendErr, errMcrfRawPinnedPacketRejected) {
		t.Fatalf("pinned packet was not rejected fail-closed: outcome=%v err=%v", outcome, sendErr)
	}
	if backend.acceptCalls != 2 || len(backend.packets) != 1 || len(main.packets) != 0 {
		t.Fatalf("admission/fallback mismatch: checks=%d sent=%d maincore=%d", backend.acceptCalls, len(backend.packets), len(main.packets))
	}
}

func TestMcrfRawPacketBoundaryKeepsMaincoreFlowThereAfterPolicyChange(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore {
		t.Fatalf("first outcome=%v err=%v", outcome, err)
	}
	main.decision = mcrfRawDecision{Final: true, Backend: "csqtt-a"}
	if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore {
		t.Fatalf("flow diverted after policy change: outcome=%v err=%v", outcome, err)
	}
	if main.evaluations != 2 || main.continued != 2 || len(main.packets) != 2 || len(backend.packets) != 0 {
		t.Fatalf("flow split: eval=%d continued=%d main=%d raw=%d", main.evaluations, main.continued, len(main.packets), len(backend.packets))
	}
}

func TestMcrfRawPacketBoundaryPinsFlowAndRejectsWrongReverseBackend(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	reply := mcrfRawTestIPv4([4]byte{198, 51, 100, 8}, [4]byte{10, 0, 0, 2}, 17, mcrfRawTestUDP(443, 41000))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	a := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	b := &mcrfRawTestBackend{id: "00000000000000000000000000000002", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": a, "csqtt-b": b}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{Known: mcrfRawMetaUID, UID: 10123}); err != nil {
		t.Fatal(err)
	}
	main.decision.Backend = "csqtt-b" // Existing flow must ignore later policy/cache changes.
	if _, err = boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{Known: mcrfRawMetaUID, UID: 10123}); err != nil {
		t.Fatal(err)
	}
	if len(a.packets) != 2 || len(b.packets) != 0 {
		t.Fatalf("flow moved: backendA=%d backendB=%d", len(a.packets), len(b.packets))
	}
	if err = boundary.DeliverOriginalReply(context.Background(), "csqtt-b", 1, reply); !errors.Is(err, errMcrfRawReturnBackend) {
		t.Fatalf("wrong backend accepted: %v", err)
	}
	if err = boundary.DeliverOriginalReply(context.Background(), "csqtt-a", 2, reply); !errors.Is(err, errMcrfRawStaleGeneration) {
		t.Fatalf("stale generation accepted: %v", err)
	}
	if err = boundary.DeliverOriginalReply(context.Background(), "csqtt-a", 1, reply); err != nil || len(main.replies) != 1 || main.uid[0] != 10123 || string(main.replies[0]) != string(reply) {
		t.Fatalf("reply err=%v count=%d uid=%v", err, len(main.replies), main.uid)
	}
}

func TestMcrfRawPacketBoundaryKeepsDifferentBSFlowsOnTheirSelectedEndpoints(t *testing.T) {
	aPacket := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	bPacket := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{203, 0, 113, 9}, 17, mcrfRawTestUDP(41001, 80))
	main := &mcrfRawTestMaincore{byDestination: map[uint16]mcrfRawDecision{443: {Final: true, Backend: "csqtt-a"}, 80: {Final: true, Backend: "csqtt-b"}}}
	a := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	b := &mcrfRawTestBackend{id: "00000000000000000000000000000002", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": a, "csqtt-b": b}, 7, 8)
	if err != nil {
		t.Fatal(err)
	}
	for _, packet := range [][]byte{aPacket, bPacket} {
		if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawBackendAccepted {
			t.Fatalf("outcome=%v err=%v", outcome, err)
		}
	}
	if len(a.packets) != 1 || len(b.packets) != 1 || string(a.packets[0]) != string(aPacket) || string(b.packets[0]) != string(bPacket) {
		t.Fatalf("per-server affinity failed: A=%d B=%d", len(a.packets), len(b.packets))
	}
}

func TestMcrfRawPacketBoundaryUnknownTargetsAndUnsupportedPacketsStayMaincore(t *testing.T) {
	base := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	fragmented := append([]byte(nil), base...)
	fragmented[6] = 0x20
	for _, tc := range []struct {
		name     string
		packet   []byte
		decision mcrfRawDecision
	}{
		{"unsupported-fragment", fragmented, mcrfRawDecision{Final: true, Backend: "csqtt-a"}},
		{"nonterminal", base, mcrfRawDecision{Backend: "csqtt-a"}},
		{"maincore-target", base, mcrfRawDecision{Final: true}},
		{"unadmitted-backend", base, mcrfRawDecision{Final: true, Backend: "other"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			main := &mcrfRawTestMaincore{decision: tc.decision}
			backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
			boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
			if err != nil {
				t.Fatal(err)
			}
			outcome, err := boundary.DispatchOriginal(context.Background(), tc.packet, mcrfRawPacketMetadata{})
			if err != nil || outcome != mcrfRawStayedInMaincore || len(main.packets) != 1 || len(backend.packets) != 0 {
				t.Fatalf("outcome=%v err=%v main=%d backend=%d", outcome, err, len(main.packets), len(backend.packets))
			}
		})
	}
}

func TestMcrfRawPacketBoundaryCapacityAndRetirementDoNotEvictLiveFlows(t *testing.T) {
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	p1 := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	p2 := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41001, 443))
	if _, err = boundary.DispatchOriginal(context.Background(), p1, mcrfRawPacketMetadata{}); err != nil {
		t.Fatal(err)
	}
	if outcome, err := boundary.DispatchOriginal(context.Background(), p2, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore || len(main.packets) != 1 {
		t.Fatalf("capacity outcome=%v err=%v main=%d", outcome, err, len(main.packets))
	}
	if !boundary.RetireFlow(p1, 0) {
		t.Fatal("closed flow not retired")
	}
	if outcome, err := boundary.DispatchOriginal(context.Background(), p2, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore {
		t.Fatalf("capacity-overflow flow changed path: outcome=%v err=%v", outcome, err)
	}
	if len(main.packets) != 2 || len(backend.packets) != 1 {
		t.Fatalf("capacity-overflow flow split: main=%d raw=%d", len(main.packets), len(backend.packets))
	}
}

func TestMcrfRawPacketFlowParserPreservesIPv6AndRejectsMalformedLengths(t *testing.T) {
	udp := mcrfRawTestUDP(53000, 443)
	packet := make([]byte, 40+len(udp))
	packet[0] = 0x60
	packet[4] = 0
	packet[5] = byte(len(udp))
	packet[6] = 17
	source := netip.MustParseAddr("2001:db8::1").As16()
	destination := netip.MustParseAddr("2001:db8::2").As16()
	copy(packet[8:24], source[:])
	copy(packet[24:40], destination[:])
	copy(packet[40:], udp)
	flow, ok := parseMcrfRawPacketFlow(packet)
	if !ok || flow.Source.String() != "2001:db8::1" || flow.Destination.String() != "2001:db8::2" || flow.SourcePort != 53000 || flow.DestinationPort != 443 {
		t.Fatalf("bad IPv6 flow: %+v %v", flow, ok)
	}
	packet[5]--
	if _, ok = parseMcrfRawPacketFlow(packet); ok {
		t.Fatal("malformed IPv6 length accepted")
	}
}

func TestMcrfRawPacketBoundaryPinsMaincoreWhenEvaluationFailsOrIsNil(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	for _, tc := range []struct {
		name string
		main *mcrfRawTestMaincore
	}{
		{"error", &mcrfRawTestMaincore{evalErr: errors.New("evaluation failed"), decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}},
		{"nil", &mcrfRawTestMaincore{returnNil: true, decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
			boundary, err := newMcrfRawPacketBoundary(tc.main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
			if err != nil {
				t.Fatal(err)
			}
			if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore {
				t.Fatalf("first packet outcome=%v err=%v", outcome, err)
			}
			tc.main.decision = mcrfRawDecision{Final: true, Backend: "csqtt-a"}
			tc.main.evalErr = nil
			tc.main.returnNil = false
			if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{}); err != nil || outcome != mcrfRawStayedInMaincore {
				t.Fatalf("pinned packet outcome=%v err=%v", outcome, err)
			}
			if len(tc.main.packets) != 2 || len(backend.packets) != 0 {
				t.Fatalf("flow moved after evaluation failure: main=%d raw=%d", len(tc.main.packets), len(backend.packets))
			}
		})
	}
}

func TestMcrfRawPacketBoundaryPinsInvalidMetadataFlowToMaincore(t *testing.T) {
	packet := mcrfRawTestIPv4([4]byte{10, 0, 0, 2}, [4]byte{198, 51, 100, 8}, 17, mcrfRawTestUDP(41000, 443))
	main := &mcrfRawTestMaincore{decision: mcrfRawDecision{Final: true, Backend: "csqtt-a"}}
	backend := &mcrfRawTestBackend{id: "00000000000000000000000000000001", accept: true, state: mcrfRawSendAccepted}
	boundary, err := newMcrfRawPacketBoundary(main, map[string]mcrfRawPacketBackend{"csqtt-a": backend}, 1, 8)
	if err != nil {
		t.Fatal(err)
	}
	invalid := mcrfRawPacketMetadata{Known: mcrfRawMetaUID, UID: -2}
	if outcome, err := boundary.DispatchOriginal(context.Background(), packet, invalid); err != nil || outcome != mcrfRawStayedInMaincore {
		t.Fatalf("invalid metadata outcome=%v err=%v", outcome, err)
	}
	if outcome, err := boundary.DispatchOriginal(context.Background(), packet, mcrfRawPacketMetadata{Known: mcrfRawMetaUID, UID: 10123}); err != nil || outcome != mcrfRawStayedInMaincore {
		t.Fatalf("flow diverted after metadata became valid: outcome=%v err=%v", outcome, err)
	}
	if len(main.packets) != 2 || len(backend.packets) != 0 {
		t.Fatalf("invalid-metadata flow changed path: main=%d raw=%d", len(main.packets), len(backend.packets))
	}
}
