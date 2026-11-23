//go:build with_lx_raw_packet && (linux || android)

package adapter

import (
	"context"
	"errors"
	"net/netip"
	"strconv"
	"sync"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// OriginalPacketEndpoint is implemented by the platform's owned CSQTT packet
// endpoint. SendOriginalPacket must be bounded and atomic for one IP packet.
// ReadOriginalPacket returns one complete IP packet, never an envelope.
type OriginalPacketEndpoint interface {
	Identity() string
	Generation() uint64
	LocalAddress() netip.Addr
	MTU() int
	SendOriginalPacket(context.Context, []byte) (RawPacketSendState, error)
	ReadOriginalPacket(context.Context) ([]byte, error)
}

type OriginalPacketWritable interface {
	WaitOriginalPacketWritable(context.Context) error
}

type RawPacketSendState uint8

const (
	RawPacketAccepted RawPacketSendState = iota + 1
	RawPacketWouldBlock
	RawPacketRejected
)

// OriginalPacketEndpointProvider resolves only endpoints owned by the active
// Android session. Lookup must not create or restart a session.
type OriginalPacketEndpointProvider interface {
	HasOriginalPacketEndpoints() bool
	OriginalPacketEndpoint(outboundTag string) (OriginalPacketEndpoint, bool)
}

// RawPacketIngress is an opt-in pre-stack hook. It calls the existing ordered
// Router.PreMatch evaluator; all actions other than an unchanged Flow to an
// admitted endpoint remain on the ordinary sing-tun path.
type RawPacketIngress struct {
	router       Router
	provider     OriginalPacketEndpointProvider
	inbound      string
	inboundType  string
	ctx          context.Context
	cancel       context.CancelFunc
	write        func([]byte) error
	mu           sync.Mutex
	closed       bool
	flows        map[rawPacketTuple]rawPacketBinding
	maincore     map[rawPacketTuple]struct{}
	maincoreFull bool
	reverse      map[rawPacketTuple]rawPacketBinding
	readers      map[string]struct{}
	endpointTags map[string]string
	readerWG     sync.WaitGroup
}

type rawPacketTuple struct {
	source, destination         netip.Addr
	sourcePort, destinationPort uint16
	protocol                    uint8
}

type rawPacketBinding struct {
	endpoint   OriginalPacketEndpoint
	identity   string
	generation uint64
}

func NewRawPacketIngress(ctx context.Context, router Router, provider OriginalPacketEndpointProvider, inbound, inboundType string, write func([]byte) error) (*RawPacketIngress, error) {
	if ctx == nil || router == nil || provider == nil || !provider.HasOriginalPacketEndpoints() || inbound == "" || write == nil {
		return nil, errors.New("MCRF_RAW_INGRESS_INPUT")
	}
	ownedContext, cancel := context.WithCancel(ctx)
	return &RawPacketIngress{ctx: ownedContext, cancel: cancel, router: router, provider: provider, inbound: inbound, inboundType: inboundType, write: write,
		flows: make(map[rawPacketTuple]rawPacketBinding), maincore: make(map[rawPacketTuple]struct{}),
		reverse: make(map[rawPacketTuple]rawPacketBinding), readers: make(map[string]struct{}), endpointTags: make(map[string]string)}, nil
}

// DispatchOriginal reports consumed=true only after the packet was accepted by
// its pinned raw endpoint. Errors never authorize re-injection into maincore.
func (i *RawPacketIngress) DispatchOriginal(packet []byte) (consumed bool, err error) {
	flow, payload, network, ok := parseOriginalPacket(packet)
	if !ok {
		return false, nil
	}
	i.mu.Lock()
	if i.closed {
		i.mu.Unlock()
		return false, errors.New("MCRF_RAW_INGRESS_CLOSED")
	}
	binding, pinned := i.flows[flow]
	_, maincorePinned := i.maincore[flow]
	maincoreFull := i.maincoreFull
	i.mu.Unlock()
	if maincorePinned || !pinned && maincoreFull {
		return false, nil
	}
	if !pinned {
		metadata := InboundContext{Inbound: i.inbound, InboundType: i.inboundType, Network: network,
			Source:      M.SocksaddrFromNetIP(netip.AddrPortFrom(flow.source, flow.sourcePort)),
			Destination: M.SocksaddrFromNetIP(netip.AddrPortFrom(flow.destination, flow.destinationPort))}
		result := i.router.PreMatch(metadata, payload)
		if result.Action != PreMatchFlow || result.Outbound == nil || result.Destination.IsValid() && result.Destination != netip.AddrPortFrom(flow.destination, flow.destinationPort) {
			i.pinMaincore(flow)
			return false, nil
		}
		if _, ok := result.Outbound.(FlowOutbound); !ok {
			i.pinMaincore(flow)
			return false, nil
		}
		endpoint, found := i.provider.OriginalPacketEndpoint(result.Outbound.Tag())
		if !found || !validOriginalPacketEndpoint(endpoint, flow, packet) {
			i.pinMaincore(flow)
			return false, nil
		}
		binding = rawPacketBinding{endpoint: endpoint, identity: endpoint.Identity(), generation: endpoint.Generation()}
		i.mu.Lock()
		if i.closed {
			i.mu.Unlock()
			return false, errors.New("MCRF_RAW_INGRESS_CLOSED")
		}
		if existing, exists := i.flows[flow]; exists {
			binding = existing
		} else {
			_, collision := i.reverse[flow.reverse()]
			otherTag, endpointShared := i.endpointTags[binding.identity]
			if len(i.flows) >= 65536 || len(i.reverse) >= 65536 || collision || binding.identity == "" || len(binding.identity) > 128 || binding.generation == 0 || endpointShared && otherTag != result.Outbound.Tag() {
				i.mu.Unlock()
				i.pinMaincore(flow)
				return false, nil
			}
			i.flows[flow] = binding
			i.reverse[flow.reverse()] = binding
			i.endpointTags[binding.identity] = result.Outbound.Tag()
		}
		i.mu.Unlock()
		i.startReplyReader(binding)
	}
	if !validOriginalPacketEndpoint(binding.endpoint, flow, packet) || binding.endpoint.Identity() != binding.identity || binding.endpoint.Generation() != binding.generation {
		return false, errors.New("MCRF_RAW_PINNED_ENDPOINT_CHANGED")
	}
	for {
		if err := i.ctx.Err(); err != nil {
			return false, err
		}
		state, sendErr := binding.endpoint.SendOriginalPacket(i.ctx, packet)
		switch state {
		case RawPacketAccepted:
			if sendErr != nil {
				return false, sendErr
			}
			return true, nil
		case RawPacketWouldBlock:
			if sendErr != nil {
				return false, sendErr
			}
			// Keep this exact packet and pinned owner. Backpressure must not
			// reclassify it or make a transient full socket fatal to the TUN.
			if writable, ok := binding.endpoint.(OriginalPacketWritable); ok {
				if err := writable.WaitOriginalPacketWritable(i.ctx); err != nil {
					return false, err
				}
			} else {
				timer := time.NewTimer(10 * time.Millisecond)
				select {
				case <-i.ctx.Done():
					timer.Stop()
					return false, i.ctx.Err()
				case <-timer.C:
				}
			}
		case RawPacketRejected:
			if sendErr == nil {
				sendErr = errors.New("MCRF_RAW_REJECTED")
			}
			return false, sendErr
		default:
			return false, errors.New("MCRF_RAW_SEND_STATE")
		}
	}
}

func (i *RawPacketIngress) pinMaincore(flow rawPacketTuple) {
	i.mu.Lock()
	defer i.mu.Unlock()
	if i.closed || i.maincoreFull {
		return
	}
	if len(i.maincore) >= 65536 {
		i.maincoreFull = true
		return
	}
	i.maincore[flow] = struct{}{}
}

func (t rawPacketTuple) reverse() rawPacketTuple {
	return rawPacketTuple{source: t.destination, destination: t.source, sourcePort: t.destinationPort, destinationPort: t.sourcePort, protocol: t.protocol}
}

func validOriginalPacketEndpoint(endpoint OriginalPacketEndpoint, flow rawPacketTuple, packet []byte) bool {
	if endpoint == nil || endpoint.Identity() == "" || endpoint.Generation() == 0 || endpoint.MTU() < 20 || len(packet) > endpoint.MTU() || !endpoint.LocalAddress().IsValid() {
		return false
	}
	return flow.source == endpoint.LocalAddress()
}

func (i *RawPacketIngress) startReplyReader(binding rawPacketBinding) {
	readerKey := binding.identity + ":" + strconv.FormatUint(binding.generation, 10)
	i.mu.Lock()
	if _, exists := i.readers[readerKey]; i.closed || exists {
		i.mu.Unlock()
		return
	}
	i.readers[readerKey] = struct{}{}
	i.readerWG.Add(1)
	i.mu.Unlock()
	go func() {
		defer i.readerWG.Done()
		for {
			packet, err := binding.endpoint.ReadOriginalPacket(i.ctx)
			if err != nil {
				return
			}
			flow, _, _, ok := parseOriginalPacket(packet)
			if !ok {
				continue
			}
			if len(packet) > binding.endpoint.MTU() || flow.destination != binding.endpoint.LocalAddress() {
				continue
			}
			i.mu.Lock()
			bound, found := i.reverse[flow]
			valid := !i.closed && found && bound.identity == binding.identity && bound.generation == binding.generation &&
				binding.endpoint.Identity() == binding.identity && binding.endpoint.Generation() == binding.generation
			i.mu.Unlock()
			if valid {
				if err = i.write(packet); err != nil {
					return
				}
			}
		}
	}()
}

func (i *RawPacketIngress) Close() {
	i.mu.Lock()
	i.closed = true
	clear(i.flows)
	clear(i.maincore)
	clear(i.reverse)
	clear(i.endpointTags)
	i.mu.Unlock()
	i.cancel()
	i.readerWG.Wait()
}

func parseOriginalPacket(packet []byte) (rawPacketTuple, []byte, string, bool) {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return rawPacketTuple{}, nil, "", false
	}
	headerLength := int(packet[0]&15) * 4
	totalLength := int(packet[2])<<8 | int(packet[3])
	fragment := int(packet[6])<<8 | int(packet[7])
	if headerLength < 20 || headerLength > len(packet) || totalLength != len(packet) || fragment&0xbfff != 0 {
		return rawPacketTuple{}, nil, "", false
	}
	flow := rawPacketTuple{source: netip.AddrFrom4([4]byte{packet[12], packet[13], packet[14], packet[15]}),
		destination: netip.AddrFrom4([4]byte{packet[16], packet[17], packet[18], packet[19]}), protocol: packet[9]}
	transport := packet[headerLength:]
	var network string
	var payload []byte
	switch flow.protocol {
	case 6:
		if len(transport) < 20 || int(transport[12]>>4)*4 < 20 || int(transport[12]>>4)*4 > len(transport) {
			return rawPacketTuple{}, nil, "", false
		}
		flow.sourcePort = uint16(transport[0])<<8 | uint16(transport[1])
		flow.destinationPort = uint16(transport[2])<<8 | uint16(transport[3])
		network = N.NetworkTCP
		payload = transport[int(transport[12]>>4)*4:]
	case 17:
		if len(transport) < 8 || int(transport[4])<<8|int(transport[5]) != len(transport) {
			return rawPacketTuple{}, nil, "", false
		}
		flow.sourcePort = uint16(transport[0])<<8 | uint16(transport[1])
		flow.destinationPort = uint16(transport[2])<<8 | uint16(transport[3])
		network = N.NetworkUDP
		payload = transport[8:]
	default:
		return rawPacketTuple{}, nil, "", false
	}
	return flow, payload, network, true
}
