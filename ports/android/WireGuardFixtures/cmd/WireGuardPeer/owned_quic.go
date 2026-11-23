package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
	"math/big"
	"net"
	"regexp"
	"sync"
	"sync/atomic"
	"time"

	quic "github.com/sagernet/quic-go"
)

const ownedQuicAlpn = "mcrf-owner-quic-v1"
const ownedQuicLimit = 5 * time.Second

var ownedQuicNonce = regexp.MustCompile("^[0-9a-f]{32}$")

type ownedQuicSocket struct {
	net.PacketConn
	datagrams atomic.Uint64
}

func (socket *ownedQuicSocket) ReadFrom(buffer []byte) (int, net.Addr, error) {
	n, address, err := socket.PacketConn.ReadFrom(buffer)
	if err == nil {
		socket.datagrams.Add(1)
	}
	return n, address, err
}

type ownedQuicPeer struct {
	socket         *ownedQuicSocket
	transport      *quic.Transport
	listener       *quic.Listener
	cancel         context.CancelFunc
	done           chan struct{}
	closeOnce      sync.Once
	closeErr       error
	handshakes     int
	accepted       int
	nonces         [2]string
	certificate    string
	certificatePin string
}

func newOwnedQuicPeer(socket net.PacketConn) (*ownedQuicPeer, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}
	now := time.Now()
	cert := &x509.Certificate{
		SerialNumber: serial.Add(serial, big.NewInt(1)), DNSNames: []string{"owned-quic.invalid"},
		NotBefore: now.Add(-time.Minute), NotAfter: now.Add(10 * time.Minute),
		BasicConstraintsValid: true, IsCA: true,
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	observed := &ownedQuicSocket{PacketConn: socket}
	transport := &quic.Transport{Conn: observed}
	listener, err := transport.Listen(&tls.Config{MinVersion: tls.VersionTLS13,
		NextProtos:   []string{ownedQuicAlpn},
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}},
		&quic.Config{Versions: []quic.Version{quic.Version1}, MaxIdleTimeout: ownedQuicLimit,
			InitialPacketSize: 1200, DisablePathMTUDiscovery: true})
	if err != nil {
		_ = transport.Close()
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	pin := sha256.Sum256(der)
	peer := &ownedQuicPeer{socket: observed, transport: transport, listener: listener,
		cancel: cancel, done: make(chan struct{}), certificate: base64.RawURLEncoding.EncodeToString(der),
		certificatePin: hex.EncodeToString(pin[:])}
	go peer.serve(ctx)
	return peer, nil
}

func (peer *ownedQuicPeer) serve(ctx context.Context) {
	defer close(peer.done)
	for {
		conn, err := peer.listener.Accept(ctx)
		if err != nil {
			return
		}
		peer.handshakes++
		if peer.handshakes > len(peer.nonces) {
			_ = conn.CloseWithError(1, "")
			return
		}
		bounded, cancel := context.WithTimeout(ctx, ownedQuicLimit)
		nonce, err := echoOwnedQuic(bounded, conn)
		if err == nil && (peer.accepted == 0 || nonce != peer.nonces[0]) {
			peer.nonces[peer.accepted] = nonce
			peer.accepted++
			select {
			case <-conn.Context().Done():
			case <-bounded.Done():
			}
		}
		_ = conn.CloseWithError(0, "")
		cancel()
	}
}

func echoOwnedQuic(ctx context.Context, conn *quic.Conn) (string, error) {
	state := conn.ConnectionState()
	if state.Version != quic.Version1 || state.Used0RTT || !state.TLS.HandshakeComplete ||
		state.TLS.Version != tls.VersionTLS13 || state.TLS.NegotiatedProtocol != ownedQuicAlpn {
		return "", errors.New("unexpected fixture QUIC negotiation")
	}
	stream, err := conn.AcceptStream(ctx)
	if err != nil {
		return "", err
	}
	defer stream.CancelRead(0)
	deadline, _ := ctx.Deadline()
	if err = stream.SetDeadline(deadline); err != nil {
		return "", err
	}
	nonce, err := io.ReadAll(io.LimitReader(stream, 33))
	if err != nil || !ownedQuicNonce.Match(nonce) {
		return "", errors.New("invalid fixture QUIC nonce")
	}
	if _, err = stream.Write(nonce); err != nil {
		return "", err
	}
	return string(nonce), stream.Close()
}

func (peer *ownedQuicPeer) close() error {
	peer.closeOnce.Do(func() {
		peer.cancel()
		_ = peer.listener.Close()
		select {
		case <-peer.done:
		case <-time.After(ownedQuicLimit):
			peer.closeErr = errors.New("fixture QUIC server still running")
		}
		peer.closeErr = errors.Join(peer.closeErr, peer.transport.Close(), peer.socket.Close())
	})
	return peer.closeErr
}

func (peer *ownedQuicPeer) evidence() map[string]any {
	digest := sha256.Sum256([]byte("mcrf-wg-quic-v1\n" + peer.nonces[0] + "\n" + peer.nonces[1] + "\n"))
	return map[string]any{"handshakes": peer.handshakes, "accepted": peer.accepted,
		"datagrams": peer.socket.datagrams.Load(), "nonceSha256": hex.EncodeToString(digest[:]), "closed": true}
}
