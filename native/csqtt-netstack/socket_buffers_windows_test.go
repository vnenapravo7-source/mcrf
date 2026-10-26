package main

import (
 "net"
 "testing"
 "golang.org/x/sys/windows"
)

func TestRawSocketBufferCapacity(t *testing.T) {
 peer,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)})
 if err!=nil{t.Fatal(err)};defer peer.Close()
 socket,err:=net.DialUDP("udp4",nil,peer.LocalAddr().(*net.UDPAddr))
 if err!=nil{t.Fatal(err)};defer socket.Close()
 if err=configureRawSocket(socket);err!=nil{t.Fatal(err)}
 raw,err:=socket.SyscallConn();if err!=nil{t.Fatal(err)}
 for _,option:=range []int{windows.SO_RCVBUF,windows.SO_SNDBUF}{
  var size int;var queryErr error
  if err=raw.Control(func(fd uintptr){size,queryErr=windows.GetsockoptInt(windows.Handle(fd),windows.SOL_SOCKET,option)});err!=nil{t.Fatal(err)}
  if queryErr!=nil{t.Fatal(queryErr)}
  if size<rawSocketBufferBytes{t.Fatalf("socket option %d has %d bytes, need %d",option,size,rawSocketBufferBytes)}
  t.Logf("actual Windows socket option %d: %d bytes",option,size)
 }
 socket.Close()
 if configureRawSocket(socket)==nil{t.Fatal("closed socket silently accepted")}
}
