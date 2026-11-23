package client

import (
	"bytes"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestMcrfOlcSocks5OfferedMethods(t *testing.T) {
	for _, test := range []struct {
		name  string
		auth  bool
		offer []byte
		want  byte
	}{
		{"required-auth-not-offered", true, []byte{0}, 255},
		{"no-auth-not-offered", false, []byte{2}, 255},
		{"empty-offer", true, nil, 255},
		{"required-auth-offered", true, []byte{0, 2}, 2},
		{"no-auth-offered", false, []byte{2, 0}, 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			server, peer := net.Pipe()
			defer server.Close()
			defer peer.Close()
			deadline := time.Now().Add(time.Second)
			server.SetDeadline(deadline)
			peer.SetDeadline(deadline)
			client := &Client{}
			if test.auth {
				client.socksUser = "u"
				client.socksPass = "p"
			}
			done := make(chan error, 1)
			go func() { done <- client.socks5Handshake(server) }()
			if _, err := peer.Write(append([]byte{5, byte(len(test.offer))}, test.offer...)); err != nil {
				t.Fatal(err)
			}
			reply := make([]byte, 2)
			if _, err := io.ReadFull(peer, reply); err != nil || !bytes.Equal(reply, []byte{5, test.want}) {
				t.Fatalf("method response = %v, %v; want [5 %d]", reply, err, test.want)
			}
			if test.want == 2 {
				if _, err := peer.Write([]byte{1, 1, 'u', 1, 'p'}); err != nil {
					t.Fatal(err)
				}
				if _, err := io.ReadFull(peer, reply); err != nil || !bytes.Equal(reply, []byte{1, 0}) {
					t.Fatalf("credential response = %v, %v", reply, err)
				}
			}
			err := <-done
			if test.want == 255 {
				if !errors.Is(err, ErrSOCKSAuthFailed) {
					t.Fatalf("expected authentication rejection, got %v", err)
				}
			} else if err != nil {
				t.Fatal(err)
			}
		})
	}
}
