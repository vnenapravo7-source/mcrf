package main

import (
 "context"
 "io"
 "net"
 "net/netip"
 "sync/atomic"
 "testing"
 "time"

 "golang.org/x/net/proxy"
 "golang.zx2c4.com/wireguard/tun/netstack"
)

func TestRawTransportMTU(t *testing.T) {
 for _, c := range []struct{requested, expected int}{{576,576},{1100,1100},{1280,1280},{1300,1280},{1400,1280},{1500,1280},{9000,1280}} {
  if got:=rawTransportMTU(c.requested); got!=c.expected {t.Fatalf("MTU %d: got %d, want %d",c.requested,got,c.expected)}
 }
}

// Exercise the old parent's explicit1300 argument, not just the new default.
func TestOldParentUploadDoesNotNeedWireFragments(t *testing.T) {
 ctx,cancel:=context.WithCancel(context.Background());defer cancel()
 raw,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)})
 if err!=nil{t.Fatal(err)};defer raw.Close()
 if err=configureRawSocket(raw);err!=nil{t.Fatal(err)}
 device,network,err:=netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.66.66.1")},nil,1300)
 if err!=nil{t.Fatal(err)};defer device.Close()
 ready:=make(chan struct{});var peer *net.UDPAddr
 var maxPacket,dataPackets atomic.Int64
 go func(){b:=make([]byte,65536);first:=true;for{
  n,p,e:=raw.ReadFromUDP(b);if e!=nil{return}
  if first{peer=p;close(ready);first=false}
  for old:=maxPacket.Load();int64(n)>old;old=maxPacket.Load(){if maxPacket.CompareAndSwap(old,int64(n)){break}}
  if n>40&&b[9]==6 {h:=int(b[0]&15)*4;if n>h+20&&n>h+int(b[h+12]>>4)*4{dataPackets.Add(1)}}
  if _,e=device.Write([][]byte{b[:n]},0);e!=nil{return}
 }}()
 go func(){select{case<-ready:case<-ctx.Done():return};b:=[][]byte{make([]byte,65536)};sizes:=make([]int,1);for{n,e:=device.Read(b,sizes,0);if e!=nil{return};for i:=0;i<n;i++{if _,e=raw.WriteToUDP(b[i][:sizes[i]],peer);e!=nil{return}}}}()
 listener,err:=network.ListenTCPAddrPort(netip.MustParseAddrPort("10.66.66.1:8080"));if err!=nil{t.Fatal(err)};defer listener.Close()
 const size=256<<10
 serverDone:=make(chan error,1)
 go func(){c,e:=listener.Accept();if e!=nil{serverDone<-e;return};defer c.Close();c.SetDeadline(time.Now().Add(10*time.Second));_,e=io.CopyN(io.Discard,c,size);if e==nil{_,e=c.Write([]byte{1})};serverDone<-e}()
 address:=freeTCP(t);done:=make(chan error,1)
 go func(){done<-runWithMTU(ctx,raw.LocalAddr().String(),"10.66.66.2","1.1.1.1",address,1300)}()
 probe:=connect(t,address);probe.Close()
 dialer,err:=proxy.SOCKS5("tcp",address,nil,&net.Dialer{Timeout:3*time.Second});if err!=nil{t.Fatal(err)}
 c,err:=dialer.Dial("tcp","10.66.66.1:8080");if err!=nil{t.Fatal(err)};defer c.Close();c.SetDeadline(time.Now().Add(10*time.Second))
 if _,err=c.Write(make([]byte,size));err!=nil{t.Fatal(err)}
 reply:=make([]byte,1);if _,err=io.ReadFull(c,reply);err!=nil{t.Fatal(err)}
 if err=<-serverDone;err!=nil{t.Fatal(err)}
 if got:=maxPacket.Load();got>1280{t.Fatalf("raw packet %d exceeds wire ceiling",got)}
 if dataPackets.Load()<2{t.Fatal("bulk upload was not exercised")}
 cancel();select{case err=<-done:if err!=nil{t.Fatal(err)};case<-time.After(3*time.Second):t.Fatal("shutdown timeout")}
}
