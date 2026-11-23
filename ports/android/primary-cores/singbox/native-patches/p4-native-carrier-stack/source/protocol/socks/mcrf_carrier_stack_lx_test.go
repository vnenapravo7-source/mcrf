//go:build with_lx_carrier_stack && with_lx_raw_packet && with_gvisor && (linux || android)

package socks

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/gvisor/pkg/buffer"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/link/channel"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/sing-box/adapter"
	O "github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type carrierStackFixtureEndpoint struct {
	ctx           context.Context
	cancel        context.CancelFunc
	link          *channel.Endpoint
	sent          atomic.Uint32
	fragments     atomic.Uint32
	bufferMu      sync.Mutex
	firstBuffer   *byte
	firstCapacity int
	changedBuffer bool
	blockFirst    bool
	pendingRetry  []byte
	blocked       int
	retried       int
}

func (*carrierStackFixtureEndpoint) Identity() string   { return "native-carrier-fixture" }
func (*carrierStackFixtureEndpoint) Generation() uint64 { return 1 }
func (*carrierStackFixtureEndpoint) LocalAddress() netip.Addr {
	return netip.MustParseAddr("198.18.0.1")
}
func (*carrierStackFixtureEndpoint) MTU() int                   { return 1300 }
func (*carrierStackFixtureEndpoint) OriginalCarrierStack() bool { return true }
func (f *carrierStackFixtureEndpoint) Close() error             { f.cancel(); return nil }
func (f *carrierStackFixtureEndpoint) SendOriginalPacket(ctx context.Context, data []byte) (adapter.RawPacketSendState, error) {
	if err := ctx.Err(); err != nil {
		return adapter.RawPacketRejected, err
	}
	if err := f.ctx.Err(); err != nil {
		return adapter.RawPacketRejected, err
	}
	if len(data) < 20 || len(data) > 1300 {
		return adapter.RawPacketRejected, errors.New("owned IP bounds")
	}
	f.bufferMu.Lock()
	if f.firstBuffer == nil {
		f.firstBuffer = &data[0]
		f.firstCapacity = cap(data)
	} else if f.firstBuffer != &data[0] {
		f.changedBuffer = true
	}
	if f.blockFirst && f.blocked == 0 {
		f.blocked++
		f.pendingRetry = append([]byte(nil), data...)
		f.bufferMu.Unlock()
		return adapter.RawPacketWouldBlock, nil
	}
	if f.pendingRetry != nil {
		if !bytes.Equal(f.pendingRetry, data) {
			f.bufferMu.Unlock()
			return adapter.RawPacketRejected, errors.New("pending packet overwritten")
		}
		f.pendingRetry = nil
		f.retried++
	}
	f.bufferMu.Unlock()
	f.sent.Add(1)
	if data[6]&0x3f != 0 || data[7] != 0 {
		f.fragments.Add(1)
	}
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), data...))})
	f.link.InjectInbound(ipv4.ProtocolNumber, packet)
	packet.DecRef()
	return adapter.RawPacketAccepted, nil
}
func (f *carrierStackFixtureEndpoint) WaitOriginalPacketWritable(ctx context.Context) error {
	return ctx.Err()
}

func (f *carrierStackFixtureEndpoint) ReadOriginalPacket(ctx context.Context) ([]byte, error) {
	read, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(f.ctx, cancel)
	defer stop()
	packet := f.link.ReadContext(read)
	if packet == nil {
		return nil, read.Err()
	}
	defer packet.DecRef()
	var data []byte
	for _, part := range packet.AsSlices() {
		data = append(data, part...)
	}
	return data, nil
}

type carrierStackFixtureDNS struct {
	adapter.DNSRouter
	calls atomic.Uint32
}

func (f *carrierStackFixtureDNS) Lookup(ctx context.Context, domain string, _ adapter.DNSQueryOptions) ([]netip.Addr, error) {
	f.calls.Add(1)
	metadata := adapter.ContextFrom(ctx)
	if metadata == nil || metadata.Outbound != "native-carrier" {
		return nil, errors.New("owned DNS metadata missing")
	}
	if domain != "owned.carrier.test" {
		return nil, errors.New("OWNED_DNS_UNAVAILABLE")
	}
	return []netip.Addr{netip.MustParseAddr("198.18.0.2")}, nil
}

func carrierStackFixture(t *testing.T) (*originalCarrierStackOutbound, *carrierStackFixtureEndpoint, *carrierStackFixtureDNS, *stack.Stack, <-chan struct{}) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	link := channel.New(256, 1300, "")
	remote, err := tun.NewGVisorStackWithOptions(link, stack.NICOptions{}, true)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	if e := remote.AddProtocolAddress(tun.DefaultNIC, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: tcpip.AddressWithPrefix{Address: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), PrefixLen: 32}}, stack.AddressProperties{}); e != nil {
		t.Fatal(e)
	}
	endpointContext, endpointCancel := context.WithCancel(ctx)
	endpoint := &carrierStackFixtureEndpoint{ctx: endpointContext, cancel: endpointCancel, link: link}
	registry := adapter.NewOriginalPacketEndpointRegistry()
	failed := make(chan struct{}, 1)
	registry.SetOriginalCarrierFailureHandler(func() { failed <- struct{}{} })
	if err = registry.Register("native-carrier", endpoint); err != nil {
		t.Fatal(err)
	}
	ctx = service.ContextWith[adapter.OriginalPacketEndpointProvider](ctx, registry)
	dns := new(carrierStackFixtureDNS)
	base := &Outbound{Adapter: O.NewAdapter("socks", "native-carrier", []string{"tcp", "udp"}, nil), dnsRouter: dns, logger: log.NewNOPFactory().Logger()}
	wrapped, err, handled := wrapMcrfCarrierStackOutbound(ctx, base)
	if err != nil || !handled {
		t.Fatal("native carrier not adopted", err)
	}
	if _, fast := wrapped.(adapter.FlowOutbound); fast {
		t.Fatal("native ordinary path bypassed normal sniff/routing")
	}
	outbound := wrapped.(*originalCarrierStackOutbound)
	t.Cleanup(func() {
		cancel()
		_ = outbound.Close()
		registry.Close()
		remote.Close()
		for _, endpoint := range remote.CleanupEndpoints() {
			endpoint.Abort()
		}
		remote.Wait()
		link.Close()
	})
	return outbound, endpoint, dns, remote, failed
}

func TestOriginalCarrierWrapperNativeTCPUDPFragmentsAndProfileDNS(t *testing.T) {
	outbound, endpoint, dns, remote, failed := carrierStackFixture(t)
	endpoint.bufferMu.Lock()
	endpoint.blockFirst = true
	endpoint.bufferMu.Unlock()
	listener, err := gonet.ListenTCP(remote, tcpip.FullAddress{NIC: tun.DefaultNIC, Addr: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), Port: 18080}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	echo := make(chan error, 1)
	go func() {
		connection, e := listener.Accept()
		if e != nil {
			echo <- e
			return
		}
		defer connection.Close()
		_ = connection.SetDeadline(time.Now().Add(2 * time.Second))
		data := make([]byte, 64)
		_, e = io.ReadFull(connection, data)
		if e == nil {
			_, e = connection.Write(data)
		}
		echo <- e
	}()
	ctx := context.Background()
	connection, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr("owned.carrier.test:18080"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(2 * time.Second))
	request := bytes.Repeat([]byte{0x67}, 64)
	if _, err = connection.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 64)
	if _, err = io.ReadFull(connection, reply); err != nil || !bytes.Equal(reply, request) {
		t.Fatal("native TCP mismatch", err)
	}
	if err = <-echo; err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	peer, err := gonet.DialUDP(remote, &tcpip.FullAddress{NIC: tun.DefaultNIC, Addr: tun.AddressFromAddr(netip.MustParseAddr("198.18.0.2")), Port: 18081}, nil, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	_ = peer.SetDeadline(time.Now().Add(2 * time.Second))
	destination := M.ParseSocksaddr("owned.carrier.test:18081")
	client, err := outbound.ListenPacket(ctx, destination)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(2 * time.Second))
	large := bytes.Repeat([]byte{0x27, 0x81, 0x19, 0xab}, 1024)
	if _, err = client.WriteTo(large, destination); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 8192)
	n, address, err := peer.ReadFrom(data)
	if err != nil || !bytes.Equal(data[:n], large) {
		t.Fatal("native domain UDP fragmented request", err)
	}
	if _, err = peer.WriteTo(data[:n], address); err != nil {
		t.Fatal(err)
	}
	// The core consumes PacketReader's Socksaddr, which preserves a domain;
	// net.UDPAddr from the standard ReadFrom API cannot represent an FQDN.
	native, ok := client.(N.NetPacketConn)
	if !ok {
		t.Fatal("native packet interface missing")
	}
	packet := buf.NewPacket()
	defer packet.Release()
	from, err := native.ReadPacket(packet)
	if err != nil || !bytes.Equal(packet.Bytes(), large) || from.String() != destination.String() {
		t.Fatal("native domain UDP reply/NAT", err, from)
	}
	if dns.calls.Load() != 2 || endpoint.sent.Load() < 5 || endpoint.fragments.Load() < 4 {
		t.Fatal("native endpoint/profile DNS/fragment path bypassed")
	}
	_ = client.Close()
	_ = outbound.Close()
	endpoint.bufferMu.Lock()
	defer endpoint.bufferMu.Unlock()
	if endpoint.changedBuffer || endpoint.firstCapacity != 1301 || endpoint.blocked != 1 || endpoint.retried != 1 {
		t.Fatalf("packet storage reuse/backpressure: changed=%v capacity=%d blocked=%d retried=%d", endpoint.changedBuffer, endpoint.firstCapacity, endpoint.blocked, endpoint.retried)
	}
	select {
	case <-failed:
		t.Fatal("normal close reported failure")
	case <-time.After(30 * time.Millisecond):
	}
}

func TestOriginalCarrierWrapperDNSFailureCannotFallbackToSOCKS(t *testing.T) {
	outbound, endpoint, _, _, _ := carrierStackFixture(t)
	if _, err := outbound.DialContext(context.Background(), "tcp", M.ParseSocksaddr("unknown.carrier.test:18080")); err == nil || !strings.Contains(err.Error(), "OWNED_DNS_UNAVAILABLE") {
		t.Fatal("DNS failure hidden", err)
	}
	if _, err := outbound.ListenPacket(context.Background(), M.ParseSocksaddr("unknown.carrier.test:18081")); err == nil || !strings.Contains(err.Error(), "OWNED_DNS_UNAVAILABLE") {
		t.Fatal("UDP DNS failure hidden", err)
	}
	if endpoint.sent.Load() != 0 {
		t.Fatal("DNS failure sent carrier packets")
	}
}

func TestOriginalCarrierWrapperPeerFailureRetiresGeneration(t *testing.T) {
	outbound, endpoint, _, _, failed := carrierStackFixture(t)
	_ = endpoint.Close()
	select {
	case <-failed:
	case <-time.After(time.Second):
		t.Fatal("unexpected carrier EOF not reported")
	}
	if _, err := outbound.DialContext(context.Background(), "tcp", M.ParseSocksaddr("198.18.0.2:18080")); err == nil {
		t.Fatal("retired carrier dialled/fell back")
	}
	_ = outbound.Close()
}
