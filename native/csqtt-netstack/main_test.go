package main

import (
 "context"
 "encoding/binary"
 "io"
 "net"
 "net/netip"
 "testing"
 "time"
 "golang.org/x/net/proxy"
 "golang.zx2c4.com/wireguard/tun/netstack"
)

func freeTCP(t *testing.T) string { l,e:=net.Listen("tcp4","127.0.0.1:0");if e!=nil{t.Fatal(e)};defer l.Close();return l.Addr().String() }
func connect(t *testing.T,address string) net.Conn { var c net.Conn;var e error;for i:=0;i<150;i++{c,e=net.DialTimeout("tcp4",address,20*time.Millisecond);if e==nil{c.SetDeadline(time.Now().Add(4*time.Second));return c};time.Sleep(10*time.Millisecond)};t.Fatal(e);return nil }
func TestUDPAndShutdown(t *testing.T){
 raw,e:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if e!=nil{t.Fatal(e)};defer raw.Close();raw.SetDeadline(time.Now().Add(4*time.Second))
 address:=freeTCP(t);ctx,cancel:=context.WithCancel(context.Background());defer cancel();done:=make(chan error,1)
 go func(){done<-run(ctx,raw.LocalAddr().String(),"10.66.66.2","1.1.1.1",address)}()
 c:=connect(t,address);defer c.Close();c.Write([]byte{5,1,0});reply:=make([]byte,2);if _,e=io.ReadFull(c,reply);e!=nil||reply[1]!=0{t.Fatal("auth",e)}
 c.Write([]byte{5,3,0,1,0,0,0,0,0,0});reply=make([]byte,10);if _,e=io.ReadFull(c,reply);e!=nil||reply[1]!=0{t.Fatal("associate",e)}
 client,e:=net.DialUDP("udp4",nil,&net.UDPAddr{IP:net.IP(reply[4:8]),Port:int(binary.BigEndian.Uint16(reply[8:10]))});if e!=nil{t.Fatal(e)};defer client.Close();client.SetDeadline(time.Now().Add(4*time.Second))
 payload:=[]byte("local-only-udp-fixture");client.Write(append([]byte{0,0,0,1,203,0,113,40,0x27,0x0f},payload...))
 packet:=make([]byte,65536);n,source,e:=raw.ReadFromUDP(packet);if e!=nil{t.Fatal(e)};packet=packet[:n];h:=int(packet[0]&15)*4
 if h<20||n<h+8||packet[9]!=17||string(packet[h+8:])!=string(payload){t.Fatal("invalid raw datagram")}
 for i:=0;i<4;i++{packet[12+i],packet[16+i]=packet[16+i],packet[12+i]};packet[h],packet[h+2]=packet[h+2],packet[h];packet[h+1],packet[h+3]=packet[h+3],packet[h+1];packet[h+6],packet[h+7]=0,0;packet[10],packet[11]=0,0
 var sum uint32;for i:=0;i<h;i+=2{sum+=uint32(binary.BigEndian.Uint16(packet[i:i+2]))};for sum>65535{sum=(sum&65535)+(sum>>16)};binary.BigEndian.PutUint16(packet[10:12],^uint16(sum));raw.WriteToUDP(packet,source)
 reply=make([]byte,1024);n,e=client.Read(reply);if e!=nil||n<10||string(reply[10:n])!=string(payload){t.Fatal("UDP return path",e)}
 cancel();select{case e=<-done:if e!=nil{t.Fatal(e)};case<-time.After(2*time.Second):t.Fatal("cancel stalled")}
 if l,e:=net.Listen("tcp4",address);e!=nil{t.Fatal("listener leaked",e)}else{l.Close()}
}
func TestTCPRawRoundTrip(t *testing.T){
 raw,e:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if e!=nil{t.Fatal(e)};defer raw.Close()
 device,network,e:=netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.66.66.1")},nil,1300);if e!=nil{t.Fatal(e)};defer device.Close()
 var peer *net.UDPAddr;peerReady:=make(chan struct{})
 go func(){buf:=make([]byte,65536);first:=true;for{n,p,e:=raw.ReadFromUDP(buf);if e!=nil{return};if first{peer=p;close(peerReady);first=false};device.Write([][]byte{buf[:n]},0)}}()
 go func(){<-peerReady;b:=[][]byte{make([]byte,65536)};sizes:=make([]int,1);for{n,e:=device.Read(b,sizes,0);if e!=nil{return};for i:=0;i<n;i++{raw.WriteToUDP(b[i][:sizes[i]],peer)}}}()
 listener,e:=network.ListenTCPAddrPort(netip.MustParseAddrPort("10.66.66.1:8080"));if e!=nil{t.Fatal(e)};defer listener.Close()
 go func(){c,e:=listener.Accept();if e!=nil{return};defer c.Close();c.SetDeadline(time.Now().Add(5*time.Second));io.Copy(c,c)}()
 ctx,cancel:=context.WithCancel(context.Background());defer cancel();address:=freeTCP(t);done:=make(chan error,1);go func(){done<-run(ctx,raw.LocalAddr().String(),"10.66.66.2","1.1.1.1",address)}()
 probe:=connect(t,address);probe.Close();dialer,e:=proxy.SOCKS5("tcp",address,nil,&net.Dialer{Timeout:3*time.Second});if e!=nil{t.Fatal(e)}
 c,e:=dialer.Dial("tcp","10.66.66.1:8080");if e!=nil{t.Fatal(e)};c.SetDeadline(time.Now().Add(4*time.Second));data:=[]byte("tcp-through-ip-adapter");c.Write(data);out:=make([]byte,len(data));if _,e=io.ReadFull(c,out);e!=nil||string(out)!=string(data){t.Fatal("TCP mismatch",e)};c.Close();cancel()
 select{case <-done:case<-time.After(2*time.Second):t.Fatal("TCP shutdown stalled")}
}
func TestRejectNonLoopback(t *testing.T){if run(context.Background(),"203.0.113.1:9000","10.66.66.2","1.1.1.1","127.0.0.1:1080")==nil{t.Fatal("remote peer accepted")}}
func TestMTUBounds(t *testing.T){for _,mtu:=range []int{0,575,9001}{if runWithMTU(context.Background(),"127.0.0.1:9","10.66.66.2","1.1.1.1","127.0.0.1:1080",mtu)==nil{t.Fatal("invalid MTU accepted")}}}

// A brief CSQTT raw-port rebind must not kill every existing SOCKS connection.
func TestRawPeerRestartKeepsProxy(t *testing.T){
 raw,e:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if e!=nil{t.Fatal(e)};defer raw.Close();endpoint:=raw.LocalAddr().String()
 address:=freeTCP(t);ctx,cancel:=context.WithCancel(context.Background());defer cancel();done:=make(chan error,1);go func(){done<-run(ctx,endpoint,"10.66.66.2","1.1.1.1",address)}()
 control:=connect(t,address);defer control.Close();control.Write([]byte{5,1,0});auth:=make([]byte,2);if _,e=io.ReadFull(control,auth);e!=nil{t.Fatal(e)}
 control.Write([]byte{5,3,0,1,0,0,0,0,0,0});reply:=make([]byte,10);if _,e=io.ReadFull(control,reply);e!=nil||reply[1]!=0{t.Fatal("associate",e)}
 client,e:=net.DialUDP("udp4",nil,&net.UDPAddr{IP:net.IP(reply[4:8]),Port:int(binary.BigEndian.Uint16(reply[8:10]))});if e!=nil{t.Fatal(e)};defer client.Close()
 packet:=[]byte{0,0,0,1,203,0,113,40,0x27,0x0f,1};client.Write(packet);raw.SetReadDeadline(time.Now().Add(2*time.Second));buffer:=make([]byte,65536);if _,_,e=raw.ReadFromUDP(buffer);e!=nil{t.Fatal(e)}
 raw.Close();for i:=0;i<3;i++{client.Write(packet);time.Sleep(80*time.Millisecond)}
 select{case e=<-done:t.Fatal("one transient raw UDP error stopped all SOCKS flows",e);default:}
 rebound,e:=net.ListenPacket("udp4",endpoint);if e!=nil{t.Fatal(e)};defer rebound.Close();rebound.SetReadDeadline(time.Now().Add(2*time.Second));client.Write(packet);if _,_,e=rebound.ReadFrom(buffer);e!=nil{t.Fatal("raw transport did not recover",e)}
 cancel();select{case<-done:case<-time.After(2*time.Second):t.Fatal("shutdown stalled")}
}

func TestBurstClientsDoNotResetSOCKS(t *testing.T){
 raw,e:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if e!=nil{t.Fatal(e)};defer raw.Close()
 address:=freeTCP(t);ctx,cancel:=context.WithCancel(context.Background());defer cancel();done:=make(chan error,1);go func(){done<-run(ctx,raw.LocalAddr().String(),"10.66.66.2","1.1.1.1",address)}()
 var clients []net.Conn;defer func(){for _,c:=range clients{c.Close()};cancel();select{case<-done:case<-time.After(2*time.Second):t.Error("burst shutdown stalled")}}()
 for i:=0;i<96;i++{c:=connect(t,address);clients=append(clients,c);c.Write([]byte{5,1,0});auth:=make([]byte,2);if _,e=io.ReadFull(c,auth);e!=nil||auth[1]!=0{t.Fatalf("client %d abruptly reset: %v",i+1,e)};c.Write([]byte{5,3,0,1,0,0,0,0,0,0});reply:=make([]byte,10);if _,e=io.ReadFull(c,reply);e!=nil||reply[1]!=0{t.Fatalf("association %d failed: %v",i+1,e)};c.SetDeadline(time.Time{})}
}

func TestCapacityQueuesAndRecovers(t *testing.T){
 raw,e:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if e!=nil{t.Fatal(e)};defer raw.Close()
 address:=freeTCP(t);ctx,cancel:=context.WithCancel(context.Background());defer cancel();done:=make(chan error,1);go func(){done<-run(ctx,raw.LocalAddr().String(),"10.66.66.2","1.1.1.1",address)}()
 var clients []net.Conn;defer func(){for _,c:=range clients{c.Close()};cancel();select{case<-done:case<-time.After(2*time.Second):t.Error("capacity shutdown stalled")}}()
 for i:=0;i<socksMaxClients;i++{c:=connect(t,address);clients=append(clients,c);c.Write([]byte{5,1,0});auth:=make([]byte,2);if _,e=io.ReadFull(c,auth);e!=nil{t.Fatal("fill",i,e)}}
 extra:=connect(t,address);defer extra.Close();extra.Write([]byte{5,1,0});extra.SetReadDeadline(time.Now().Add(100*time.Millisecond));auth:=make([]byte,2);_,e=io.ReadFull(extra,auth);if timeout,ok:=e.(net.Error);!ok||!timeout.Timeout(){t.Fatal("overflow reset instead of queue",e)}
 clients[0].Close();extra.SetReadDeadline(time.Now().Add(2*time.Second));if _,e=io.ReadFull(extra,auth);e!=nil||auth[1]!=0{t.Fatal("queued client did not resume",e)}
}
