package tunnel

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"

	"swiftsstp/internal/mschap"
	"swiftsstp/internal/ppp"
	"swiftsstp/internal/sstp"
	"swiftsstp/internal/sstp/wire"
)

type mcrfOwnedPeer struct {
	conn                                net.Conn
	writeMu                             sync.Mutex
	nonce, certificate, expectedBinding []byte
	networkUp, bound                    bool
}

func (p *mcrfOwnedPeer) write(packet []byte) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	_, err := io.Copy(p.conn, bytes.NewReader(packet))
	return err
}
func (p *mcrfOwnedPeer) SendPPP(frame []byte) error {
	packet, err := wire.EncodeData(frame)
	if err != nil {
		return err
	}
	return p.write(packet)
}
func (p *mcrfOwnedPeer) Authenticated(username, password string, response [mschap.NTResponseLen]byte) {
	p.expectedBinding, _ = sstp.BuildCallConnected(p.nonce, p.certificate, mschap.ClientHLAK(password, response))
}
func (p *mcrfOwnedPeer) NetworkUp()   { p.networkUp = true }
func (p *mcrfOwnedPeer) Closed(error) { p.conn.Close() }

func (p *mcrfOwnedPeer) serve() error {
	reader := bufio.NewReader(p.conn)
	first := true
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return err
		}
		if first {
			if !bytes.HasPrefix([]byte(line), []byte("SSTP_DUPLEX_POST ")) {
				return errors.New("not SSTP HTTP")
			}
			first = false
		}
		if line == "\r\n" {
			break
		}
	}
	if _, err := io.WriteString(p.conn, "HTTP/1.1 200 OK\r\nContent-Length: 18446744073709551615\r\n\r\n"); err != nil {
		return err
	}
	control, body, err := wire.ReadPacket(reader)
	if err != nil || !control {
		return errors.New("missing ConnectRequest")
	}
	message, err := wire.ParseControl(body)
	if err != nil || message.Type != wire.MsgCallConnectRequest {
		return errors.New("bad ConnectRequest")
	}
	cbr := make([]byte, 4+wire.NonceLen)
	cbr[3] = wire.CertHashSHA256
	copy(cbr[4:], p.nonce)
	ack, err := wire.EncodeControl(wire.MsgCallConnectAck, []wire.Attribute{{ID: wire.AttrCryptoBindingReq, Value: cbr}})
	if err != nil {
		return err
	}
	if err = p.write(ack); err != nil {
		return err
	}
	server := ppp.NewServer(ppp.ServerConfig{ClientIP: net.IPv4(192, 0, 2, 2), ServerIP: net.IPv4(192, 0, 2, 1), DNS: []net.IP{net.IPv4(192, 0, 2, 1)}, Auth: func(user string) (string, bool) { return "owned-password", user == "owned-user" }}, p, p)
	server.Start()
	for {
		control, body, err = wire.ReadPacket(reader)
		if err != nil {
			return err
		}
		if control {
			message, err = wire.ParseControl(body)
			if err != nil {
				return err
			}
			switch message.Type {
			case wire.MsgCallConnected:
				packet := []byte{wire.Version, 1, 0, 0}
				binary.BigEndian.PutUint16(packet[2:], uint16(4+len(body)))
				packet = append(packet, body...)
				if len(p.expectedBinding) == 0 || !bytes.Equal(packet, p.expectedBinding) {
					return errors.New("invalid nonce/certificate/MSCHAP crypto binding")
				}
				p.bound = true
			case wire.MsgCallDisconnect:
				ack, _ := wire.EncodeControl(wire.MsgCallDisconnectAck, nil)
				p.write(ack)
				return nil
			case wire.MsgEchoRequest:
				ack, _ := wire.EncodeControl(wire.MsgEchoResponse, nil)
				if err = p.write(ack); err != nil {
					return err
				}
			}
			continue
		}
		if _, ok := ppp.IsIP(body); ok {
			if !p.networkUp || !p.bound {
				return errors.New("IP before authenticated PPP and crypto binding")
			}
			// Echo only inside the owned TLS socket. No routing, forwarding or DNS.
			if err = p.SendPPP(body); err != nil {
				return err
			}
		} else {
			server.Receive(body)
		}
	}
}

func TestMcrfSstpOwnedTLSMSCHAPIPData(t *testing.T) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "owned.test"}, DNSNames: []string{"owned.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	peerDone := make(chan error, 1)
	go func() {
		raw, e := ln.Accept()
		if e != nil {
			peerDone <- e
			return
		}
		defer raw.Close()
		stop := context.AfterFunc(ctx, func() { raw.Close() })
		defer stop()
		conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS12})
		peer := &mcrfOwnedPeer{conn: conn, certificate: der, nonce: make([]byte, wire.NonceLen)}
		if _, e = rand.Read(peer.nonce); e != nil {
			peerDone <- e
			return
		}
		peerDone <- peer.serve()
	}()
	t.Cleanup(func() {
		cancel()
		ln.Close()
		select {
		case <-peerDone:
		case <-time.After(2 * time.Second):
			t.Error("owned SSTP peer did not stop")
		}
	})
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	number, _ := strconv.Atoi(port)
	sess, result, err := Dial(ctx, Config{Server: host, Port: number, SNI: "owned.test", Username: "owned-user", Password: "owned-password", TLSMode: TLSCustomCA, CACerts: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), KeepAlive: -1})
	if err != nil {
		t.Fatal(err)
	}
	defer sess.McrfClose()
	if !result.LocalIP.Equal(net.IPv4(192, 0, 2, 2)) {
		t.Fatal("PPP address was not negotiated")
	}
	payload := append([]byte{0x45, 0, 0, 25, 0, 0, 0, 0, 64, 17, 0, 0, 192, 0, 2, 2, 192, 0, 2, 1}, []byte("OWNED")...)
	if err = sess.WriteIP(payload); err != nil {
		t.Fatal(err)
	}
	received := make(chan []byte, 1)
	go func() {
		buffer := make([]byte, 65535)
		n, e := sess.ReadIP(buffer)
		if e != nil {
			received <- nil
			return
		}
		received <- append([]byte(nil), buffer[:n]...)
	}()
	select {
	case got := <-received:
		if !bytes.Equal(got, payload) {
			t.Fatal("IP payload did not cross TLS/SSTP/MSCHAP/PPP")
		}
	case <-ctx.Done():
		t.Fatal("owned SSTP payload timed out")
	}
}
