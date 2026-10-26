// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

func TestMcrfProtocolsSshOriginalWire(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_, private, e := ed25519.GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	signer, e := ssh.NewSignerFromKey(private)
	if e != nil {
		t.Fatal(e)
	}
	target, stop := wireEcho(t)
	defer stop()
	options := &ssh.ServerConfig{PasswordCallback: func(meta ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
		if meta.User() != "fixture" || string(password) != "owned-password" {
			return nil, errors.New("fixture credentials rejected")
		}
		return nil, nil
	}}
	options.AddHostKey(signer)
	ln, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	var acceptedMu sync.Mutex
	var accepted net.Conn
	cleanup := func() {
		ln.Close()
		acceptedMu.Lock()
		defer acceptedMu.Unlock()
		if accepted != nil {
			accepted.Close()
		}
	}
	defer cleanup()
	done := make(chan struct{})
	go func() {
		defer close(done)
		raw, e := ln.AcceptTCP()
		if e != nil {
			return
		}
		acceptedMu.Lock()
		accepted = raw
		acceptedMu.Unlock()
		defer raw.Close()
		raw.SetDeadline(time.Now().Add(15 * time.Second))
		conn, channels, requests, e := ssh.NewServerConn(raw, options)
		if e != nil {
			return
		}
		defer conn.Close()
		go ssh.DiscardRequests(requests)
		for request := range channels {
			var destination struct {
				Host       string
				Port       uint32
				Origin     string
				OriginPort uint32
			}
			if request.ChannelType() != "direct-tcpip" || ssh.Unmarshal(request.ExtraData(), &destination) != nil || destination.Host != "127.0.0.1" || destination.Port != uint32(target.Port) {
				request.Reject(ssh.Prohibited, "owned target only")
				continue
			}
			upstream, e := net.DialTimeout("tcp", net.JoinHostPort(destination.Host, strconv.Itoa(int(destination.Port))), time.Second)
			if e != nil {
				request.Reject(ssh.ConnectionFailed, "owned echo unavailable")
				continue
			}
			channel, requests, e := request.Accept()
			if e != nil {
				upstream.Close()
				continue
			}
			go ssh.DiscardRequests(requests)
			copyDone := make(chan struct{})
			go func() {
				defer close(copyDone)
				io.Copy(upstream, channel)
				if c, ok := upstream.(*net.TCPConn); ok {
					c.CloseWrite()
				}
			}()
			io.Copy(channel, upstream)
			channel.CloseWrite()
			channel.Close()
			upstream.Close()
			<-copyDone
		}
	}()
	c := fixtureConfig("ssh")
	c["outbound"] = map[string]any{"type": "ssh", "server": "127.0.0.1", "server_port": ln.Addr().(*net.TCPAddr).Port, "user": "fixture", "password": "owned-password", "host_key": strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))}
	data, _ := json.Marshal(c)
	cfg, e := readConfig(strings.NewReader(string(data)))
	if e != nil {
		t.Fatal(e)
	}
	client, out, e := createCore(ctx, cfg)
	if e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	if e = client.Start(); e != nil {
		t.Fatal(e)
	}
	conn, e := out.DialContext(ctx, "tcp", target)
	if e != nil {
		t.Fatal(e)
	}
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	payload := []byte("owned-original-ssh-forwarding")
	if e = writeAll(conn, payload); e != nil {
		conn.Close()
		t.Fatal(e)
	}
	response := make([]byte, len(payload))
	_, e = io.ReadFull(conn, response)
	conn.Close()
	if e != nil || string(response) != string(payload) {
		t.Fatalf("actual SSH wire: %v", e)
	}
	client.Close()
	cleanup()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("SSH fixture did not retire")
	}
}
