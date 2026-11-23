//go:build with_utls

package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	boxTLS "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

// Configured ONLY by the expanded common owned-wire runner. No imported user
// endpoint is accepted: both Reality listener and target must be owned loopback.
func TestP049TLSRealityOwnedVariations(t *testing.T) {
	input := os.Getenv("MCRF_P049_OWNED_PEER")
	if input == "" {
		t.Skip("explicit owned peer fixture only")
	}
	var peer struct {
		Port      int    `json:"port"`
		PublicKey string `json:"publicKey"`
		Target    string `json:"target"`
	}
	if len(input) > 4096 || json.Unmarshal([]byte(input), &peer) != nil || peer.Port < 1024 || peer.Port > 65535 {
		t.Fatal("invalid owned peer")
	}
	host, port, e := net.SplitHostPort(peer.Target)
	target, e2 := strconv.Atoi(port)
	if e != nil || e2 != nil || host != "127.0.0.1" || target < 1024 || target > 65535 {
		t.Fatal("owned target must be loopback")
	}
	var names []string
	if json.Unmarshal([]byte(os.Getenv("MCRF_P049_FINGERPRINTS")), &names) != nil || len(names) == 0 || len(names) > 80 {
		t.Fatal("explicit fingerprint matrix required")
	}
	for _, fp := range names {
		for _, wrong := range []bool{false, true} {
			label := fp
			if wrong {
				label += "/wrong-key"
			}
			t.Run(label, func(t *testing.T) {
				pk, e := base64.RawURLEncoding.DecodeString(peer.PublicKey)
				if e != nil || len(pk) != 32 {
					t.Fatal("owned public key")
				}
				if wrong {
					pk[0] ^= 1
				}
				options := map[string]any{"enabled": true, "server_name": "fixture.example.test", "alpn": []string{"http/1.1"},
					"utls": map[string]any{"enabled": true, "fingerprint": fp}, "reality": map[string]any{"enabled": true, "public_key": base64.RawURLEncoding.EncodeToString(pk), "short_id": "abcd"}}
				data, _ := json.Marshal(options)
				var tlsOptions option.OutboundTLSOptions
				if json.Unmarshal(data, &tlsOptions) != nil {
					t.Fatal("owned client options")
				}
				ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
				defer cancel()
				config, e := boxTLS.NewClient(ctx, logger.NOP(), "fixture.example.test", tlsOptions)
				if e != nil {
					t.Fatal("valid client config refused", fp)
				}
				raw, e := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(peer.Port)), time.Second)
				if e != nil {
					t.Fatal("owned peer unavailable")
				}
				defer raw.Close()
				_ = raw.SetDeadline(time.Now().Add(3 * time.Second))
				client, e := config.Client(raw)
				if e == nil {
					defer client.Close()
					e = client.HandshakeContext(ctx)
				}
				if wrong {
					if e == nil {
						t.Fatal("wrong Reality key authenticated")
					}
					return
				}
				if e != nil {
					t.Fatal("owned Reality handshake failed", fp, e)
				}
				// Same synthetic UUID as the pinned owned peer. Never a saved profile.
				id := []byte{0x4b, 0x72, 0xaf, 0x9e, 0x32, 0xb4, 0x4b, 0x14, 0xa2, 0xfc, 0x4a, 0x91, 0, 0xd2, 0xeb, 0x21}
				request := append([]byte{0}, id...)
				request = append(request, 0, 1, byte(target>>8), byte(target), 1, 127, 0, 0, 1)
				request = append(request, []byte("GET / HTTP/1.1\r\nHost: owned.fixture.test\r\nConnection: close\r\n\r\n")...)
				if _, e = client.Write(request); e != nil {
					t.Fatal("owned VLESS request failed")
				}
				var prefix [2]byte
				if _, e = io.ReadFull(client, prefix[:]); e != nil || prefix[0] != 0 {
					t.Fatal("owned VLESS response")
				}
				if prefix[1] > 0 {
					if _, e = io.CopyN(io.Discard, client, int64(prefix[1])); e != nil {
						t.Fatal(e)
					}
				}
				var status [12]byte
				if _, e = io.ReadFull(client, status[:]); e != nil || !strings.HasPrefix(string(status[:]), "HTTP/1.1 204") {
					t.Fatal("no authenticated owned HTTP204")
				}
			})
		}
	}
}
