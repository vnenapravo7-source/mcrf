// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	xlog "github.com/xtls/xray-core/app/log"
	clog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/core"
	conf "github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
	bridge "mcrf.local/optionalbridge"
	"net"
	"strconv"
	"strings"
)

const descriptor = `{"api":1,"kind":"xray","upstream":"e662c22ab1096883c4df200a0e8068a89da04df1","capabilities":["vless","xhttp","reality","mux","socks5-udp-associate","server-address-dns-v1"],"ingress":"authenticated-loopback-socks5-tcp-udp","commands":["STOP","NETWORK_CHANGED"],"readiness":"listener-only"}`

var physicalDialerPatch string
var socksIngressPatch string

func strict(raw json.RawMessage, dest any) error {
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	return d.Decode(dest)
}

func validate(out map[string]json.RawMessage) error {
	var s struct {
		Vnext []struct {
			Address string `json:"address"`
			Port    uint16 `json:"port"`
			Users   []struct {
				ID         string `json:"id"`
				Encryption string `json:"encryption"`
				Flow       string `json:"flow"`
				Level      uint32 `json:"level"`
				Email      string `json:"email"`
			} `json:"users"`
		} `json:"vnext"`
	}
	if strict(out["settings"], &s) != nil || len(s.Vnext) != 1 || s.Vnext[0].Address == "" || s.Vnext[0].Port == 0 || len(s.Vnext[0].Users) != 1 {
		return errors.New("one VLESS server and user required")
	}
	u := s.Vnext[0].Users[0]
	if u.Encryption != "none" || u.Flow != "" && u.Flow != "xtls-rprx-vision" {
		return errors.New("unsupported VLESS user options")
	}
	var stream conf.StreamConfig
	if raw, ok := out["streamSettings"]; ok {
		if strict(raw, &stream) != nil {
			return errors.New("invalid stream fields")
		}
		m, e := bridge.Object(raw)
		if e != nil {
			return e
		}
		allowed := map[string]bool{"network": true, "security": true, "tlsSettings": true, "realitySettings": true, "rawSettings": true, "tcpSettings": true, "xhttpSettings": true, "wsSettings": true, "grpcSettings": true, "httpupgradeSettings": true}
		for k := range m {
			if !allowed[k] {
				return errors.New("unsupported stream field")
			}
		}
		network := "raw"
		if stream.Network != nil {
			network = string(*stream.Network)
		}
		if network != "raw" && network != "tcp" && network != "xhttp" && network != "ws" && network != "grpc" && network != "httpupgrade" {
			return errors.New("unsupported transport")
		}
		if stream.Security != "" && stream.Security != "none" && stream.Security != "tls" && stream.Security != "reality" {
			return errors.New("unsupported security")
		}
		if t := stream.TLSSettings; t != nil {
			if t.MasterKeyLog != "" || len(t.Certs) > 0 || t.ECHSocketSettings != nil || t.ECHServerKeys != "" || strings.Contains(t.ECHConfigList, "+") {
				return errors.New("external file or resolver TLS options unsupported")
			}
		}
		if r := stream.REALITYSettings; r != nil {
			if r.MasterKeyLog != "" || r.Show || r.Target != nil || r.Dest != nil || r.PrivateKey != "" {
				return errors.New("client REALITY only")
			}
		}
		if x := stream.XHTTPSettings; x != nil {
			xm, e := bridge.Object(m["xhttpSettings"])
			if e != nil {
				return e
			}
			if e = bridge.RejectKeys(xm, "downloadSettings"); e != nil {
				return e
			}
			if extra, ok := xm["extra"]; ok {
				ex, e := bridge.Object(extra)
				if e != nil {
					return e
				}
				var config conf.SplitHTTPConfig
				if strict(extra, &config) != nil {
					return errors.New("unknown XHTTP extra")
				}
				if e = bridge.RejectKeys(ex, "downloadSettings", "extra"); e != nil {
					return e
				}
			}
		}
		if u.Flow != "" && (network != "raw" && network != "tcp" || stream.Security != "tls" && stream.Security != "reality") {
			return errors.New("Vision requires raw TLS or REALITY")
		}
	}
	if raw, ok := out["mux"]; ok {
		var mux conf.MuxConfig
		if strict(raw, &mux) != nil {
			return errors.New("invalid mux")
		}
		if mux.Enabled && (mux.Concurrency < 1 || mux.Concurrency > 1024 || u.Flow != "") {
			return errors.New("invalid mux concurrency or Vision conflict")
		}
	}
	return nil
}

func create(ctx context.Context, c bridge.Config) (bridge.Core, error) {
	store, err := bridge.CAStore(c.CertificateStore)
	if err != nil || store != "system" {
		return nil, errors.New("Xray requires system certificate store")
	}
	out, err := bridge.Object(c.Outbound)
	if err != nil {
		return nil, err
	}
	var protocol string
	json.Unmarshal(out["protocol"], &protocol)
	if protocol != "vless" {
		return nil, errors.New("VLESS outbound required")
	}
	if err = bridge.RejectKeys(out, "proxySettings", "sendThrough"); err != nil {
		return nil, err
	}
	// Prevent config additions from escaping this single-hop worker contract.
	allowed := map[string]bool{"protocol": true, "settings": true, "streamSettings": true, "mux": true, "tag": true}
	for k := range out {
		if !allowed[k] {
			return nil, errors.New("unknown outbound field")
		}
	}
	if err = validate(out); err != nil {
		return nil, err
	}
	if stream, ok := out["streamSettings"]; ok {
		m, e := bridge.Object(stream)
		if e != nil {
			return nil, e
		}
		if e = bridge.RejectKeys(m, "sockopt"); e != nil {
			return nil, e
		}
	}
	out["tag"] = json.RawMessage(`"proxy"`)
	_, port, _ := net.SplitHostPort(c.SOCKS)
	p, _ := strconv.Atoi(port)
	conf := map[string]any{"log": map[string]any{"loglevel": "warning", "access": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": p, "protocol": "socks", "settings": map[string]any{"auth": "password", "accounts": []any{map[string]string{"user": c.User, "pass": c.Password}}, "udp": true, "ip": "127.0.0.1"}}}, "outbounds": []any{out}}
	data, _ := json.Marshal(conf)
	config, err := serial.LoadJSONConfig(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	network := bridge.PhysicalNetwork(c.PhysicalNetworkHandle)
	if network != 0 {
		if physicalDialerPatch != "fail-closed-v1" {
			return nil, errors.New("Xray physical network requires fail-closed dialer")
		}
		if err := network.BindProcess(); err != nil {
			return nil, err
		}
		if err := internet.RegisterDialerController(network.Control); err != nil {
			return nil, err
		}
	}
	instance, err := core.New(config)
	if err != nil {
		return nil, err
	}
	if c.ServerDNS == nil {
		return instance, nil
	}
	resolver, err := newServerResolverOnNetwork(ctx, c.ServerDNS, network)
	if err != nil {
		instance.Close()
		return nil, err
	}
	instance.SetOutboundDNS(resolver, resolver.strategy())
	return &dnsOwnedCore{Core: instance, resolver: resolver}, nil
}

// stdout belongs to the parent readiness protocol, including during core startup.
// Android merges stderr into the bounded diagnostics reader; host tools keep it separate.
func init() {
	clog.RegisterHandler(clog.NewLogger(clog.CreateStderrLogWriter()))
	if err := xlog.RegisterHandlerCreator(xlog.LogType_Console, func(_ xlog.LogType, _ xlog.HandlerCreatorOptions) (clog.Handler, error) {
		return clog.NewLogger(clog.CreateStderrLogWriter()), nil
	}); err != nil {
		panic(err)
	}
}

func main() {
	capabilities := descriptor
	if socksIngressPatch == "exact-udp-source-v1" {
		capabilities = strings.Replace(capabilities, `"capabilities":[`, `"capabilities":["udp-authenticated-source-port-v1",`, 1)
	}
	if physicalDialerPatch == "fail-closed-v1" {
		capabilities = strings.Replace(capabilities, `"capabilities":[`, `"capabilities":["`+bridge.PhysicalNetworkCapability+`",`, 1)
	}
	bridge.Main(capabilities, create)
}
