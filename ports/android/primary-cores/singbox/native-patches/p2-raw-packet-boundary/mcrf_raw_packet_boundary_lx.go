//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"sync"
)

// STAGED FOUNDATION ONLY: no upstream TUN ingress registration or build
// inclusion is present in this directory. The intended caller is the
// maincore's raw-packet ingress before its TCP/UDP stack. Maincore policy must
// evaluate the original packet; only a final selected backend may receive it.
// Every other outcome resumes the original maincore evaluation/path.
//
// This file deliberately does not implement route matching, DNS, sniffing,
// FakeIP, NAT, a TUN, or a stream proxy. Metadata is supplied by the maincore;
// missing required metadata makes a decision ineligible for diversion.
type mcrfRawMetadataMask uint8

const (
	mcrfRawMetaUID mcrfRawMetadataMask = 1 << iota
	mcrfRawMetaDomain
	mcrfRawMetaDNS
	mcrfRawMetaFakeIP
	mcrfRawMetaSniff
)

type mcrfRawPacketMetadata struct {
	Known  mcrfRawMetadataMask
	UID    int
	Domain string
	DNS    bool
	FakeIP bool
	Sniff  bool
}

func (m mcrfRawPacketMetadata) complete(mask mcrfRawMetadataMask) bool {
	if m.Known&mask != mask {
		return false
	}
	if mask&mcrfRawMetaUID != 0 && m.UID < 0 {
		return false
	}
	if mask&mcrfRawMetaDomain != 0 && m.Domain == "" {
		return false
	}
	return true
}

func (m mcrfRawPacketMetadata) valid() bool {
	const known = mcrfRawMetaUID | mcrfRawMetaDomain | mcrfRawMetaDNS | mcrfRawMetaFakeIP | mcrfRawMetaSniff
	return m.Known&^known == 0 && (m.Known&mcrfRawMetaUID == 0 || m.UID >= 0) &&
		(m.Known&mcrfRawMetaDomain == 0 || m.Domain != "")
}

// mcrfRawPacketFlow is the IP-layer five-tuple. Unsupported packet forms,
// including fragments and IPv6 extension-header chains, are intentionally
// returned to maincore instead of being approximated into a different flow.
type mcrfRawPacketFlow struct {
	Source, Destination netip.Addr
	Protocol            uint8
	SourcePort          uint16
	DestinationPort     uint16
	Identifier          uint16 // ICMP echo identifier; zero for TCP/UDP.
}

func (f mcrfRawPacketFlow) reverse() mcrfRawPacketFlow {
	return mcrfRawPacketFlow{
		Source: f.Destination, Destination: f.Source, Protocol: f.Protocol,
		SourcePort: f.DestinationPort, DestinationPort: f.SourcePort,
		Identifier: f.Identifier,
	}
}

type mcrfRawDecision struct {
	Final         bool
	Backend       string
	NeedsMetadata mcrfRawMetadataMask
}

// The implementation must use the existing ordered route/rule evaluator.
// ContinueOriginal resumes that same evaluation in the established maincore
// stack (including later sniff/DNS work), with original bytes intact.
type mcrfRawPolicyEvaluation interface {
	Decision() mcrfRawDecision
	ContinueOriginal(context.Context, []byte) error
}

type mcrfRawMaincore interface {
	EvaluateOriginal(context.Context, []byte, mcrfRawPacketFlow, mcrfRawPacketMetadata) (mcrfRawPolicyEvaluation, error)
	// ProcessOriginal is used only when the packet cannot be safely inspected
	// enough to begin a raw policy evaluation.
	ProcessOriginal(context.Context, []byte, mcrfRawPacketMetadata) error
	// uid is -1 when the original flow owner was not available at admission.
	InjectOriginalReply(context.Context, []byte, int) error
}

// One instance represents one independently owned per-backend packet FD.
// Implementations must preserve one complete IP packet per SendRawPacket call.
type mcrfRawPacketBackend interface {
	RawEndpointID() string
	// RawEndpointIdentity is derived by the native adapter from the actually
	// verified connected FD (stable across dup aliases), not from RawEndpointID.
	RawEndpointIdentity() string
	// AcceptsOriginalPacket is a cheap immutable-config check: source address,
	// address family, and complete-packet MTU must match this backend's own
	// run_tun configuration. It performs no IO and never rewrites the packet.
	AcceptsOriginalPacket(mcrfRawPacketFlow, []byte) bool
	// SendRawPacket is bounded/nonblocking and writes or copies one complete
	// original IP packet before returning. WOULD_BLOCK and REJECTED never
	// authorize route migration/fallback.
	SendRawPacket(context.Context, uint64, []byte) (mcrfRawSendState, error)
}

type mcrfRawSendState uint8

const (
	mcrfRawSendAccepted mcrfRawSendState = iota + 1
	mcrfRawSendWouldBlock
	mcrfRawSendRejected
)

type mcrfRawRouteOutcome uint8

const (
	mcrfRawStayedInMaincore mcrfRawRouteOutcome = iota
	mcrfRawBackendAccepted
	mcrfRawBackendBackpressure
	mcrfRawBackendRejected
)

var (
	errMcrfRawPacketUnsupported    = errors.New("MCRF_RAW_PACKET_UNSUPPORTED")
	errMcrfRawUIDChanged           = errors.New("MCRF_RAW_FLOW_UID_CHANGED")
	errMcrfRawReturnUnbound        = errors.New("MCRF_RAW_RETURN_UNBOUND")
	errMcrfRawReturnBackend        = errors.New("MCRF_RAW_RETURN_BACKEND")
	errMcrfRawStaleGeneration      = errors.New("MCRF_RAW_STALE_GENERATION")
	errMcrfRawSendState            = errors.New("MCRF_RAW_SEND_STATE")
	errMcrfRawBackpressure         = errors.New("MCRF_RAW_BACKPRESSURE")
	errMcrfRawBackendRejected      = errors.New("MCRF_RAW_BACKEND_REJECTED")
	errMcrfRawPinnedPacketRejected = errors.New("MCRF_RAW_PINNED_PACKET_REJECTED")
)

type mcrfRawFlowBinding struct {
	backend    string
	maincore   bool
	generation uint64
	uid        int
	uidKnown   bool
}

// mcrfRawPacketBoundary is session-scoped and bounded. It never evicts an
// active flow to make room: when full, new traffic stays in maincore. The owner
// calls RetireFlow only after the flow is known closed, or Close at session end.
type mcrfRawPacketBoundary struct {
	mu          sync.Mutex
	drained     *sync.Cond
	active      int
	maincore    mcrfRawMaincore
	backends    map[string]mcrfRawPacketBackend
	forward     map[mcrfRawPacketFlow]mcrfRawFlowBinding
	reverse     map[mcrfRawPacketFlow]mcrfRawFlowBinding
	maxFlows    int
	generation  uint64
	rawDisabled bool
	closed      bool
}

func newMcrfRawPacketBoundary(maincore mcrfRawMaincore, backends map[string]mcrfRawPacketBackend, generation uint64, maxFlows int) (*mcrfRawPacketBoundary, error) {
	if maincore == nil || generation == 0 || generation > (1<<63)-1 || maxFlows < 1 || maxFlows > 1<<20 || len(backends) == 0 {
		return nil, errors.New("MCRF_RAW_BOUNDARY_INPUT")
	}
	owned := make(map[string]mcrfRawPacketBackend, len(backends))
	endpointIDs := make(map[string]struct{}, len(backends))
	endpointIdentities := make(map[string]struct{}, len(backends))
	for tag, backend := range backends {
		if !validMcrfRawBackendTag(tag) || backend == nil {
			return nil, errors.New("MCRF_RAW_BACKEND_INPUT")
		}
		endpointID := backend.RawEndpointID()
		if !validMcrfRawEndpointID(endpointID) {
			return nil, errors.New("MCRF_RAW_ENDPOINT_ID")
		}
		if _, duplicate := endpointIDs[endpointID]; duplicate {
			return nil, errors.New("MCRF_RAW_ENDPOINT_NOT_INDEPENDENT")
		}
		endpointIDs[endpointID] = struct{}{}
		endpointIdentity := backend.RawEndpointIdentity()
		if endpointIdentity == "" || len(endpointIdentity) > 128 {
			return nil, errors.New("MCRF_RAW_ENDPOINT_IDENTITY")
		}
		if _, duplicate := endpointIdentities[endpointIdentity]; duplicate {
			return nil, errors.New("MCRF_RAW_ENDPOINT_FD_REUSED")
		}
		endpointIdentities[endpointIdentity] = struct{}{}
		owned[tag] = backend
	}
	boundary := &mcrfRawPacketBoundary{
		maincore: maincore, backends: owned,
		forward: make(map[mcrfRawPacketFlow]mcrfRawFlowBinding),
		reverse: make(map[mcrfRawPacketFlow]mcrfRawFlowBinding), maxFlows: maxFlows, generation: generation,
	}
	boundary.drained = sync.NewCond(&boundary.mu)
	return boundary, nil
}

func (b *mcrfRawPacketBoundary) beginOperation() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return false
	}
	b.active++
	return true
}

func (b *mcrfRawPacketBoundary) endOperation() {
	b.mu.Lock()
	b.active--
	if b.closed && b.active == 0 {
		b.drained.Broadcast()
	}
	b.mu.Unlock()
}

func (b *mcrfRawPacketBoundary) bindUIDLocked(flow mcrfRawPacketFlow, binding mcrfRawFlowBinding, metadata mcrfRawPacketMetadata) mcrfRawFlowBinding {
	if !binding.uidKnown && metadata.Known&mcrfRawMetaUID != 0 && metadata.UID >= 0 {
		binding.uid = metadata.UID
		binding.uidKnown = true
		b.forward[flow] = binding
		b.reverse[flow.reverse()] = binding
	}
	return binding
}

func validMcrfRawEndpointID(id string) bool {
	if len(id) != 32 {
		return false
	}
	for _, c := range id {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func validMcrfRawBackendTag(tag string) bool {
	if len(tag) == 0 || len(tag) > 96 {
		return false
	}
	for _, c := range tag {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

// DispatchOriginal must be called in the TUN reader's packet order. The
// caller has already obtained metadata using the platform/maincore's existing
// UID, DNS, FakeIP, and sniff facilities. It passes the exact original bytes.
func (b *mcrfRawPacketBoundary) DispatchOriginal(ctx context.Context, packet []byte, metadata mcrfRawPacketMetadata) (mcrfRawRouteOutcome, error) {
	if ctx == nil || len(packet) == 0 {
		return mcrfRawStayedInMaincore, errors.New("MCRF_RAW_PACKET_INPUT")
	}
	if !b.beginOperation() {
		return mcrfRawStayedInMaincore, errors.New("MCRF_RAW_BOUNDARY_CLOSED")
	}
	defer b.endOperation()
	flow, ok := parseMcrfRawPacketFlow(packet)
	if !ok {
		if err := b.maincore.ProcessOriginal(ctx, packet, metadata); err != nil {
			return mcrfRawStayedInMaincore, err
		}
		return mcrfRawStayedInMaincore, nil
	}

	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return mcrfRawStayedInMaincore, errors.New("MCRF_RAW_BOUNDARY_CLOSED")
	}
	binding, pinned := b.forward[flow]
	if pinned && binding.uidKnown && metadata.Known&mcrfRawMetaUID != 0 && metadata.UID >= 0 && binding.uid != metadata.UID {
		b.mu.Unlock()
		return mcrfRawStayedInMaincore, errMcrfRawUIDChanged
	}
	if pinned && metadata.valid() {
		binding = b.bindUIDLocked(flow, binding, metadata)
	}
	rawDisabled := b.rawDisabled
	b.mu.Unlock()

	if pinned && binding.maincore {
		evaluation, err := b.maincore.EvaluateOriginal(ctx, packet, flow, metadata)
		if err != nil || evaluation == nil {
			return mcrfRawStayedInMaincore, b.maincore.ProcessOriginal(ctx, packet, metadata)
		}
		return mcrfRawStayedInMaincore, evaluation.ContinueOriginal(ctx, packet)
	}
	if pinned {
		backend := b.backends[binding.backend]
		if backend == nil {
			return mcrfRawBackendRejected, errors.New("MCRF_RAW_PINNED_BACKEND_MISSING")
		}
		// Revalidate every packet against the pinned backend's immutable source,
		// family and MTU contract. Once pinned, rejection is fail-closed; it must
		// never split this flow back into the ordinary maincore transport.
		if !backend.AcceptsOriginalPacket(flow, packet) {
			return mcrfRawBackendRejected, errMcrfRawPinnedPacketRejected
		}
		return b.sendPinned(ctx, backend, binding, packet)
	}
	if !metadata.valid() {
		b.pinMaincoreFlow(flow, metadata)
		if err := b.maincore.ProcessOriginal(ctx, packet, metadata); err != nil {
			return mcrfRawStayedInMaincore, err
		}
		return mcrfRawStayedInMaincore, nil
	}

	if !pinned {
		evaluation, err := b.maincore.EvaluateOriginal(ctx, packet, flow, metadata)
		if err != nil || evaluation == nil {
			b.pinMaincoreFlow(flow, metadata)
			if processErr := b.maincore.ProcessOriginal(ctx, packet, metadata); processErr != nil {
				if err != nil {
					return mcrfRawStayedInMaincore, fmt.Errorf("MCRF_RAW_POLICY_AND_MAINCORE: %w", processErr)
				}
				return mcrfRawStayedInMaincore, processErr
			}
			return mcrfRawStayedInMaincore, nil
		}
		decision := evaluation.Decision()
		continueMaincore := func() (mcrfRawRouteOutcome, error) {
			b.pinMaincoreFlow(flow, metadata)
			return mcrfRawStayedInMaincore, evaluation.ContinueOriginal(ctx, packet)
		}
		if rawDisabled || !decision.Final || decision.Backend == "" || !metadata.complete(decision.NeedsMetadata) {
			return continueMaincore()
		}

		b.mu.Lock()
		if b.closed {
			b.mu.Unlock()
			return mcrfRawStayedInMaincore, errors.New("MCRF_RAW_BOUNDARY_CLOSED")
		}
		// A backend must be explicitly admitted. Unknown/non-packet outbounds
		// remain on the normal maincore path.
		if b.backends[decision.Backend] == nil {
			b.mu.Unlock()
			return continueMaincore()
		}
		selectedBackend := b.backends[decision.Backend]
		if !selectedBackend.AcceptsOriginalPacket(flow, packet) {
			b.mu.Unlock()
			return continueMaincore()
		}
		if b.rawDisabled {
			b.mu.Unlock()
			return continueMaincore()
		}
		if existing, exists := b.forward[flow]; exists {
			if existing.uidKnown && metadata.Known&mcrfRawMetaUID != 0 && existing.uid != metadata.UID {
				b.mu.Unlock()
				return mcrfRawStayedInMaincore, errMcrfRawUIDChanged
			}
			binding = b.bindUIDLocked(flow, existing, metadata) // Concurrent first packet: preserve the winner.
		} else {
			if len(b.forward) >= b.maxFlows {
				// A flow sent to maincore at capacity must not be eligible for raw
				// diversion after another flow retires. Disable new raw admissions
				// for this session; existing raw pins remain stable.
				b.rawDisabled = true
				b.mu.Unlock()
				return continueMaincore()
			}
			uid := -1
			uidKnown := metadata.Known&mcrfRawMetaUID != 0 && metadata.UID >= 0
			if uidKnown {
				uid = metadata.UID
			}
			if _, exists := b.reverse[flow.reverse()]; exists {
				b.rawDisabled = true
				b.mu.Unlock()
				return continueMaincore()
			}
			binding = mcrfRawFlowBinding{backend: decision.Backend, generation: b.generation, uid: uid, uidKnown: uidKnown}
			b.forward[flow] = binding
			b.reverse[flow.reverse()] = binding
		}
		b.mu.Unlock()
	}

	backend := b.backends[binding.backend]
	if backend == nil {
		return mcrfRawStayedInMaincore, errors.New("MCRF_RAW_PINNED_BACKEND_MISSING")
	}
	return b.sendPinned(ctx, backend, binding, packet)
}

func (b *mcrfRawPacketBoundary) pinMaincoreFlow(flow mcrfRawPacketFlow, metadata mcrfRawPacketMetadata) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed || b.rawDisabled {
		return
	}
	if existing, ok := b.forward[flow]; ok {
		b.bindUIDLocked(flow, existing, metadata)
		return
	}
	if len(b.forward) >= b.maxFlows {
		b.rawDisabled = true
		return
	}
	b.forward[flow] = mcrfRawFlowBinding{
		maincore:   true,
		generation: b.generation,
		uid:        metadata.UID,
		uidKnown:   metadata.Known&mcrfRawMetaUID != 0 && metadata.UID >= 0,
	}
}

func (b *mcrfRawPacketBoundary) sendPinned(ctx context.Context, backend mcrfRawPacketBackend, binding mcrfRawFlowBinding, packet []byte) (mcrfRawRouteOutcome, error) {
	// Never retry this packet through maincore after endpoint submission: the
	// backend may have accepted it before reporting an error.
	state, err := backend.SendRawPacket(ctx, binding.generation, packet)
	switch state {
	case mcrfRawSendAccepted:
		if err != nil {
			return mcrfRawBackendRejected, err
		}
		return mcrfRawBackendAccepted, nil
	case mcrfRawSendWouldBlock:
		if err == nil {
			err = errMcrfRawBackpressure
		}
		return mcrfRawBackendBackpressure, err
	case mcrfRawSendRejected:
		if err == nil {
			err = errMcrfRawBackendRejected
		}
		return mcrfRawBackendRejected, err
	default:
		if err == nil {
			err = errMcrfRawSendState
		}
		return mcrfRawBackendRejected, err
	}
}

// DeliverOriginalReply accepts a packet only from the backend bound to its
// reverse tuple. Packet bytes are injected unchanged; there is no NAT or
// synthetic address rewriting at this boundary.
func (b *mcrfRawPacketBoundary) DeliverOriginalReply(ctx context.Context, backendTag string, generation uint64, packet []byte) error {
	if ctx == nil || len(packet) == 0 || !validMcrfRawBackendTag(backendTag) {
		return errors.New("MCRF_RAW_RETURN_INPUT")
	}
	if !b.beginOperation() {
		return errors.New("MCRF_RAW_BOUNDARY_CLOSED")
	}
	defer b.endOperation()
	flow, ok := parseMcrfRawPacketFlow(packet)
	if !ok {
		return errMcrfRawPacketUnsupported
	}
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return errors.New("MCRF_RAW_BOUNDARY_CLOSED")
	}
	if generation != b.generation {
		b.mu.Unlock()
		return errMcrfRawStaleGeneration
	}
	binding, found := b.reverse[flow]
	b.mu.Unlock()
	if !found {
		return errMcrfRawReturnUnbound
	}
	if binding.backend != backendTag {
		return errMcrfRawReturnBackend
	}
	if binding.generation != generation {
		return errMcrfRawStaleGeneration
	}
	return b.maincore.InjectOriginalReply(ctx, packet, binding.uid)
}

// RetireFlow must be called after authoritative flow-close evidence. The
// packet layer deliberately does not infer closure from an idle timer.
func (b *mcrfRawPacketBoundary) RetireFlow(original []byte, uid int) bool {
	flow, ok := parseMcrfRawPacketFlow(original)
	if !ok {
		return false
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	binding, found := b.forward[flow]
	if !found || binding.uidKnown && binding.uid != uid {
		return false
	}
	delete(b.forward, flow)
	if !binding.maincore {
		delete(b.reverse, flow.reverse())
	}
	return true
}

// Close follows cancellation of the owner's context and waits for any packet
// operation already in progress before releasing flow/reverse bindings.
func (b *mcrfRawPacketBoundary) Close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closed = true
	for b.active > 0 {
		b.drained.Wait()
	}
	clear(b.forward)
	clear(b.reverse)
}

func parseMcrfRawPacketFlow(packet []byte) (mcrfRawPacketFlow, bool) {
	if len(packet) < 1 {
		return mcrfRawPacketFlow{}, false
	}
	switch packet[0] >> 4 {
	case 4:
		if len(packet) < 20 {
			return mcrfRawPacketFlow{}, false
		}
		header := int(packet[0]&15) * 4
		total := int(packet[2])<<8 | int(packet[3])
		fragment := int(packet[6])<<8 | int(packet[7])
		if header < 20 || header > len(packet) || total != len(packet) || fragment&0xbfff != 0 {
			return mcrfRawPacketFlow{}, false
		}
		flow := mcrfRawPacketFlow{Source: netip.AddrFrom4([4]byte{packet[12], packet[13], packet[14], packet[15]}),
			Destination: netip.AddrFrom4([4]byte{packet[16], packet[17], packet[18], packet[19]}), Protocol: packet[9]}
		return parseMcrfRawTransport(packet[header:], flow)
	case 6:
		if len(packet) < 40 || int(packet[4])<<8|int(packet[5]) != len(packet)-40 {
			return mcrfRawPacketFlow{}, false
		}
		flow := mcrfRawPacketFlow{Source: netip.AddrFrom16([16]byte{packet[8], packet[9], packet[10], packet[11], packet[12], packet[13], packet[14], packet[15], packet[16], packet[17], packet[18], packet[19], packet[20], packet[21], packet[22], packet[23]}),
			Destination: netip.AddrFrom16([16]byte{packet[24], packet[25], packet[26], packet[27], packet[28], packet[29], packet[30], packet[31], packet[32], packet[33], packet[34], packet[35], packet[36], packet[37], packet[38], packet[39]}), Protocol: packet[6]}
		// Extension headers need maincore parsing/sniff state. Do not reduce them
		// to a guessed transport flow.
		if flow.Protocol != 6 && flow.Protocol != 17 && flow.Protocol != 58 {
			return mcrfRawPacketFlow{}, false
		}
		return parseMcrfRawTransport(packet[40:], flow)
	default:
		return mcrfRawPacketFlow{}, false
	}
}

func parseMcrfRawTransport(payload []byte, flow mcrfRawPacketFlow) (mcrfRawPacketFlow, bool) {
	switch flow.Protocol {
	case 6: // TCP
		if len(payload) < 20 {
			return mcrfRawPacketFlow{}, false
		}
		header := int(payload[12]>>4) * 4
		if header < 20 || header > len(payload) {
			return mcrfRawPacketFlow{}, false
		}
		flow.SourcePort = uint16(payload[0])<<8 | uint16(payload[1])
		flow.DestinationPort = uint16(payload[2])<<8 | uint16(payload[3])
	case 17: // UDP
		if len(payload) < 8 || int(payload[4])<<8|int(payload[5]) != len(payload) {
			return mcrfRawPacketFlow{}, false
		}
		flow.SourcePort = uint16(payload[0])<<8 | uint16(payload[1])
		flow.DestinationPort = uint16(payload[2])<<8 | uint16(payload[3])
	case 1, 58: // ICMPv4/v6 echo only; other ICMP has no safe flow identifier here.
		if len(payload) < 8 || flow.Protocol == 1 && payload[0] != 0 && payload[0] != 8 ||
			flow.Protocol == 58 && payload[0] != 128 && payload[0] != 129 {
			return mcrfRawPacketFlow{}, false
		}
		flow.Identifier = uint16(payload[4])<<8 | uint16(payload[5])
	default:
		return mcrfRawPacketFlow{}, false
	}
	return flow, true
}
