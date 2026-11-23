//go:build with_utls

package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	boxTLS "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"io"
	"net"
	"reflect"
	"testing"
	"time"
)

// No listener/DNS/external traffic. EOF follows the client's first flight.
type helloCapture49 struct{ wire []byte }

func (c *helloCapture49) Read(p []byte) (int, error) { return 0, io.EOF }
func (c *helloCapture49) Write(p []byte) (int, error) {
	c.wire = append(c.wire, p...)
	return len(p), nil
}
func (c *helloCapture49) Close() error { return nil }
func (c *helloCapture49) LocalAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 19049}
}
func (c *helloCapture49) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 19050}
}
func (c *helloCapture49) SetDeadline(time.Time) error      { return nil }
func (c *helloCapture49) SetReadDeadline(time.Time) error  { return nil }
func (c *helloCapture49) SetWriteDeadline(time.Time) error { return nil }

func capturedALPN49(t *testing.T, wire []byte) []string {
	t.Helper()
	var hello []byte
	for len(wire) >= 5 {
		size := int(binary.BigEndian.Uint16(wire[3:]))
		if len(wire) < 5+size {
			t.Fatal("captured TLS record length")
		}
		if wire[0] == 22 {
			hello = append(hello, wire[5:5+size]...)
		}
		wire = wire[5+size:]
		if len(hello) >= 4 {
			need := 4 + (int(hello[1]) << 16) + (int(hello[2]) << 8) + int(hello[3])
			if len(hello) >= need {
				hello = hello[:need]
				break
			}
		}
	}
	if len(hello) < 39 || hello[0] != 1 {
		t.Fatal("no captured ClientHello")
	}
	at := 38
	at += 1 + int(hello[at])
	if at+2 > len(hello) {
		t.Fatal("session ID")
	}
	at += 2 + int(binary.BigEndian.Uint16(hello[at:]))
	if at >= len(hello) {
		t.Fatal("cipher suites")
	}
	at += 1 + int(hello[at])
	if at == len(hello) {
		return nil
	}
	if at+2 > len(hello) {
		t.Fatal("compression")
	}
	size := int(binary.BigEndian.Uint16(hello[at:]))
	at += 2
	if at+size != len(hello) {
		t.Fatal("extensions")
	}
	for at < len(hello) {
		if at+4 > len(hello) {
			t.Fatal("extension header")
		}
		kind := binary.BigEndian.Uint16(hello[at:])
		n := int(binary.BigEndian.Uint16(hello[at+2:]))
		at += 4
		if at+n > len(hello) {
			t.Fatal("extension length")
		}
		if kind == 16 {
			p := hello[at : at+n]
			if len(p) < 2 || int(binary.BigEndian.Uint16(p))+2 != len(p) {
				t.Fatal("ALPN length")
			}
			var values []string
			for k := 2; k < len(p); {
				count := int(p[k])
				k++
				if !(count > 0 && k+count <= len(p)) {
					t.Fatal("ALPN token")
				}
				values = append(values, string(p[k:k+count]))
				k += count
			}
			return values
		}
		at += n
	}
	return nil
}

func TestP049PinnedTLSAllFingerprintExplicitALPN(t *testing.T) {
	fingerprints := []string{"", "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized", "custom",
		"chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk"}
	for _, fp := range fingerprints {
		for _, list := range [][]string{{"h2"}, {"http/1.1"}, {"http/1.1", "h2"}, {}} {
			t.Run(fp+"/"+strings49(list), func(t *testing.T) {
				raw, _ := json.Marshal(map[string]any{"enabled": true, "server_name": "fixture.example.test", "alpn": list, "utls": map[string]any{"enabled": true, "fingerprint": fp}})
				var options option.OutboundTLSOptions
				if json.Unmarshal(raw, &options) != nil {
					t.Fatal("fixture options")
				}
				ctx, cancel := context.WithTimeout(context.Background(), time.Second)
				defer cancel()
				config, err := boxTLS.NewClient(ctx, logger.NOP(), "fixture.example.test", options)
				if err != nil {
					t.Fatal("fixture TLS config")
				}
				conn := &helloCapture49{}
				client, err := config.Client(conn)
				if err != nil {
					t.Fatal("fixture TLS client")
				}
				defer client.Close()
				if client.HandshakeContext(ctx) == nil {
					t.Fatal("EOF fixture cannot authenticate")
				}
				got := capturedALPN49(t, conn.wire)
				if len(list) == 0 {
					if len(got) != 0 {
						t.Fatal("explicit empty ALPN not preserved")
					}
				} else if !reflect.DeepEqual(got, list) {
					t.Fatal("explicit ALPN changed or dropped")
				}
			})
		}
	}
}
func strings49(values []string) string {
	if len(values) == 0 {
		return "empty"
	}
	out := values[0]
	for _, v := range values[1:] {
		out += "+" + v
	}
	return out
}
