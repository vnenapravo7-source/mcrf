package main

import (
	"context"
	"encoding/json"
	mdns "github.com/miekg/dns"
	bridge "mcrf.local/optionalbridge"
	"net"
	"strings"
	"testing"
	"time"
)

func TestSelectedServerDNSWithFragmentedTLS(t *testing.T) {
	addr, cert, _, _ := fixture(t)
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	queries := make(chan string, 8)
	srv := &mdns.Server{PacketConn: socket, NotifyStartedFunc: func() { close(started) }, Handler: mdns.HandlerFunc(func(w mdns.ResponseWriter, q *mdns.Msg) {
		queries <- q.Question[0].Name
		a := new(mdns.Msg).SetReply(q)
		if q.Question[0].Qtype == mdns.TypeA {
			a.Answer = []mdns.RR{&mdns.A{Hdr: mdns.RR_Header{Name: q.Question[0].Name, Rrtype: mdns.TypeA, Class: 1, Ttl: 10}, A: net.ParseIP("127.0.0.1")}}
		}
		w.WriteMsg(a)
	})}
	go srv.ActivateAndServe()
	defer srv.Shutdown()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("DNS fixture did not start")
	}
	c := cfg(t, addr, cert, true, false, false)
	var out map[string]any
	if err = json.Unmarshal(c.Outbound, &out); err != nil {
		t.Fatal(err)
	}
	out["server"] = "owned-server.fixture.invalid"
	c.Outbound, _ = json.Marshal(out)
	c.ServerDNS = &bridge.ServerDNS{Endpoint: bridge.ServerDNSEndpoint{Type: "udp", Server: "127.0.0.1", Port: uint16(socket.LocalAddr().(*net.UDPAddr).Port)}, Strategy: "ipv4_only"}
	core, err := create(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	defer core.Close()
	if err = core.Start(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(socksRequest(t, c), "200") {
		t.Fatal("selected DNS TLS traffic failed")
	}
	select {
	case name := <-queries:
		if name != "owned-server.fixture.invalid." {
			t.Fatal("wrong DNS query")
		}
	case <-time.After(time.Second):
		t.Fatal("explicit DNS was ignored")
	}
}

func TestRealSystemServerDNSWithFragmentedTLS(t *testing.T) {
	addr, cert, _, _ := fixture(t)
	c := cfg(t, addr, cert, true, false, false)
	var out map[string]any
	if err := json.Unmarshal(c.Outbound, &out); err != nil {
		t.Fatal(err)
	}
	out["server"] = "localhost"
	c.Outbound, _ = json.Marshal(out)
	c.ServerDNS = &bridge.ServerDNS{Endpoint: bridge.ServerDNSEndpoint{Type: "local"}, Strategy: "ipv4_only"}
	core, err := create(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	defer core.Close()
	if err = core.Start(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(socksRequest(t, c), "200") {
		t.Fatal("system-resolved fragmented TLS traffic failed")
	}
}
