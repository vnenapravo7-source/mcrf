package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// NEW scoped cases for the common source-ready batch, not old DNS regression.
func TestManagedPeerDnsTcpUdpBothFamiliesAndRealClose(t *testing.T){
	nonce:=strings.Repeat("a",32);packets:=map[bool]net.PacketConn{};listeners:=map[bool]net.Listener{}
	d,err:=newManagedFixtureDNS(nonce,
		func(address *net.UDPAddr)(net.PacketConn,error){v6:=address.IP.To4()==nil;network,host:="udp4","127.0.0.1";if v6{network,host="udp6","::1"};socket,err:=net.ListenPacket(network,net.JoinHostPort(host,"0"));if err==nil{packets[v6]=socket};return socket,err},
		func(address *net.TCPAddr)(net.Listener,error){v6:=address.IP.To4()==nil;network,host:="tcp4","127.0.0.1";if v6{network,host="tcp6","::1"};socket,err:=net.Listen(network,net.JoinHostPort(host,"0"));if err==nil{listeners[v6]=socket};return socket,err})
	if err!=nil{t.Fatal(err)};defer d.Close()
	var clients []net.Conn;defer func(){for _,client:=range clients{client.Close()}}()
	for _,v6:=range []bool{false,true}{for _,aaaa:=range []bool{false,true}{
		query:=append([]byte{0x12,0x34,1,0,0,1,0,0,0,0,0,0,32},[]byte(nonce)...);query=append(query,7);query=append(query,[]byte("invalid")...);kind:=byte(1);if aaaa{kind=28};query=append(query,0,0,kind,0,1)
		want:=fixtureDNS(query,nonce)
		udp,err:=net.Dial("udp",packets[v6].LocalAddr().String());if err!=nil{t.Fatal(err)};udp.SetDeadline(time.Now().Add(2*time.Second));if _,err=udp.Write(query);err!=nil{t.Fatal(err)}
		buffer:=make([]byte,513);n,err:=udp.Read(buffer);udp.Close();if err!=nil||!bytes.Equal(want,buffer[:n]){t.Fatal("UDP owned reply",err)}
		tcp,err:=net.Dial("tcp",listeners[v6].Addr().String());if err!=nil{t.Fatal(err)};clients=append(clients,tcp);tcp.SetDeadline(time.Now().Add(2*time.Second))
		wire:=binary.BigEndian.AppendUint16(nil,uint16(len(query)));wire=append(wire,query...);if _,err=tcp.Write(wire);err!=nil{t.Fatal(err)}
		var size [2]byte;if _,err=io.ReadFull(tcp,size[:]);err!=nil{t.Fatal(err)};answer:=make([]byte,binary.BigEndian.Uint16(size[:]));if _,err=io.ReadFull(tcp,answer);err!=nil||!bytes.Equal(want,answer){t.Fatal("TCP owned reply",err)}
	}}
	d.Close();if d.total()!=8||d.udp4.Load()!=2||d.udp6.Load()!=2||d.tcp4.Load()!=2||d.tcp6.Load()!=2||d.refused.Load()!=0{t.Fatal("independent transport counts")}
	for _,client:=range clients{var b [1]byte;if n,err:=client.Read(b[:]);n!=0||err!=io.EOF{t.Fatal("close did not unwind TCP",err)}}
}
func TestManagedPeerAuditDetectsInternalAliasWithoutChangingPermittedPackets(t *testing.T){
	for _,v6:=range []bool{false,true}{
		size,src,dst:=20,12,16;alias:=net.IPv4(198,19,255,53).To4();if v6{size,src,dst=40,8,24;alias=net.ParseIP("fdfe:dcba:9876:dd54::53").To16()}
		for _,at:=range []int{src,dst}{packet:=make([]byte,size);packet[0]=0x45;if v6{packet[0]=0x60};copy(packet[at:],alias)
			sink:=&auditSink{};audit:=&auditedTun{Device:sink};if n,err:=audit.Write([][]byte{packet},0);err!=nil||n!=1||audit.internalDNS.Load()!=1||sink.writes!=1{t.Fatal("internal alias escaped independent audit")}
		}
	}
	if internalDNSFixturePacket(nil)||internalDNSFixturePacket([]byte{0x60}){t.Fatal("truncated alias audit")}
}
