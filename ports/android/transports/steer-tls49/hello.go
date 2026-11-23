package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"time"

	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	utls "github.com/refraction-networking/utls"
	"golang.org/x/crypto/hkdf"
)

func applyALPN49(c *utls.UConn, protocols []string) error {
	if protocols == nil {
		return c.BuildHandshakeState()
	}
	if err := c.BuildHandshakeState(); err != nil {
		return err
	}
	c.MCRFExplicitALPN49(protocols)
	// HelloGolang builds directly from Config, not Extensions. Its configured
	// NextProtos was set before the first build, so no preset mutation is needed.
	if c.ClientHelloID == utls.HelloGolang {
		return c.MCRFMarshalHello49()
	}
	h2 := false
	for _, p := range protocols {
		h2 = h2 || p == "h2"
	}
	list := make([]utls.TLSExtension, 0, len(c.Extensions)+1)
	found := false
	for _, ext := range c.Extensions {
		switch v := ext.(type) {
		case *utls.ALPNExtension:
			if len(protocols) > 0 && !found {
				v.AlpnProtocols = append([]string(nil), protocols...)
				list = append(list, v)
				found = true
			}
			continue
		case *utls.ApplicationSettingsExtension, *utls.ApplicationSettingsExtensionNew:
			if !h2 {
				continue
			}
		case *utls.UtlsPaddingExtension:
			if len(protocols) > 0 && !found {
				list = append(list, &utls.ALPNExtension{AlpnProtocols: append([]string(nil), protocols...)})
				found = true
			}
		}
		list = append(list, ext)
	}
	if len(protocols) > 0 && !found {
		list = append(list, &utls.ALPNExtension{AlpnProtocols: append([]string(nil), protocols...)})
	}
	c.Extensions = list
	return c.BuildHandshakeState()
}

// Reality authenticates the FINAL Hello (including explicit ALPN). No decoy
// fallback, background spider, external dial, host lookup or secret logging.
// Adapted from pinned Xray e662c22ab109 transport/internet/reality (MPL-2.0).
type reality49 struct {
	conn     *utls.UConn
	auth     []byte
	pq       *mldsa65.PublicKey
	verified bool
}

func (r *reality49) prepare(o options49) error {
	h := r.conn.HandshakeState.Hello
	keys := r.conn.HandshakeState.State13.KeyShareKeys
	if h == nil || keys == nil || len(h.Random) != 32 {
		return errReality
	}
	// Prefer the exact classical X25519 share, or the hybrid's X25519 component.
	// A P256-only Hello cannot authenticate against Reality's X25519 public key.
	private := keys.Ecdhe
	if private == nil || private.Curve() != ecdh.X25519() {
		private = keys.MlkemEcdhe
	}
	if private == nil || private.Curve() != ecdh.X25519() {
		return errReality
	}
	pk, _ := base64.RawURLEncoding.DecodeString(o.PublicKey)
	public, e := ecdh.X25519().NewPublicKey(pk)
	if e != nil {
		return errReality
	}
	shared, e := private.ECDH(public)
	if e != nil {
		return errReality
	}
	r.auth = make([]byte, 32)
	if _, e := hkdf.New(sha256.New, shared, h.Random[:20], []byte("REALITY")).Read(r.auth); e != nil {
		return errReality
	}
	for i := range shared {
		shared[i] = 0
	}
	if o.PQVerify != "" {
		p, _ := base64.RawURLEncoding.DecodeString(o.PQVerify)
		r.pq = new(mldsa65.PublicKey)
		if r.pq.UnmarshalBinary(p) != nil {
			return errReality
		}
	}
	// Set the ID length through the real marshaller, not a fixed raw overwrite
	// when an unusual/versioned Hello originally carried a different ID length.
	h.SessionId = make([]byte, 32)
	if e := r.conn.MCRFMarshalHello49(); e != nil {
		return e
	}
	h = r.conn.HandshakeState.Hello
	if len(h.Raw) < 71 || h.Raw[38] != 32 {
		return errReality
	}
	id := make([]byte, 16)
	id[0] = realityVersionX49
	id[1] = realityVersionY49
	id[2] = realityVersionZ49
	binary.BigEndian.PutUint32(id[4:], uint32(time.Now().Unix()))
	sid, _ := hex.DecodeString(o.ShortID)
	copy(id[8:], sid)
	block, e := aes.NewCipher(r.auth)
	if e != nil {
		return errReality
	}
	aead, e := cipher.NewGCM(block)
	if e != nil {
		return errReality
	}
	h.SessionId = aead.Seal(h.SessionId[:0], h.Random[20:], id, h.Raw)
	copy(h.Raw[39:71], h.SessionId)
	return nil
}

func (r *reality49) verify(raw [][]byte, _ [][]*x509.Certificate) error {
	if len(raw) == 0 || len(raw[0]) > 40960 || len(r.auth) != 32 {
		return errReality
	}
	c, e := x509.ParseCertificate(raw[0])
	if e != nil {
		return errReality
	}
	pub, ok := c.PublicKey.(ed25519.PublicKey)
	if !ok {
		return errReality
	}
	h := hmac.New(sha512.New, r.auth)
	_, _ = h.Write(pub)
	if !hmac.Equal(h.Sum(nil), c.Signature) {
		return errReality
	}
	if r.pq != nil {
		if len(c.Extensions) == 0 || len(c.Extensions[0].Value) != mldsa65.SignatureSize || r.conn.HandshakeState.ServerHello == nil {
			return errReality
		}
		_, _ = h.Write(r.conn.HandshakeState.Hello.Raw)
		_, _ = h.Write(r.conn.HandshakeState.ServerHello.Raw)
		if !mldsa65.Verify(r.pq, h.Sum(nil), nil, c.Extensions[0].Value) {
			return errReality
		}
	}
	r.verified = true
	return nil
}
