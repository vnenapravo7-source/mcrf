package main

// Source package P049: reference identities come from the pinned upstream itself,
// never a handwritten assumption that every browser uses Chrome's extensions.
// No TCP listener, external peer, imported profile, or private key is exported.
import (
	"encoding/binary"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	utls "github.com/refraction-networking/utls"
	xtls "github.com/xtls/xray-core/transport/internet/tls"
)

type fingerprint49 struct {
	Name             string   `json:"name"`
	Client           string   `json:"client"`
	Version          string   `json:"version"`
	CipherSuites     []uint16 `json:"cipherSuites"`
	Extensions       []uint16 `json:"extensions"`
	Versions         []uint16 `json:"versions"`
	ShareGroups      []uint16 `json:"shareGroups"`
	Groups           []uint16 `json:"groups"`
	ALPN             []string `json:"alpn"`
	SignatureSchemes []uint16 `json:"signatureSchemes"`
	TLS13            bool     `json:"tls13"`
	X25519           bool     `json:"x25519"`
	MLKEM            bool     `json:"mlkem"`
	StandardTLS      bool     `json:"standardTls"`
	Unavailable      bool     `json:"unavailable"`
	RealityForbidden bool     `json:"realityForbidden"`
}

func normalize49(x uint16) uint16 {
	if x&0x0f0f == 0x0a0a && byte(x) == byte(x>>8) {
		return 0x0a0a
	}
	return x
}
func extensionIDs49(t *testing.T, raw []byte) []uint16 {
	t.Helper()
	// uTLS Raw is a handshake message, without its five-byte record header.
	if len(raw) < 39 || raw[0] != 1 {
		t.Fatal("reference ClientHello framing")
	}
	at := 38
	at += 1 + int(raw[at])
	if at+2 > len(raw) {
		t.Fatal("reference session ID")
	}
	n := int(binary.BigEndian.Uint16(raw[at:]))
	at += 2 + n
	if at >= len(raw) {
		t.Fatal("reference cipher suites")
	}
	at += 1 + int(raw[at])
	if at == len(raw) {
		return nil
	}
	if at+2 > len(raw) {
		t.Fatal("reference compression")
	}
	n = int(binary.BigEndian.Uint16(raw[at:]))
	at += 2
	if at+n != len(raw) {
		t.Fatal("reference extension length")
	}
	var ids []uint16
	for at < len(raw) {
		if at+4 > len(raw) {
			t.Fatal("reference extension header")
		}
		ids = append(ids, normalize49(binary.BigEndian.Uint16(raw[at:])))
		n = int(binary.BigEndian.Uint16(raw[at+2:]))
		at += 4 + n
		if at > len(raw) {
			t.Fatal("reference extension body")
		}
	}
	return ids
}

func fingerprintReference49(t *testing.T, name string) fingerprint49 {
	t.Helper()
	r := fingerprint49{Name: name}
	r.RealityForbidden = name == "unsafe" || name == "hellogolang"
	id := xtls.GetFingerprint(name)
	if id == nil {
		if name == "unsafe" {
			r.StandardTLS = true
			return r
		} // permitted ONLY ordinary TLS, not Reality
		r.Unavailable = true
		return r
	}
	r.Client = id.Client
	r.Version = id.Version
	if id.Client == "Golang" {
		r.StandardTLS = true
		return r
	}
	local, peer := net.Pipe()
	defer local.Close()
	defer peer.Close()
	// Registry/shape reference is a COLD Hello, with no resumed ticket. The
	// explicit option omits an empty PSK extension rather than inventing a ticket.
	// This does not claim that the installed Xray worker accepts every profile.
	conn := utls.UClient(local, &utls.Config{ServerName: "fixture.example.test", SessionTicketsDisabled: true, OmitEmptyPsk: true}, *id)
	if err := conn.BuildHandshakeState(); err != nil {
		t.Fatalf("reference %s cannot build", name)
	}
	h := conn.HandshakeState.Hello
	r.Extensions = extensionIDs49(t, h.Raw)
	for _, v := range h.CipherSuites {
		r.CipherSuites = append(r.CipherSuites, normalize49(v))
	}
	for _, v := range h.SupportedVersions {
		r.Versions = append(r.Versions, normalize49(v))
		r.TLS13 = r.TLS13 || v == 0x0304
	}
	for _, v := range h.SupportedCurves {
		r.Groups = append(r.Groups, normalize49(uint16(v)))
	}
	for _, v := range h.KeyShares {
		r.ShareGroups = append(r.ShareGroups, normalize49(uint16(v.Group)))
		r.X25519 = r.X25519 || uint16(v.Group) == 0x001d
		r.MLKEM = r.MLKEM || uint16(v.Group) == 0x11ec
	}
	r.ALPN = append([]string(nil), h.AlpnProtocols...)
	for _, v := range h.SupportedSignatureAlgorithms {
		r.SignatureSchemes = append(r.SignatureSchemes, uint16(v))
	}
	return r
}

func TestP049AllRegisteredFingerprintReferences(t *testing.T) {
	names := map[string]bool{"": true}
	for _, pool := range []map[string]*utls.ClientHelloID{xtls.PresetFingerprints, xtls.ModernFingerprints, xtls.OtherFingerprints} {
		for name := range pool {
			names[name] = true
		}
	}
	ordered := make([]string, 0, len(names))
	for name := range names {
		ordered = append(ordered, name)
	}
	sort.Strings(ordered)
	rows := make([]fingerprint49, 0, len(ordered))
	for _, name := range ordered {
		rows = append(rows, fingerprintReference49(t, name))
	}
	// These are protocol facts, not a claim of Steer parity or remote acceptance.
	for _, name := range []string{"android", "360"} {
		r := fingerprintReference49(t, name)
		if r.TLS13 || r.X25519 || r.MLKEM {
			t.Fatalf("%s is no longer the pinned TLS12-only identity; update matrix", name)
		}
	}
	edge := fingerprintReference49(t, "edge")
	if !edge.TLS13 || !edge.X25519 || edge.MLKEM {
		t.Fatal("pinned Edge_Auto changed")
	}
	chrome := fingerprintReference49(t, "chrome")
	if !chrome.TLS13 || !chrome.MLKEM {
		t.Fatal("pinned Chrome_Auto changed")
	}
	if directory := os.Getenv("MCRF_P049_REFERENCE_DIR"); directory != "" {
		dir, err := filepath.Abs(directory)
		if err != nil {
			t.Fatal("reference output path")
		}
		root, err := filepath.Abs("D:/MCRF/Builds/Android")
		if err != nil {
			t.Fatal("reference root")
		}
		rel, err := filepath.Rel(root, dir)
		if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			t.Fatal("reference output outside owned build root")
		}
		body, err := json.MarshalIndent(struct {
			Schema         int             `json:"schema"`
			RemoteAccepted bool            `json:"remoteAccepted"`
			Rows           []fingerprint49 `json:"rows"`
		}{1, false, rows}, "", "  ")
		if err != nil {
			t.Fatal("reference encode")
		}
		file, err := os.OpenFile(filepath.Join(dir, "fingerprints49.json"), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err != nil {
			t.Fatal("reference exists or directory unavailable")
		}
		_, err = file.Write(body)
		closeErr := file.Close()
		if err != nil || closeErr != nil {
			t.Fatal("reference write")
		}
	}
}
