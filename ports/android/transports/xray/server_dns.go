// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"strconv"
	"time"

	mdns "github.com/miekg/dns"
	dnsfeature "github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/transport/internet"
	bridge "mcrf.local/optionalbridge"
)

// Used ONLY by the pinned fork's per-instance outbound-server DNS hook.
// Website destinations stay with the parent. No package-global dialer changes,
// no fallback to system DNS after an explicit resolver fails, no first-IP pin.
type serverResolver struct {
	spec      *bridge.ServerDNS
	ctx       context.Context
	cancel    context.CancelFunc
	http      *http.Client
	transport *http.Transport
	network   bridge.PhysicalNetwork
}

func newServerResolver(ctx context.Context, spec *bridge.ServerDNS) (*serverResolver, error) {
	return newServerResolverOnNetwork(ctx, spec, 0)
}

func newServerResolverOnNetwork(ctx context.Context, spec *bridge.ServerDNS, network bridge.PhysicalNetwork) (*serverResolver, error) {
	if err := spec.Validate(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	r := &serverResolver{spec: spec, ctx: ctx, cancel: cancel, network: network}
	if spec.Endpoint.Type == "https" {
		endpoint := spec.Endpoint
		address := net.JoinHostPort(endpoint.Server, strconv.Itoa(int(endpoint.ServerPort())))
		r.transport = &http.Transport{Proxy: nil, ForceAttemptHTTP2: true,
			TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: endpoint.TLSName()},
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return network.Dialer().DialContext(ctx, "tcp", address)
			}}
		r.http = &http.Client{Transport: r.transport, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("DNS redirect refused") }}
	}
	return r, nil
}
func (*serverResolver) Type() interface{} { return dnsfeature.ClientType() }
func (*serverResolver) Start() error      { return nil }
func (r *serverResolver) Close() error {
	r.cancel()
	if r.transport != nil {
		r.transport.CloseIdleConnections()
	}
	return nil
}
func (r *serverResolver) strategy() internet.DomainStrategy {
	switch r.spec.Strategy {
	case "prefer_ipv4":
		return internet.DomainStrategy_FORCE_IP46
	case "prefer_ipv6":
		return internet.DomainStrategy_FORCE_IP64
	case "ipv4_only":
		return internet.DomainStrategy_FORCE_IP4
	case "ipv6_only":
		return internet.DomainStrategy_FORCE_IP6
	default:
		return internet.DomainStrategy_FORCE_IP
	}
}
func (r *serverResolver) LookupIP(host string, option dnsfeature.IPOption) ([]net.IP, uint32, error) {
	if !option.IPv4Enable && !option.IPv6Enable {
		return nil, 0, dnsfeature.ErrEmptyResponse
	}
	ctx, cancel := context.WithTimeout(r.ctx, 8*time.Second)
	defer cancel()
	if r.spec.Endpoint.Type == "local" {
		family := "ip"
		if !option.IPv4Enable {
			family = "ip6"
		} else if !option.IPv6Enable {
			family = "ip4"
		}
		ips, err := net.DefaultResolver.LookupIP(ctx, family, host)
		return ips, 0, err
	}
	kinds := []uint16{}
	if option.IPv4Enable {
		kinds = append(kinds, mdns.TypeA)
	}
	if option.IPv6Enable {
		kinds = append(kinds, mdns.TypeAAAA)
	}
	ips := []net.IP{}
	ttl := uint32(0)
	ttlSet := false
	var last error
	for _, kind := range kinds {
		q := new(mdns.Msg).SetQuestion(mdns.Fqdn(host), kind)
		answer, err := r.exchange(ctx, q)
		if err != nil {
			last = err
			continue
		}
		if answer.Id != q.Id || !answer.Response || answer.Truncated || answer.Rcode != mdns.RcodeSuccess || len(answer.Question) != 1 || answer.Question[0] != q.Question[0] {
			last = errors.New("invalid server DNS response")
			continue
		}
		for _, rr := range answer.Answer {
			var ip net.IP
			switch a := rr.(type) {
			case *mdns.A:
				if kind == mdns.TypeA {
					ip = a.A
				}
			case *mdns.AAAA:
				if kind == mdns.TypeAAAA {
					ip = a.AAAA
				}
			}
			if ip != nil {
				ips = append(ips, ip)
				if !ttlSet || rr.Header().Ttl < ttl {
					ttl = rr.Header().Ttl
					ttlSet = true
				}
			}
		}
	}
	if len(ips) > 0 {
		return ips, ttl, nil
	}
	if last != nil {
		return nil, 0, last
	}
	return nil, 0, dnsfeature.ErrEmptyResponse
}
func (r *serverResolver) exchange(ctx context.Context, q *mdns.Msg) (*mdns.Msg, error) {
	e := r.spec.Endpoint
	if e.Type == "https" {
		wire, err := q.Pack()
		if err != nil {
			return nil, err
		}
		path := e.Path
		if path == "" {
			path = "/dns-query"
		}
		url := "https://" + net.JoinHostPort(e.TLSName(), strconv.Itoa(int(e.ServerPort()))) + path
		request, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(wire))
		if err != nil {
			return nil, err
		}
		request.Header.Set("Content-Type", "application/dns-message")
		request.Header.Set("Accept", "application/dns-message")
		response, err := r.http.Do(request)
		if err != nil {
			return nil, err
		}
		defer response.Body.Close()
		if response.StatusCode != 200 {
			return nil, errors.New("server DNS HTTPS status")
		}
		contentType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
		if err != nil || contentType != "application/dns-message" {
			return nil, errors.New("server DNS MIME type")
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, 65536))
		if err != nil || len(body) > 65535 {
			return nil, errors.New("server DNS body bound")
		}
		answer := new(mdns.Msg)
		if err = answer.Unpack(body); err != nil {
			return nil, err
		}
		return answer, nil
	}
	network := e.Type
	if network == "tls" {
		network = "tcp-tls"
	}
	client := &mdns.Client{Net: network, Timeout: 8 * time.Second, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: e.TLSName()}}
	if r.network != 0 {
		client.Dialer = r.network.Dialer()
		client.Dialer.Timeout = client.Timeout
	}
	address := net.JoinHostPort(e.Server, strconv.Itoa(int(e.ServerPort())))
	answer, _, err := client.ExchangeContext(ctx, q, address)
	if err == nil && answer.Truncated && e.Type == "udp" {
		client.Net = "tcp"
		answer, _, err = client.ExchangeContext(ctx, q, address)
	}
	return answer, err
}

type dnsOwnedCore struct {
	bridge.Core
	resolver *serverResolver
}

func (c *dnsOwnedCore) Close() error { c.resolver.Close(); return c.Core.Close() }
