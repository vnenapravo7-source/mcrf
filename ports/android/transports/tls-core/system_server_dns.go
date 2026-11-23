// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"net"
	"strings"
	"time"

	mdns "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	bdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	bridge "mcrf.local/optionalbridge"
)

// The embedded CLI's ordinary Linux local DNS reads /etc/resolv.conf, which
// is not Android's system resolver. Use the Go/Android system API ONLY when
// the user selected system server-address DNS. Explicit DNS never comes here.
type systemServerDNS struct {
	bdns.TransportAdapter
	ctx     context.Context
	cancel  context.CancelFunc
	lookup  func(context.Context, string, string) ([]net.IP, error)
	pending chan struct{}
}

func newSystemServerDNS(ctx context.Context, tag string) *systemServerDNS {
	ctx, cancel := context.WithCancel(ctx)
	return &systemServerDNS{TransportAdapter: bdns.NewTransportAdapter(C.DNSTypeLocal, tag, nil), ctx: ctx, cancel: cancel, lookup: net.DefaultResolver.LookupIP, pending: make(chan struct{}, 16)}
}
func (s *systemServerDNS) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage == adapter.StartStateStart {
		scope.Add(s.Close)
	}
	return nil
}
func (s *systemServerDNS) Close() error { s.cancel(); return nil }
func (*systemServerDNS) Reset()         {}
func (s *systemServerDNS) Exchange(ctx context.Context, q *mdns.Msg) (*mdns.Msg, error) {
	if s.ctx.Err() != nil {
		return nil, s.ctx.Err()
	}
	if len(q.Question) != 1 || q.Question[0].Qclass != mdns.ClassINET {
		return nil, errors.New("one system server DNS question required")
	}
	kind := q.Question[0].Qtype
	if kind != mdns.TypeA && kind != mdns.TypeAAAA {
		return nil, errors.New("system server DNS address questions only")
	}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	family := "ip4"
	if kind == mdns.TypeAAAA {
		family = "ip6"
	}
	ips, err := s.lookup(ctx, family, strings.TrimSuffix(q.Question[0].Name, "."))
	answer := new(mdns.Msg).SetReply(q)
	answer.RecursionAvailable = true
	if err != nil {
		// OS getaddrinfo does not distinguish family NODATA from NXDOMAIN.
		// Never turn absence of A into a domain-wide negative blocking AAAA.
		var dnsError *net.DNSError
		if errors.As(err, &dnsError) && dnsError.IsNotFound {
			return answer, nil
		}
		return nil, err
	}
	if s.ctx.Err() != nil {
		return nil, s.ctx.Err()
	}
	for _, ip := range ips {
		hdr := mdns.RR_Header{Name: q.Question[0].Name, Rrtype: kind, Class: mdns.ClassINET, Ttl: 0}
		// getaddrinfo owns caching; no fabricated positive TTL in this layer.
		if kind == mdns.TypeA && ip.To4() != nil {
			answer.Answer = append(answer.Answer, &mdns.A{Hdr: hdr, A: ip.To4()})
		}
		if kind == mdns.TypeAAAA && ip.To4() == nil && ip.To16() != nil {
			answer.Answer = append(answer.Answer, &mdns.AAAA{Hdr: hdr, AAAA: ip.To16()})
		}
	}
	return answer, nil
}
func (s *systemServerDNS) ExchangeAsync(ctx context.Context, q *mdns.Msg, callback func(*mdns.Msg, error)) {
	if s.ctx.Err() != nil {
		callback(nil, s.ctx.Err())
		return
	}
	select {
	case s.pending <- struct{}{}:
	default:
		callback(nil, errors.New("system server DNS busy"))
		return
	}
	go func() { defer func() { <-s.pending }(); answer, err := s.Exchange(ctx, q); callback(answer, err) }()
}
func serverDNSContext(ctx context.Context, spec *bridge.ServerDNS) context.Context {
	if spec == nil || spec.Endpoint.Type != "local" {
		return include.Context(ctx)
	}
	registry := include.DNSTransportRegistry()
	bdns.RegisterTransport[option.LocalDNSServerOptions](registry, C.DNSTypeLocal, func(ctx context.Context, _ log.ContextLogger, tag string, _ option.LocalDNSServerOptions) (adapter.DNSTransport, error) {
		return newSystemServerDNS(ctx, tag), nil
	})
	return box.Context(ctx, include.InboundRegistry(), include.OutboundRegistry(), include.EndpointRegistry(), registry, include.ServiceRegistry(), include.CertificateProviderRegistry())
}
