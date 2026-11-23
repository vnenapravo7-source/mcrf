package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"math/big"
	"net"
	"net/netip"
	"os"
	"regexp"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	quic "github.com/sagernet/quic-go"
)

const (
	alpn             = "mcrf-owner-quic-v1"
	peerName         = "owned-quic.invalid"
	exchangeDeadline = 5 * time.Second
)

var (
	noncePattern         = regexp.MustCompile("^[0-9a-f]{32}$")
	errVPNBinding        = errors.New("VPN socket binding denied")
	errUnsupportedClient = errors.New("Android fixture client required")
)

type reports struct {
	sync.Mutex
	out io.Writer
}

func (r *reports) emit(fields map[string]any) {
	r.Lock()
	defer r.Unlock()
	_ = json.NewEncoder(r.out).Encode(fields)
}

type observedSocket struct {
	net.PacketConn
	received atomic.Uint64
}

// Keep the kernel UDP socket connected while exposing only PacketConn to QUIC.
// The library's raw UDP optimization would otherwise bypass the checks here.
type connectedSocket struct {
	net.Conn
}

func udpTuple(address net.Addr) (netip.AddrPort, bool) {
	udp, ok := address.(*net.UDPAddr)
	if !ok {
		return netip.AddrPort{}, false
	}
	tuple := udp.AddrPort()
	return netip.AddrPortFrom(tuple.Addr().Unmap(), tuple.Port()), tuple.IsValid()
}

func (c connectedSocket) ReadFrom(data []byte) (int, net.Addr, error) {
	n, err := c.Read(data)
	return n, c.RemoteAddr(), err
}

func (c connectedSocket) WriteTo(data []byte, destination net.Addr) (int, error) {
	peer, connected := udpTuple(c.RemoteAddr())
	requested, valid := udpTuple(destination)
	if !connected || !valid || requested != peer {
		return 0, errors.New("QUIC destination differs from the connected peer")
	}
	return c.Write(data)
}

func quicConfig() *quic.Config {
	return &quic.Config{
		Versions: []quic.Version{quic.Version1}, MaxIdleTimeout: exchangeDeadline,
		HandshakeIdleTimeout: exchangeDeadline, InitialPacketSize: 1200, DisablePathMTUDiscovery: true,
	}
}

func (p *observedSocket) ReadFrom(data []byte) (int, net.Addr, error) {
	n, from, err := p.PacketConn.ReadFrom(data)
	if err == nil {
		p.received.Add(1)
	}
	return n, from, err
}

func makeServerTLS() (*tls.Config, []byte, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, nil, err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: serial.Add(serial, big.NewInt(1)), DNSNames: []string{peerName},
		NotBefore: now.Add(-time.Minute), NotAfter: now.Add(10 * time.Minute),
		BasicConstraintsValid: true, IsCA: true,
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	return &tls.Config{MinVersion: tls.VersionTLS13, NextProtos: []string{alpn},
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}, der, nil
}

func pinDigest(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

func makeClientTLS(encoded, pin string) (*tls.Config, error) {
	if len(encoded) > 2048 || len(pin) != sha256.Size*2 {
		return nil, errors.New("invalid certificate pin")
	}
	der, err := base64.RawURLEncoding.Strict().DecodeString(encoded)
	if err != nil || pinDigest(der) != pin {
		return nil, errors.New("invalid certificate pin")
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return &tls.Config{MinVersion: tls.VersionTLS13, NextProtos: []string{alpn},
		RootCAs: pool, ServerName: peerName, VerifyConnection: func(state tls.ConnectionState) error {
			if len(state.PeerCertificates) != 1 || !bytes.Equal(state.PeerCertificates[0].Raw, der) {
				return errors.New("unexpected peer certificate")
			}
			return nil
		}}, nil
}

func negotiated(conn *quic.Conn) bool {
	state := conn.ConnectionState()
	return state.Version == quic.Version1 && !state.Used0RTT && state.TLS.HandshakeComplete &&
		state.TLS.Version == tls.VersionTLS13 && state.TLS.NegotiatedProtocol == alpn
}

func protocolResult(event, nonce string) map[string]any {
	return map[string]any{"event": event, "nonce": nonce, "quicVersion": 1, "tls": "1.3", "alpn": alpn}
}

func serverEcho(ctx context.Context, conn *quic.Conn) (string, error) {
	if !negotiated(conn) {
		return "", errors.New("unexpected negotiation")
	}
	stream, err := conn.AcceptStream(ctx)
	if err != nil {
		return "", err
	}
	defer stream.CancelRead(0)
	defer stream.Close()
	if err = stream.SetDeadline(time.Now().Add(exchangeDeadline)); err != nil {
		return "", err
	}
	message, err := io.ReadAll(io.LimitReader(stream, 33))
	if err != nil || !noncePattern.Match(message) {
		return "", errors.New("invalid nonce")
	}
	_, err = stream.Write(message)
	return string(message), err
}

func serve(parent context.Context, socket net.PacketConn, control io.Reader, output *reports) error {
	config, der, err := makeServerTLS()
	if err != nil {
		return err
	}
	observed := &observedSocket{PacketConn: socket}
	transport := &quic.Transport{Conn: observed}
	defer transport.Close()
	listener, err := transport.Listen(config, quicConfig())
	if err != nil {
		return err
	}
	defer listener.Close()
	ctx, cancel := context.WithTimeout(parent, 70*time.Second)
	defer cancel()
	var accepted atomic.Uint64
	snapshot := func(event string) {
		output.emit(map[string]any{"event": event, "accepted": accepted.Load(), "datagrams": observed.received.Load()})
	}
	port := socket.LocalAddr().(*net.UDPAddr).Port
	output.emit(map[string]any{"event": "ready", "port": port,
		"certificate": base64.RawURLEncoding.EncodeToString(der), "certificateSha256": pinDigest(der)})
	go func() {
		scanner := bufio.NewScanner(control)
		scanner.Buffer(make([]byte, 64), 64)
		for scanner.Scan() {
			switch scanner.Text() {
			case "SNAPSHOT":
				snapshot("snapshot")
			case "STOP":
				cancel()
				return
			default:
				cancel()
				return
			}
		}
		cancel()
	}()
	for ctx.Err() == nil {
		conn, err := listener.Accept(ctx)
		if err != nil {
			if ctx.Err() == nil {
				return err
			}
			break
		}
		exchange, finish := context.WithTimeout(ctx, exchangeDeadline)
		nonce, err := serverEcho(exchange, conn)
		if err == nil {
			accepted.Add(1)
			output.emit(protocolResult("accepted", nonce))
			select {
			case <-conn.Context().Done():
			case <-exchange.Done():
			}
		}
		_ = conn.CloseWithError(0, "")
		finish()
	}
	snapshot("stopped")
	return nil
}

func exchange(ctx context.Context, socket net.PacketConn, target net.Addr, config *tls.Config, nonce string) (string, int) {
	transport := &quic.Transport{Conn: socket}
	defer transport.Close()
	conn, err := transport.Dial(ctx, target, config, quicConfig())
	if err != nil || !negotiated(conn) {
		return "QUIC_HANDSHAKE_FAILED", 21
	}
	defer conn.CloseWithError(0, "")
	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		return "QUIC_EXCHANGE_FAILED", 22
	}
	defer stream.CancelRead(0)
	deadline, _ := ctx.Deadline()
	if err = stream.SetDeadline(deadline); err != nil {
		return "QUIC_EXCHANGE_FAILED", 22
	}
	if _, err = io.WriteString(stream, nonce); err != nil {
		return "QUIC_EXCHANGE_FAILED", 22
	}
	if err = stream.Close(); err != nil {
		return "QUIC_EXCHANGE_FAILED", 22
	}
	echo, err := io.ReadAll(io.LimitReader(stream, 33))
	if err != nil || !bytes.Equal(echo, []byte(nonce)) {
		return "QUIC_EXCHANGE_FAILED", 22
	}
	return "QUIC_COMPLETE", 0
}

func laneAddresses(lane string) (source string, target string, ok bool) {
	switch lane {
	case "p4":
		return "172.19.0.1:0", "198.18.0.2:18444", true
	case "wg":
		return "10.77.0.2:0", "10.77.0.1:18444", true
	default:
		return "", "", false
	}
}

func bindFailure(err error) (string, int) {
	if errors.Is(err, errVPNBinding) {
		return "QUIC_BIND_DENIED", 20
	}
	return "QUIC_FIXTURE_FAILED", 2
}

func androidClient(network uint64, source, target string, config *tls.Config, nonce string) (string, int) {
	ctx, cancel := context.WithTimeout(context.Background(), exchangeDeadline)
	defer cancel()
	dialer := net.Dialer{
		LocalAddr: net.UDPAddrFromAddrPort(netip.MustParseAddrPort(source)),
		Control: func(_, _ string, raw syscall.RawConn) error {
			return bindAndroidNetwork(raw, network)
		},
	}
	socket, err := dialer.DialContext(ctx, "udp4", target)
	if err != nil {
		return bindFailure(err)
	}
	defer socket.Close()
	return exchange(ctx, connectedSocket{socket}, socket.RemoteAddr(), config, nonce)
}

func run(args []string, input io.Reader, output *reports) int {
	if len(args) == 0 {
		return 2
	}
	flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	switch args[0] {
	case "server":
		listen := flags.String("listen", "", "owned fixture socket")
		if flags.Parse(args[1:]) != nil || flags.NArg() != 0 {
			return 2
		}
		address, err := netip.ParseAddrPort(*listen)
		if err != nil || !address.Addr().Is4() ||
			!address.Addr().IsLoopback() && (runtime.GOOS == "android" || !address.Addr().IsPrivate()) {
			return 2
		}
		socket, err := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(address))
		if err != nil {
			return 2
		}
		defer socket.Close()
		if serve(context.Background(), socket, input, output) != nil {
			return 2
		}
		return 0
	case "client":
		network := flags.Uint64("network", 0, "Android VPN network handle")
		lane := flags.String("lane", "p4", "owned fixture lane")
		cert := flags.String("certificate", "", "pinned test certificate")
		pin := flags.String("certificate-sha256", "", "pinned test certificate digest")
		nonce := flags.String("nonce", "", "owned fixture nonce")
		if flags.Parse(args[1:]) != nil || flags.NArg() != 0 || runtime.GOOS != "android" ||
			*network == 0 || !noncePattern.MatchString(*nonce) {
			return 2
		}
		source, target, ok := laneAddresses(*lane)
		if !ok {
			return 2
		}
		tlsConfig, err := makeClientTLS(*cert, *pin)
		if err != nil {
			return 2
		}
		status, code := androidClient(*network, source, target, tlsConfig, *nonce)
		result := map[string]any{"event": "client_result", "status": status}
		if code == 0 {
			result = protocolResult("client_result", *nonce)
			result["status"] = status
		}
		output.emit(result)
		return code
	default:
		return 2
	}
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, &reports{out: os.Stdout}))
}
