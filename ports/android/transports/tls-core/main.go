// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"errors"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	bridge "mcrf.local/optionalbridge"
	"net"
	"os"
	"strconv"
	"strings"
)

const descriptor = `{"api":1,"kind":"tls-core","upstream":"ebe0747b41823e02335ad3c7ae531bc577e7bc75","capabilities":["tls-clienthello-record-fragment-size-sleep","tls-mixedcase-sni","utls","certificate-store","server-address-dns-v1"],"ingress":"authenticated-loopback-socks5-tcp","commands":["STOP","NETWORK_CHANGED"],"readiness":"listener-only"}`

var fragmentPatch string
var socksIngressPatch string

const requiredPatch = "5db03afcd6f3f0838fd6be5dbf0ea942e1a03be082fe34d49c8d3841d2162a4c"

func rangeCheck(s string, min, max uint64) bool {
	if s == "" || len(strings.Split(s, "-")) > 2 {
		return false
	}
	r, e := option.Parse2IntRange(s)
	return e == nil && r.Min >= min && r.Max <= max
}

func create(ctx context.Context, c bridge.Config) (bridge.Core, error) {
	store, err := bridge.CAStore(c.CertificateStore)
	if err != nil {
		return nil, err
	}
	out, err := bridge.Object(c.Outbound)
	if err != nil {
		return nil, err
	}
	var kind string
	json.Unmarshal(out["type"], &kind)
	if kind != "vless" && kind != "vmess" && kind != "trojan" && kind != "http" && kind != "socks" {
		return nil, errors.New("TCP TLS outbound required")
	}
	if err = bridge.RejectKeys(out, "detour", "bind_interface", "inet4_bind_address", "inet6_bind_address", "protect_path", "routing_mark", "netns", "network_strategy", "domain_resolver"); err != nil {
		return nil, err
	}
	tls, e := bridge.Object(out["tls"])
	if e != nil {
		return nil, errors.New("TLS required")
	}
	var enabled bool
	json.Unmarshal(tls["enabled"], &enabled)
	if !enabled {
		return nil, errors.New("TLS required")
	}
	if err = bridge.RejectKeys(tls, "certificate_path", "client_certificate_path", "client_key_path", "tls_tricks_padding"); err != nil {
		return nil, err
	}
	if raw, ok := out["tls_fragment"]; ok {
		var f struct {
			Enabled bool   `json:"enabled"`
			Size    string `json:"size"`
			Sleep   string `json:"sleep"`
			Method  string `json:"method"`
			Range   string `json:"range"`
		}
		d := json.NewDecoder(strings.NewReader(string(raw)))
		d.DisallowUnknownFields()
		if d.Decode(&f) != nil {
			return nil, errors.New("invalid fragment")
		}
		// Candidate MUST use the pinned fragment-write overlay. It replaces unsafe
		// fixed upstream buffers and bounds allocations and stop latency.
		if !f.Enabled || !rangeCheck(f.Size, 1, 16384) || !rangeCheck(f.Sleep, 0, 100) || f.Method != "" && f.Method != "tlsHello" || f.Range != "" {
			return nil, errors.New("unsupported fragment range")
		}
		var tfo bool
		json.Unmarshal(out["tcp_fast_open"], &tfo)
		if tfo {
			return nil, errors.New("fragment and TFO conflict")
		}
	}
	if raw, ok := tls["tls_tricks"]; ok {
		var tricks struct {
			Mixed bool `json:"mixedcase_sni"`
		}
		d := json.NewDecoder(strings.NewReader(string(raw)))
		d.DisallowUnknownFields()
		if d.Decode(&tricks) != nil {
			return nil, errors.New("only mixedcase SNI supported")
		}
	}
	out["tag"] = json.RawMessage(`"proxy"`)
	_, port, _ := net.SplitHostPort(c.SOCKS)
	p, _ := strconv.Atoi(port)
	conf := map[string]any{"log": map[string]any{"disabled": true}, "inbounds": []any{map[string]any{"type": "socks", "listen": "127.0.0.1", "listen_port": p, "users": []any{map[string]string{"username": c.User, "password": c.Password}}}}, "outbounds": []any{out}, "route": map[string]any{"final": "proxy"}}
	if c.ServerDNS != nil {
		if err = c.ServerDNS.Validate(); err != nil {
			return nil, err
		}
		endpointBytes, _ := json.Marshal(c.ServerDNS.Endpoint)
		var endpoint map[string]any
		if err = json.Unmarshal(endpointBytes, &endpoint); err != nil {
			return nil, err
		}
		endpoint["tag"] = "server-bootstrap"
		resolver := map[string]any{"server": "server-bootstrap"}
		if c.ServerDNS.Strategy != "" {
			resolver["strategy"] = c.ServerDNS.Strategy
		}
		resolverBytes, _ := json.Marshal(resolver)
		out["domain_resolver"] = resolverBytes
		conf["dns"] = map[string]any{"servers": []any{endpoint}, "final": "server-bootstrap"}
	}
	conf["certificate"] = map[string]any{"store": store}
	data, _ := json.Marshal(conf)
	ctx = serverDNSContext(ctx, c.ServerDNS)
	opts, err := sjson.UnmarshalExtendedContext[option.Options](ctx, data)
	if err != nil {
		return nil, err
	}
	network := bridge.PhysicalNetwork(c.PhysicalNetworkHandle)
	if network != 0 {
		if err := network.BindProcess(); err != nil {
			return nil, err
		}
		ctx = service.ContextWith(ctx, &dialer.McrfPhysicalControl{Bind: network.BindSocket})
	}
	return box.New(box.Options{Context: ctx, Options: opts})
}

func main() {
	if fragmentPatch != requiredPatch {
		os.Exit(2)
	}
	capabilities := strings.Replace(descriptor, `"capabilities":[`, `"capabilities":["`+bridge.PhysicalNetworkCapability+`",`, 1)
	if socksIngressPatch == "tcp-only-v1" {
		capabilities = strings.Replace(capabilities, `"capabilities":[`, `"capabilities":["socks5-tcp-only-v1",`, 1)
	}
	bridge.Main(capabilities, create)
}
