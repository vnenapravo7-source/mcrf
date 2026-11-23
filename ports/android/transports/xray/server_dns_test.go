package main

import (
	"context"
	"crypto/x509"
	mdns "github.com/miekg/dns"
	dnsfeature "github.com/xtls/xray-core/features/dns"
	"io"
	bridge "mcrf.local/optionalbridge"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func dnsAnswer(q *mdns.Msg) *mdns.Msg {
	a := new(mdns.Msg).SetReply(q)
	a.RecursionAvailable = true
	if q.Question[0].Qtype == mdns.TypeA {
		a.Answer = []mdns.RR{&mdns.A{Hdr: mdns.RR_Header{Name: q.Question[0].Name, Rrtype: mdns.TypeA, Class: 1, Ttl: 30}, A: net.ParseIP("198.51.100.44")}}
	}
	if q.Question[0].Qtype == mdns.TypeAAAA {
		a.Answer = []mdns.RR{&mdns.AAAA{Hdr: mdns.RR_Header{Name: q.Question[0].Name, Rrtype: mdns.TypeAAAA, Class: 1, Ttl: 20}, AAAA: net.ParseIP("2001:db8::44")}}
	}
	return a
}
func TestServerDNSUDPAndTCPBothFamilies(t *testing.T) {
	for _, network := range []string{"udp", "tcp"} {
		t.Run(network, func(t *testing.T) {
			handler := mdns.HandlerFunc(func(w mdns.ResponseWriter, q *mdns.Msg) { w.WriteMsg(dnsAnswer(q)) })
			started := make(chan struct{})
			srv := &mdns.Server{Handler: handler, NotifyStartedFunc: func() { close(started) }}
			var address net.Addr
			if network == "udp" {
				socket, err := net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				srv.PacketConn = socket
				address = socket.LocalAddr()
			}
			if network == "tcp" {
				socket, err := net.Listen("tcp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				srv.Listener = socket
				address = socket.Addr()
			}
			go srv.ActivateAndServe()
			defer srv.Shutdown()
			select {
			case <-started:
			case <-time.After(time.Second):
				t.Fatal("DNS fixture did not start")
			}
			_, port, _ := net.SplitHostPort(address.String())
			p, _ := strconv.Atoi(port)
			r, err := newServerResolver(context.Background(), &bridge.ServerDNS{Endpoint: bridge.ServerDNSEndpoint{Type: network, Server: "127.0.0.1", Port: uint16(p)}})
			if err != nil {
				t.Fatal(err)
			}
			defer r.Close()
			ips, ttl, err := r.LookupIP("owned.fixture.invalid", dnsfeature.IPOption{IPv4Enable: true, IPv6Enable: true})
			if err != nil || len(ips) != 2 || ttl != 20 || !ips[0].Equal(net.ParseIP("198.51.100.44")) || !ips[1].Equal(net.ParseIP("2001:db8::44")) {
				t.Fatal("selected DNS/family/TTL lost", err)
			}
			r.Close()
			if _, _, err = r.LookupIP("owned.fixture.invalid", dnsfeature.IPOption{IPv4Enable: true}); err == nil {
				t.Fatal("DNS survived STOP")
			}
		})
	}
}
func TestServerDNSDoHTrustNoSystemFallback(t *testing.T) {
	peer := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 4096))
		q := new(mdns.Msg)
		if q.Unpack(raw) != nil {
			w.WriteHeader(400)
			return
		}
		answer, _ := dnsAnswer(q).Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		w.Write(answer)
	}))
	defer peer.Close()
	port := peer.Listener.Addr().(*net.TCPAddr).Port
	r, err := newServerResolver(context.Background(), &bridge.ServerDNS{Endpoint: bridge.ServerDNSEndpoint{Type: "https", Server: "127.0.0.1", Port: uint16(port)}})
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	opt := dnsfeature.IPOption{IPv4Enable: true}
	if _, _, err = r.LookupIP("localhost", opt); err == nil {
		t.Fatal("untrusted DNS silently fell back to system")
	}
	roots := x509.NewCertPool()
	roots.AddCert(peer.Certificate())
	r.transport.TLSClientConfig.RootCAs = roots
	ips, _, err := r.LookupIP("owned.fixture.invalid", opt)
	if err != nil || len(ips) != 1 || !ips[0].Equal(net.ParseIP("198.51.100.44")) {
		t.Fatal("selected trusted DoH failed", err)
	}
}
