//go:build with_mcrf_direct_family

package route

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"syscall"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
)

type mcrfFamilyTestDNS struct{ adapter.DNSRouter }

func (*mcrfFamilyTestDNS) Lookup(_ context.Context, domain string, options adapter.DNSQueryOptions) ([]netip.Addr, error) {
	if domain != "example.ru" || options.Strategy != C.DomainStrategyIPv4Only {
		return nil, errors.New("unexpected lookup")
	}
	return []netip.Addr{netip.MustParseAddr("198.18.0.1")}, nil
}

type mcrfFamilyTestTransports struct{ adapter.DNSTransportManager }

func (*mcrfFamilyTestTransports) FakeIP() adapter.FakeIPTransport { return nil }

type mcrfFamilyTestOutbound struct {
	adapter.Outbound
	dialer *mcrfFamilyTestDialer
	kind   string
}

func (o *mcrfFamilyTestOutbound) Type() string { return o.kind }
func (*mcrfFamilyTestOutbound) Tag() string    { return "direct" }
func (o *mcrfFamilyTestOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return o.dialer.DialContext(ctx, network, destination)
}
func (o *mcrfFamilyTestOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return o.dialer.ListenPacket(ctx, destination)
}

func TestMcrfDirectFamilyActualPolicyRecheck(t *testing.T) {
	for _, tc := range []struct {
		name, prior string
		denied      bool
	}{
		{"same-app-direct", "", false},
		{"earlier-ipv4-vpn", `{"ip_cidr":["198.18.0.0/15"],"action":"route","outbound":"vpn"},`, true},
		{"earlier-ipv4-block", `{"ip_cidr":["198.18.0.0/15"],"action":"reject"},`, true},
		{"different-direct-rule", `{"ip_cidr":["198.18.0.0/15"],"action":"route","outbound":"direct"},`, true},
		{"changed-tls-options", `{"ip_cidr":["198.18.0.0/15"],"action":"route-options","tls_fragment":true},`, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx := context.Background()
			var options []option.Rule
			if err := json.UnmarshalContext(ctx, []byte(`[`+tc.prior+`{"package_name":["org.example.browser"],"action":"route","outbound":"direct"}]`), &options); err != nil {
				t.Fatal(err)
			}
			router := &Router{ctx: ctx, logger: log.NewNOPFactory().NewLogger("test"), dns: &mcrfFamilyTestDNS{}, dnsTransport: &mcrfFamilyTestTransports{}}
			if err := router.Initialize(options, nil); err != nil {
				t.Fatal(err)
			}
			owner := &adapter.ConnectionOwner{AndroidPackageNames: []string{"org.example.browser"}}
			metadata := adapter.InboundContext{InboundType: C.TypeTun, Network: "tcp", Protocol: C.ProtocolTLS, Domain: "example.ru", ProcessInfo: owner, Destination: M.Socksaddr{Addr: netip.MustParseAddr("fdfe:dcba:9876::c612:1"), Port: 443}}
			left, right := net.Pipe()
			defer left.Close()
			defer right.Close()
			dialer := &mcrfFamilyTestDialer{dial: func(_ context.Context, _ string, dest M.Socksaddr) (net.Conn, error) {
				if dest.IsIPv6() {
					return nil, syscall.ENETUNREACH
				}
				return left, nil
			}}
			outbound := &mcrfFamilyTestOutbound{dialer: dialer, kind: C.TypeDirect}
			originalRule := router.rules[len(router.rules)-1]
			retry := router.mcrfDirectFamilyDialer(outbound, originalRule, metadata)
			conn, err := retry.DialContext(ctx, "tcp", metadata.Destination)
			if tc.denied {
				if conn != nil || !errors.Is(err, syscall.ENETUNREACH) || len(dialer.destinations) != 1 {
					t.Fatal("candidate escaped its original policy")
				}
			} else if conn != left || err != nil || len(dialer.destinations) != 2 {
				t.Fatalf("same-rule retry failed: %v", err)
			}
			if metadata.Destination.IsIPv4() || metadata.ProcessInfo != owner {
				t.Fatal("original flow metadata mutated")
			}
		})
	}
}

func TestMcrfDirectFamilyNeverWrapsVpnUnknownHostOrOtherIngress(t *testing.T) {
	for _, tc := range []struct{ name, kind, inbound, protocol, domain string }{
		{"vpn", "vless", C.TypeTun, C.ProtocolTLS, "example.ru"},
		{"unknown-host", C.TypeDirect, C.TypeTun, C.ProtocolTLS, ""},
		{"no-sniff", C.TypeDirect, C.TypeTun, "", "example.ru"},
		{"other-ingress", C.TypeDirect, "mixed", C.ProtocolTLS, "example.ru"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			outbound := &mcrfFamilyTestOutbound{kind: tc.kind}
			metadata := adapter.InboundContext{InboundType: tc.inbound, Network: "tcp", Protocol: tc.protocol, Domain: tc.domain, Destination: M.Socksaddr{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443}}
			var router *Router
			if router.mcrfDirectFamilyDialer(outbound, nil, metadata) != outbound {
				t.Fatal("ineligible flow wrapped")
			}
		})
	}
}
