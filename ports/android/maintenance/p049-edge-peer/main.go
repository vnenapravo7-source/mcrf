// Owned loopback-only legacy Reality endpoint. Unmodified upstream library,
// same revision as Xray1.8.24; current servers intentionally reject Edge85.
package main

import (
	"bufio"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	reality "github.com/xtls/reality"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"time"
)

func main() {
	if os.Getenv("MCRF_P049_OWNED_WIRE") != "1" {
		os.Exit(2)
	}
	curve := os.Getenv("MCRF_P049_CURVE")
	if curve != "" {
		os.Exit(2)
	}
	decoy := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "owned-decoy") }))
	decoy.EnableHTTP2 = true
	decoy.Config.ErrorLog = log.New(io.Discard, "", 0)
	decoy.StartTLS()
	defer decoy.Close()
	key, e := ecdh.X25519().GenerateKey(rand.Reader)
	if e != nil {
		os.Exit(2)
	}
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		os.Exit(2)
	}
	defer ln.Close()
	cfg := &reality.Config{DialContext: (&net.Dialer{Timeout: 5 * time.Second}).DialContext, Type: "tcp", Dest: decoy.Listener.Addr().String(), ServerNames: map[string]bool{"fixture.example.test": true, "": true}, PrivateKey: key.Bytes(), ShortIds: map[[8]byte]bool{{0xab, 0xcd}: true}, MaxTimeDiff: time.Minute, SessionTicketsDisabled: true}
	// This pinned REALITY server always exchanges X25519. CurvePreferences
	// does not change that code path: never label it a P256 wire oracle.
	go func() {
		for {
			raw, e := ln.Accept()
			if e != nil {
				return
			}
			go serve(raw, cfg)
		}
	}()
	public, _ := json.Marshal(map[string]any{"port": ln.Addr().(*net.TCPAddr).Port, "publicKey": base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), "target": "127.0.0.1:19049", "curve": curve})
	fmt.Println("OWNED_REALITY49=" + string(public))
	done := make(chan struct{})
	go func() { io.Copy(io.Discard, os.Stdin); close(done) }()
	select {
	case <-done:
	case <-time.After(300 * time.Second):
	}
}
func serve(raw net.Conn, cfg *reality.Config) {
	defer raw.Close()
	raw.SetDeadline(time.Now().Add(8 * time.Second))
	conn, e := reality.Server(context.Background(), raw, cfg)
	if e != nil {
		return
	}
	defer conn.Close()
	head := make([]byte, 18)
	if _, e = io.ReadFull(conn, head); e != nil {
		return
	}
	want := []byte{0x4b, 0x72, 0xaf, 0x9e, 0x32, 0xb4, 0x4b, 0x14, 0xa2, 0xfc, 0x4a, 0x91, 0x00, 0xd2, 0xeb, 0x21}
	if head[0] != 0 || head[17] != 0 {
		return
	}
	for i, v := range want {
		if head[i+1] != v {
			return
		}
	}
	dest := make([]byte, 8)
	if _, e = io.ReadFull(conn, dest); e != nil {
		return
	}
	if dest[0] != 1 || dest[3] != 1 || dest[4] != 127 || dest[5] != 0 || dest[6] != 0 || dest[7] != 1 {
		return
	}
	if int(dest[1])*256+int(dest[2]) != 19049 {
		return
	}
	reader := bufio.NewReader(io.LimitReader(conn, 4096))
	for i := 0; i < 20; i++ {
		line, e := reader.ReadString('\n')
		if e != nil {
			return
		}
		if line == "\r\n" {
			conn.Write(append([]byte{0, 0}, []byte("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")...))
			return
		}
	}
}
