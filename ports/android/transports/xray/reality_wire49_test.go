package main

// Owned loopback-only Reality peer for the Android Steer wire diagnostic.
// Ephemeral keys live only in memory. EOF on stdin closes all listeners.
import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	peerlib "github.com/xtls/reality"
	xlog "github.com/xtls/xray-core/common/log"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	reference "github.com/xtls/xray-core/transport/internet/reality"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestP049OwnedRealityWire(t *testing.T) {
	if os.Getenv("MCRF_P049_OWNED_WIRE") != "1" {
		t.Skip("explicit owned fixture only")
	}
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer target.Close()
	decoy := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "owned-decoy") }))
	decoy.Config.ErrorLog = log.New(os.Stderr, "OWNED_REFERENCE49 decoy ", 0)
	decoy.EnableHTTP2 = true
	if os.Getenv("MCRF_P049_PQ_CERT") == "1" {
		// REALITY mirrors the target's encrypted certificate flight size. Its
		// ML-DSA certificate is 3509 bytes; httptest's small default certificate
		// cannot contain that flight. Enlarge ONLY the owned test target's DER,
		// not a verifier, transcript or production peer/library.
		priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			t.Fatal("owned PQ target key")
		}
		cert := &x509.Certificate{SerialNumber: big.NewInt(49), DNSNames: []string{"fixture.example.test"},
			NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
			ExtraExtensions: []pkix.Extension{{Id: []int{2, 999, 49}, Value: make([]byte, 4096)}}}
		der, err := x509.CreateCertificate(rand.Reader, cert, cert, &priv.PublicKey, priv)
		if err != nil || len(der) < 3600 {
			t.Fatal("owned PQ target certificate geometry", err)
		}
		decoy.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: priv}}}
	}
	decoy.StartTLS()
	defer decoy.Close()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal("fixture key")
	}
	port := freePort(t)
	stream := map[string]any{"network": "raw", "security": "reality", "realitySettings": map[string]any{"dest": strings.TrimPrefix(decoy.URL, "https://"), "serverNames": []string{"fixture.example.test", ""}, "privateKey": base64.RawURLEncoding.EncodeToString(key.Bytes()), "shortIds": []string{"abcd"}}}
	var pqPublic string
	ownedPQCertificateShape49(t)
	if os.Getenv("MCRF_P049_PQ_CERT") == "1" {
		var seed [32]byte
		if _, err = rand.Read(seed[:]); err != nil {
			t.Fatal("owned PQ seed")
		}
		pk, _ := mldsa65.NewKeyFromSeed(&seed)
		stream["realitySettings"].(map[string]any)["mldsa65Seed"] = base64.RawURLEncoding.EncodeToString(seed[:])
		pqPublic = base64.RawURLEncoding.EncodeToString(pk.Bytes())
	}
	cfg := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "vless", "settings": map[string]any{"clients": []any{map[string]any{"id": fixtureID}}, "decryption": "none"}, "streamSettings": stream}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1"}}}}}}}
	// Only this exclusive owned fixture: errors are bounded/redacted by runner.
	// No user profiles exist in this process; cryptographic Show stays false.
	cfg["log"] = map[string]any{"loglevel": "debug"}
	raw, _ := json.Marshal(cfg)
	conf, err := serial.LoadJSONConfig(bytes.NewReader(raw))
	if err != nil {
		t.Fatal("fixture config")
	}
	server, err := core.New(conf)
	if err != nil {
		t.Fatal("fixture create")
	}
	defer server.Close()
	if server.Start() != nil {
		t.Fatal("fixture start")
	}
	// Synchronous owned-only handler, independent of the module's production
	// logging sink. The supervising runner bounds and redacts every line.
	xlog.RegisterHandler(ownedLog49{})
	// RAW/Reality starts its real target detector asynchronously. Wait for its
	// actual result before the independent client's four-second IO budget;
	// never seed a fake result or increase production connection timeouts.
	deadline := time.Now().Add(8 * time.Second)
	for {
		ready := true
		for _, sni := range []string{"fixture.example.test", ""} {
			for alpn := range 3 {
				value, ok := peerlib.GlobalPostHandshakeRecordsLens.Load(strings.TrimPrefix(decoy.URL, "https://") + " " + sni + " " + strconv.Itoa(alpn))
				_, measured := value.([]int)
				ready = ready && ok && measured
			}
		}
		if ready {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("owned Reality target detector did not become ready")
		}
		time.Sleep(10 * time.Millisecond)
	}
	fmt.Println("OWNED_REFERENCE49 target-detector-ready PASS")
	// The peer must authenticate with an independent pinned client before it
	// judges Steer; a broken PQ certificate issuer is not a client failure.
	controlOwnedReality49(t, port, key.PublicKey().Bytes(), pqPublic, strings.TrimPrefix(target.URL, "http://"), "chrome")
	public := map[string]any{"port": port, "publicKey": base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), "target": strings.TrimPrefix(target.URL, "http://")}
	if pqPublic != "" {
		public["pqVerify"] = pqPublic
	}
	body, _ := json.Marshal(public)
	fmt.Println("OWNED_REALITY49=" + string(body))
	done := make(chan struct{})
	go func() { io.Copy(io.Discard, os.Stdin); close(done) }()
	select {
	case <-done:
	case <-time.After(300 * time.Second):
		t.Fatal("fixture bounded expiry")
	}
}

type ownedLog49 struct{}

func (ownedLog49) Handle(message xlog.Message) {
	fmt.Println("OWNED_REFERENCE49 " + message.String())
}

func ownedPQCertificateShape49(t *testing.T) {
	t.Helper()
	public, private, e := ed25519.GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal("owned certificate key")
	}
	template := &x509.Certificate{SerialNumber: new(big.Int), ExtraExtensions: []pkix.Extension{{Id: []int{0, 0}, Value: make([]byte, 3309)}}}
	der, e := x509.CreateCertificate(rand.Reader, template, template, public, private)
	if e != nil {
		t.Fatal("owned certificate issuer", e)
	}
	cert, e := x509.ParseCertificate(der)
	if e != nil || len(cert.Extensions) != 1 {
		t.Fatal("owned certificate DER geometry", e)
	}
	offset := bytes.Index(der, cert.Extensions[0].Value)
	fmt.Printf("OWNED_CERTIFICATE49 derBytes=%d extensionOffset=%d extensionBytes=%d signatureBytes=%d\n", len(der), offset, len(cert.Extensions[0].Value), len(cert.Signature))
}

func TestP049OwnedReference(t *testing.T) {
	input := os.Getenv("MCRF_P049_OWNED_PEER")
	if input == "" {
		t.Skip("explicit owned loopback reference only")
	}
	var peer struct {
		Port      int    `json:"port"`
		PublicKey string `json:"publicKey"`
		Target    string `json:"target"`
	}
	if len(input) > 4096 || json.Unmarshal([]byte(input), &peer) != nil || peer.Port < 1024 || peer.Port > 65535 {
		t.Fatal("owned reference peer")
	}
	host, _, err := net.SplitHostPort(peer.Target)
	public, decodeErr := base64.RawURLEncoding.DecodeString(peer.PublicKey)
	if err != nil || host != "127.0.0.1" || decodeErr != nil || len(public) != 32 {
		t.Fatal("owned reference must be loopback")
	}
	// The exact legacy identity is required; no modern-Chrome relabelling.
	controlOwnedReality49(t, peer.Port, public, "", peer.Target, "edge")
}

func controlOwnedReality49(t *testing.T, port int, publicKey []byte, pqPublic, target, fingerprint string) {
	t.Helper()
	raw, e := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if e != nil {
		t.Fatal("owned reference dial")
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(4 * time.Second))
	verifier, _ := base64.RawURLEncoding.DecodeString(pqPublic)
	cfg := &reference.Config{ServerName: "fixture.example.test", Fingerprint: fingerprint, PublicKey: publicKey, ShortId: []byte{0xab, 0xcd, 0, 0, 0, 0, 0, 0}, Mldsa65Verify: verifier,
		// Invalid control character makes http.NewRequest refuse any fallback URL
		// before transport/dial. The independent positive must still be Verified.
		SpiderX: "\x00", SpiderY: make([]int64, 10)}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	client, e := reference.UClient(raw, cfg, ctx, xnet.TCPDestination(xnet.ParseAddress("127.0.0.1"), xnet.Port(port)))
	if e != nil {
		t.Fatal("owned pinned reference Reality handshake failed", e)
	}
	proven, ok := client.(*reference.UConn)
	if !ok || !proven.Verified {
		t.Fatal("owned pinned reference not authenticated")
	}
	_, textPort, e := net.SplitHostPort(target)
	if e != nil {
		t.Fatal("owned target")
	}
	var targetPort int
	if _, e = fmt.Sscan(textPort, &targetPort); e != nil || targetPort < 1024 || targetPort > 65535 {
		t.Fatal("owned target port")
	}
	id := []byte{0x4b, 0x72, 0xaf, 0x9e, 0x32, 0xb4, 0x4b, 0x14, 0xa2, 0xfc, 0x4a, 0x91, 0, 0xd2, 0xeb, 0x21}
	payload := append([]byte{0}, id...)
	payload = append(payload, 0, 1, byte(targetPort>>8), byte(targetPort), 1, 127, 0, 0, 1)
	payload = append(payload, []byte("GET / HTTP/1.1\r\nHost: owned.fixture.test\r\nConnection: close\r\n\r\n")...)
	if n, writeErr := client.Write(payload); writeErr != nil || n != len(payload) {
		t.Fatal("owned pinned reference VLESS write")
	}
	var prefix [2]byte
	if _, e = io.ReadFull(client, prefix[:]); e != nil || prefix != [2]byte{0, 0} {
		t.Fatal("owned pinned reference VLESS response", e)
	}
	var status [12]byte
	if _, e = io.ReadFull(client, status[:]); e != nil || !strings.HasPrefix(string(status[:]), "HTTP/1.1 204") {
		t.Fatal("owned pinned reference no HTTP204", e)
	}
	fmt.Println("OWNED_REFERENCE49 authenticated-vless-http204 PASS")
}
