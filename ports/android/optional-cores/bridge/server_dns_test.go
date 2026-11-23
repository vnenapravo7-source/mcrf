package bridge

import "testing"

func TestServerDNSBoundary(t *testing.T) {
	for _, kind := range []string{"local", "udp", "tcp", "tls", "https"} {
		e := ServerDNSEndpoint{Type: kind}
		if kind != "local" {
			e.Server = "2001:db8::53"
		}
		for _, strategy := range []string{"", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"} {
			if err := (&ServerDNS{Endpoint: e, Strategy: strategy}).Validate(); err != nil {
				t.Fatal(kind, strategy, err)
			}
		}
	}
	disabled := false
	for _, e := range []ServerDNSEndpoint{
		{Type: "https", Server: "fixture.invalid"}, {Type: "quic", Server: "1.1.1.1"},
		{Type: "local", Server: "1.1.1.1"}, {Type: "udp", Server: "1.1.1.1", Path: "/dns-query"},
		{Type: "tls", Server: "1.1.1.1", TLS: &ServerDNSTLS{Enabled: &disabled}},
		{Type: "https", Server: "1.1.1.1", Path: "/dns?secret=1"},
	} {
		if (&ServerDNS{Endpoint: e}).Validate() == nil {
			t.Fatal("accepted unsupported server DNS context")
		}
	}
}
