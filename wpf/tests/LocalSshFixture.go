package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"golang.org/x/crypto/ssh"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// Loopback-only SSH interoperability fixture. It never runs received commands.
func main() {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		panic(err)
	}
	if err = os.WriteFile(filepath.Join(os.Args[1], "ssh-fixture-key.pem"), pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}), 0600); err != nil {
		panic(err)
	}
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		panic(err)
	}
	config := &ssh.ServerConfig{PasswordCallback: func(c ssh.ConnMetadata, p []byte) (*ssh.Permissions, error) {
		if c.User() == "fixture" && string(p) == "fixture-password" {
			return nil, nil
		}
		return nil, fmt.Errorf("denied")
	}, PublicKeyCallback: func(c ssh.ConnMetadata, k ssh.PublicKey) (*ssh.Permissions, error) {
		if c.User() == "fixture" && string(k.Marshal()) == string(signer.PublicKey().Marshal()) {
			return nil, nil
		}
		return nil, fmt.Errorf("denied")
	}}
	config.AddHostKey(signer)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	defer listener.Close()
	fmt.Printf("PORT|%d\n", listener.Addr().(*net.TCPAddr).Port)
	certificate, err := x509.CreateCertificate(rand.Reader, &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), DNSNames: []string{"localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}, &x509.Certificate{SerialNumber: big.NewInt(1)}, &key.PublicKey, key)
	if err != nil {
		panic(err)
	}
	pair, err := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificate}), pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}))
	if err != nil {
		panic(err)
	}
	https, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	defer https.Close()
	fmt.Printf("TLS|%d\n", https.Addr().(*net.TCPAddr).Port)
	go http.Serve(tls.NewListener(https, &tls.Config{Certificates: []tls.Certificate{pair}, MinVersion: tls.VersionTLS12}), http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("MCRF_TLS_FIXTURE_OK")) }))
	for {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			c.SetDeadline(time.Now().Add(10 * time.Second))
			conn, channels, requests, err := ssh.NewServerConn(c, config)
			if err != nil {
				return
			}
			defer conn.Close()
			go ssh.DiscardRequests(requests)
			for incoming := range channels {
				if incoming.ChannelType() != "session" {
					incoming.Reject(ssh.UnknownChannelType, "session only")
					continue
				}
				ch, reqs, err := incoming.Accept()
				if err != nil {
					continue
				}
				go func() {
					defer ch.Close()
					for r := range reqs {
						if r.Type == "exec" {
							r.Reply(true, nil)
							ch.Write([]byte("MCRF_SSH_FIXTURE_OK\n"))
							ch.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{0}))
							return
						}
						r.Reply(false, nil)
					}
				}()
			}
		}()
	}
}
