package client

import (
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestMcrfSOCKSRejectsInvalidCredentials(t *testing.T) {
	for _, values := range [][4]string{{"", "p", "", ""}, {"u", "", "", ""}, {"u", "p", "remote", ""}} {
		if _, err := McrfSOCKS(values[0], values[1], values[2], values[3]); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}

func TestMcrfSOCKSLocalDoesNotConsumeConnectRequest(t *testing.T) {
	bridge, err := McrfSOCKS("u", "p", "", "")
	if err != nil {
		t.Fatal(err)
	}
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(time.Second))
	server.SetDeadline(time.Now().Add(time.Second))
	done := make(chan error, 1)
	go func() {
		if e := bridge.LocalAuth(server); e != nil {
			done <- e
			return
		}
		request := make([]byte, 4)
		_, e := io.ReadFull(server, request)
		if e == nil && string(request) != string([]byte{5, 1, 0, 1}) {
			e = errors.New("CONNECT corrupted")
		}
		done <- e
	}()
	if err = mcrfWrite(client, []byte{5, 1, 2}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2)
	if _, err = io.ReadFull(client, reply); err != nil || string(reply) != string([]byte{5, 2}) {
		t.Fatal("method", reply, err)
	}
	if err = mcrfWrite(client, []byte{1, 1, 'u', 1, 'p'}); err != nil {
		t.Fatal(err)
	}
	if _, err = io.ReadFull(client, reply); err != nil || string(reply) != string([]byte{1, 0}) {
		t.Fatal("auth", reply, err)
	}
	if err = mcrfWrite(client, []byte{5, 1, 0, 1}); err != nil {
		t.Fatal(err)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
}

func TestMcrfSOCKSLocalRejectsUnauthenticatedClient(t *testing.T) {
	bridge, _ := McrfSOCKS("u", "p", "", "")
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(time.Second))
	server.SetDeadline(time.Now().Add(time.Second))
	done := make(chan error, 1)
	go func() { done <- bridge.LocalAuth(server) }()
	mcrfWrite(client, []byte{5, 1, 0})
	reply := make([]byte, 2)
	if _, err := io.ReadFull(client, reply); err != nil || reply[1] != 255 {
		t.Fatal(reply, err)
	}
	if err := <-done; err == nil {
		t.Fatal("unauthenticated client accepted")
	}
}

func TestMcrfSOCKSRemoteRefusalIsTerminal(t *testing.T) {
	bridge, _ := McrfSOCKS("local", "secret", "remote", "password")
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(time.Second))
	server.SetDeadline(time.Now().Add(time.Second))
	go func() { greeting := make([]byte, 3); io.ReadFull(server, greeting); mcrfWrite(server, []byte{5, 255}) }()
	if err := bridge.RemoteAuth(client); !errors.Is(err, ErrMcrfRemoteAuth) || !mcrfTerminal(err) {
		t.Fatal(err)
	}
}

func TestMcrfSOCKSLocalRejectsWrongPassword(t *testing.T) {
	bridge, _ := McrfSOCKS("u", "p", "", "")
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(time.Second))
	server.SetDeadline(time.Now().Add(time.Second))
	done := make(chan error, 1)
	go func() { done <- bridge.LocalAuth(server) }()
	mcrfWrite(client, []byte{5, 1, 2})
	reply := make([]byte, 2)
	io.ReadFull(client, reply)
	mcrfWrite(client, []byte{1, 1, 'u', 1, 'x'})
	if _, err := io.ReadFull(client, reply); err != nil || reply[1] != 1 {
		t.Fatal(reply, err)
	}
	if err := <-done; err == nil {
		t.Fatal("wrong password accepted")
	}
}

func TestMcrfSOCKSRemoteWrongProtocolIsTerminal(t *testing.T) {
	bridge, _ := McrfSOCKS("local", "secret", "", "")
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(time.Second))
	server.SetDeadline(time.Now().Add(time.Second))
	go func() {
		greeting := make([]byte, 3)
		io.ReadFull(server, greeting)
		mcrfWrite(server, []byte{'S', 'S'})
	}()
	if err := bridge.RemoteAuth(client); !errors.Is(err, ErrMcrfRemoteProtocol) || !mcrfTerminal(err) {
		t.Fatal(err)
	}
}
