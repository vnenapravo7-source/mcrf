package client

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"io"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/internal/logger"
)

func TestMcrfMasterAdapterUsesTheOwnerContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	c := &Client{mcrfContext: ctx}
	if c.mcrfRootContext().Err() != context.Canceled {
		t.Fatal("session init detached from process owner")
	}
	if (&Client{}).mcrfRootContext() == nil {
		t.Fatal("ordinary SDK caller fallback missing")
	}
}

func TestMcrfMasterServerCannotRaiseTheAndroidWorkerLimit(t *testing.T) {
	c := &Client{mcrfWorkerLimit: 2}
	if c.mcrfRXWorkers(128) != 2 || c.mcrfRXWorkers(1) != 1 {
		t.Fatal("server policy overrode the explicit local worker budget")
	}
	if (&Client{}).mcrfRXWorkers(8) != 8 {
		t.Fatal("ordinary SDK caller policy changed")
	}
}

func TestMcrfMasterConnectionReleasesItsSlotOnlyOnce(t *testing.T) {
	local, peer := net.Pipe()
	defer peer.Close()
	var releases atomic.Int64
	conn := &mcrfOwnedConnection{Conn: local, release: func() { releases.Add(1) }}
	conn.Close()
	conn.Close()
	if releases.Load() != 1 {
		t.Fatal("admission slot release was not exact-once")
	}
}

func TestMcrfMasterOwnerCancellationClosesIdleHandshake(t *testing.T) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// No SOCKS bytes: the handler stays in its initial read before touching Client.
	listener := &TCPListener{protocolType: "SOCKS5", client: &Client{}}
	done := make(chan struct{})
	go func() { listener.mcrfAccept(ctx, ln); close(done) }()
	conn, err := net.Dial("tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("accept/idle handshake ignored owner cancellation")
	}
	conn.SetReadDeadline(time.Now().Add(time.Second))
	var data [1]byte
	if _, err = conn.Read(data[:]); err == nil {
		t.Fatal("idle accepted socket remained open")
	}
}

func TestMcrfMaster512ClientsQueueInsteadOfReset(t *testing.T) {
	ln,err:=net.Listen("tcp4","127.0.0.1:0");if err!=nil{t.Fatal(err)}
	ctx,cancel:=context.WithCancel(context.Background())
	c:=&Client{cfg:config.ClientConfig{SOCKS5Auth:true,SOCKS5User:"local",SOCKS5Pass:"private"},log:logger.McrfQuiet()}
	listener:=&TCPListener{protocolType:"SOCKS5",client:c}
	done:=make(chan struct{});go func(){listener.mcrfAccept(ctx,ln);close(done)}()
	var clients []net.Conn
	t.Cleanup(func(){cancel();ln.Close();for _,conn:=range clients{conn.Close()};select{case <-done:case <-time.After(time.Second):t.Error("owned listener did not retire")}})
	for i:=0;i<512;i++ {
		conn,e:=net.Dial("tcp4",ln.Addr().String());if e!=nil{t.Fatal(e)};clients=append(clients,conn)
		conn.SetDeadline(time.Now().Add(2*time.Second))
		if _,e=conn.Write([]byte{5,1,2});e!=nil{t.Fatal(e)}
		var reply [2]byte
		if _,e=io.ReadFull(conn,reply[:]);e!=nil||reply!=[2]byte{5,2}{t.Fatalf("admission smaller than512 at%d: %v",i,e)}
		conn.SetDeadline(time.Time{})
	}
	extra,err:=net.Dial("tcp4",ln.Addr().String());if err!=nil{t.Fatal(err)};clients=append(clients,extra)
	if _,err=extra.Write([]byte{5,1,2});err!=nil{t.Fatal(err)}
	extra.SetReadDeadline(time.Now().Add(50*time.Millisecond));var reply [2]byte
	if _,err=io.ReadFull(extra,reply[:]);err==nil{t.Fatal("overflow bypassed admission")}
	if timeout,ok:=err.(net.Error);!ok||!timeout.Timeout(){t.Fatalf("overflow reset instead of waiting: %v",err)}
	clients[0].Close();extra.SetReadDeadline(time.Now().Add(time.Second))
	if _,err=io.ReadFull(extra,reply[:]);err!=nil||reply!=[2]byte{5,2}{t.Fatalf("queued connection did not resume: %v",err)}
}
