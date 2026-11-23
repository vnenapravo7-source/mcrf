package main

import (
	"context"
	"errors"
	mdns "github.com/miekg/dns"
	"net"
	"testing"
)

func TestSystemServerDNSFamiliesAndNoFabricatedCache(t *testing.T) {
	s := newSystemServerDNS(context.Background(), "server-bootstrap")
	defer s.Close()
	s.lookup = func(_ context.Context, family, host string) ([]net.IP, error) {
		if host != "owned.fixture.invalid" {
			t.Fatal("wrong system name")
		}
		if family == "ip4" {
			return []net.IP{net.ParseIP("198.51.100.44")}, nil
		}
		if family == "ip6" {
			return []net.IP{net.ParseIP("2001:db8::44")}, nil
		}
		t.Fatal("family policy lost")
		return nil, nil
	}
	for _, kind := range []uint16{mdns.TypeA, mdns.TypeAAAA} {
		q := new(mdns.Msg).SetQuestion("owned.fixture.invalid.", kind)
		a, err := s.Exchange(context.Background(), q)
		if err != nil || len(a.Answer) != 1 || a.Answer[0].Header().Rrtype != kind || a.Answer[0].Header().Ttl != 0 {
			t.Fatal("system DNS family/TTL", err)
		}
	}
}
func TestSystemServerDNSFailureCloseAndFamilyNodata(t *testing.T) {
	s := newSystemServerDNS(context.Background(), "server-bootstrap")
	q := new(mdns.Msg).SetQuestion("owned.fixture.invalid.", mdns.TypeA)
	s.lookup = func(context.Context, string, string) ([]net.IP, error) { return nil, errors.New("owned failure") }
	if _, err := s.Exchange(context.Background(), q); err == nil {
		t.Fatal("system failure hidden")
	}
	s.lookup = func(context.Context, string, string) ([]net.IP, error) { return nil, &net.DNSError{IsNotFound: true} }
	a, err := s.Exchange(context.Background(), q)
	if err != nil || a.Rcode != mdns.RcodeSuccess || len(a.Answer) != 0 {
		t.Fatal("family NODATA became NXDOMAIN")
	}
	s.Close()
	if _, err = s.Exchange(context.Background(), q); err == nil {
		t.Fatal("closed DNS accepted lookup")
	}
}
