package main

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Actual production Go layer against an independently pinned, authenticated
// Reality server. Activated only by the whole common loopback fixture runner.
// No imported/saved endpoint or decoy fallback may be supplied by this test.
func TestOwnedRealityConfigured49(t *testing.T) {
	input := os.Getenv("MCRF_P049_OWNED_PEER")
	if input == "" {
		t.Skip("explicit owned loopback batch only")
	}
	var peer struct {
		Port      int    `json:"port"`
		PublicKey string `json:"publicKey"`
		Target    string `json:"target"`
		PQVerify  string `json:"pqVerify"`
	}
	if len(input) > 8192 || json.Unmarshal([]byte(input), &peer) != nil || peer.Port < 1024 || peer.Port > 65535 {
		t.Fatal("invalid owned peer")
	}
	host, port, e := net.SplitHostPort(peer.Target)
	target, e2 := strconv.Atoi(port)
	if e != nil || e2 != nil || host != "127.0.0.1" || target < 1024 || target > 65535 {
		t.Fatal("target is not owned loopback")
	}
	var names []string
	if json.Unmarshal([]byte(os.Getenv("MCRF_P049_FINGERPRINTS")), &names) != nil || len(names) == 0 || len(names) > 80 {
		t.Fatal("explicit owned identity matrix required")
	}
	for _, name := range names {
		for _, protocols := range [][]string{nil, {}, {"http/1.1"}, {"h2", "http/1.1"}} {
			for _, emptySNI := range []bool{false, true} {
				label := name + "/alpn-" + strings.Join(protocols, ",")
				if protocols == nil {
					label += "absent"
				}
				if emptySNI {
					label += "/empty-sni"
				}
				t.Run(label, func(t *testing.T) {
					o := baseOptions49()
					o.Security = "reality"
					o.Fingerprint = name
					o.PublicKey = peer.PublicKey
					o.ShortID = "abcd"
					o.SNI = "fixture.example.test"
					o.Host = "127.0.0.1"
					o.PQVerify = peer.PQVerify
					if emptySNI {
						o.SNI = ""
					}
					if protocols != nil {
						o.ALPN = &protocols
					}
					ownedRealityExchange49(t, peer.Port, target, o, false)
				})
			}
		}
		for _, negative := range []string{"wrong-key", "wrong-short-id", "wrong-pq-verifier"} {
			if negative == "wrong-pq-verifier" && peer.PQVerify == "" {
				continue
			}
			t.Run(name+"/"+negative, func(t *testing.T) {
				o := baseOptions49()
				o.Security = "reality"
				o.Fingerprint = name
				o.PublicKey = peer.PublicKey
				o.ShortID = "abcd"
				o.SNI = "fixture.example.test"
				o.Host = "127.0.0.1"
				o.PQVerify = peer.PQVerify
				switch negative {
				case "wrong-key":
					b, e := base64.RawURLEncoding.DecodeString(o.PublicKey)
					if e != nil || len(b) != 32 {
						t.Fatal("fixture key")
					}
					b[0] ^= 1
					o.PublicKey = base64.RawURLEncoding.EncodeToString(b)
				case "wrong-short-id":
					o.ShortID = "dcba"
				case "wrong-pq-verifier":
					b, e := base64.RawURLEncoding.DecodeString(o.PQVerify)
					if e != nil || len(b) < 32 {
						t.Fatal("fixture PQ verifier")
					}
					b[31] ^= 1
					o.PQVerify = base64.RawURLEncoding.EncodeToString(b)
				}
				ownedRealityExchange49(t, peer.Port, target, o, true)
			})
		}
	}
}

func ownedRealityExchange49(t *testing.T, port, target int, o options49, negative bool) {
	t.Helper()
	data, _ := json.Marshal(o)
	parsed, e := parseOptions49(data)
	if e != nil {
		t.Fatal("valid structured fixture config refused", e)
	}
	if e = validateHello49(parsed); e != nil {
		t.Fatal("fixture identity incompatible before dialing", e)
	}
	raw, e := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), time.Second)
	if e != nil {
		t.Fatal("owned peer not reachable")
	}
	r := newRegistry49()
	epoch := r.begin()
	defer r.stop(epoch)
	id, e := r.open(epoch, raw, parsed, nil)
	if negative {
		if e == nil || id != 0 {
			t.Fatal("unauthenticated Reality session published")
		}
		return
	}
	if e != nil {
		t.Fatal("authenticated Reality handshake failed", e)
	}
	s := r.get(id)
	if s == nil {
		t.Fatal("no authenticated owned session")
	}
	idBytes := []byte{0x4b, 0x72, 0xaf, 0x9e, 0x32, 0xb4, 0x4b, 0x14, 0xa2, 0xfc, 0x4a, 0x91, 0, 0xd2, 0xeb, 0x21}
	request := append([]byte{0}, idBytes...)
	request = append(request, 0, 1, byte(target>>8), byte(target), 1, 127, 0, 0, 1)
	request = append(request, []byte("GET / HTTP/1.1\r\nHost: owned.fixture.test\r\nConnection: close\r\n\r\n")...)
	if e = s.write(request); e != nil {
		t.Fatal("owned VLESS write failed", e)
	}
	var reply []byte
	until := time.Now().Add(3 * time.Second)
	for len(reply) < 128 && time.Now().Before(until) {
		var buf [128]byte
		n, readErr := s.read(buf[:], false)
		reply = append(reply, buf[:n]...)
		if len(reply) >= 2 && len(reply) >= 2+int(reply[1])+12 && reply[0] == 0 {
			start := 2 + int(reply[1])
			if strings.HasPrefix(string(reply[start:]), "HTTP/1.1 204") {
				return
			}
		}
		if readErr != nil && readErr != io.EOF && status49(readErr) != -108 {
			t.Fatal("owned VLESS reply failed", readErr)
		}
		if readErr == io.EOF {
			break
		}
	}
	t.Fatal("no actual authenticated owned HTTP204")
}
