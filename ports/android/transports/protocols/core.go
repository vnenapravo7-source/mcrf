// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	bridge "mcrf.local/optionalbridge"
)

const descriptor = `{"api":1,"kind":"protocols","upstream":"3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d","capabilities":["tuic","anytls","naive-cronet","ssh-host-key","certificate-store","server-address-dns-v1","udp-authenticated-source-port-v1","android-child-physical-network-v1"],"ingress":"authenticated-loopback-socks5-tcp-udp","commands":["STOP","NETWORK_CHANGED"],"readiness":"listener-only"}`

// No SDK TUN, ingress, rule, API or website-DNS configuration crosses this
// boundary. Steer remains the primary TUN owner; only one original outbound
// runs here. Naive is registered by the actual with_naive_outbound/Cronet SDK.
type physicalNetworkKey struct{}

func createCore(ctx context.Context, c Config) (*box.Box, adapter.Outbound, error) {
	ctx = context.WithValue(ctx, physicalNetworkKey{}, c.PhysicalNetworkHandle)
	if c.PhysicalNetworkHandle > 0 {
		ctx = service.ContextWith(ctx, &dialer.McrfPhysicalControl{Bind: func(fd int) error { return bindSocket(c.PhysicalNetworkHandle, fd) }})
	}
	out, e := bridge.Object(c.Outbound)
	if e != nil {
		return nil, nil, errConfig
	}
	out["tag"] = json.RawMessage(`"proxy"`)
	store, e := bridge.CAStore(c.CertificateStore)
	if e != nil {
		return nil, nil, errConfig
	}
	conf := map[string]any{"log": map[string]any{"disabled": true}, "outbounds": []any{out}, "route": map[string]any{"final": "proxy"}, "certificate": map[string]any{"store": store}}
	if c.ServerDNS != nil {
		data, _ := json.Marshal(c.ServerDNS.Endpoint)
		var endpoint map[string]any
		json.Unmarshal(data, &endpoint)
		endpoint["tag"] = "server-bootstrap"
		resolver := map[string]any{"server": "server-bootstrap"}
		if c.ServerDNS.Strategy != "" {
			resolver["strategy"] = c.ServerDNS.Strategy
		}
		data, _ = json.Marshal(resolver)
		out["domain_resolver"] = data
		conf["dns"] = map[string]any{"servers": []any{endpoint}, "final": "server-bootstrap"}
	}
	ctx = serverDNSContext(ctx, c.ServerDNS)
	data, _ := json.Marshal(conf)
	options, e := sjson.UnmarshalExtendedContext[option.Options](ctx, data)
	if e != nil {
		return nil, nil, e
	}
	b, e := box.New(box.Options{Context: ctx, Options: options})
	if e != nil {
		return nil, nil, e
	}
	o, ok := b.Outbound().Outbound("proxy")
	if !ok {
		b.Close()
		return nil, nil, errConfig
	}
	return b, o, nil
}
