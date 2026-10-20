package wgbackend

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net/netip"
	"testing"

	"github.com/sagernet/wireguard-go/tun"
)

func dnsTestSum(data []byte) uint16 {
	var sum uint32
	for len(data)>=2 {sum+=uint32(binary.BigEndian.Uint16(data));data=data[2:]}
	if len(data)==1 {sum+=uint32(data[0])<<8}
	for sum>>16!=0 {sum=(sum&65535)+(sum>>16)}
	return ^uint16(sum)
}
func dnsTestTransportChecksum(p []byte)uint16 {
	v,ok:=dnsPacketView(p);if !ok {panic("invalid fixture")}
	size:=len(p)-v.transport
	pseudo:=append(append([]byte(nil),v.src.AsSlice()...),v.dst.AsSlice()...)
	if v.size==4 {pseudo=append(pseudo,0,v.proto,byte(size>>8),byte(size))}else{
		pseudo=binary.BigEndian.AppendUint32(pseudo,uint32(size));pseudo=append(pseudo,0,0,0,v.proto)
	}
	return dnsTestSum(append(pseudo,p[v.transport:]...))
}
func dnsTestPacket(v6 bool,proto byte,source,destination string,sport,dport uint16,payload []byte)[]byte {
	h:=20;if v6 {h=40};transport:=8;if proto==6 {transport=20}
	p:=make([]byte,h+transport+len(payload));p[0]=0x45;p[8]=64;p[9]=proto
	if v6 {p[0]=0x60;p[6]=proto;p[7]=64;binary.BigEndian.PutUint16(p[4:6],uint16(len(p)-40));copy(p[8:24],netip.MustParseAddr(source).AsSlice());copy(p[24:40],netip.MustParseAddr(destination).AsSlice())}else{
		binary.BigEndian.PutUint16(p[2:4],uint16(len(p)));binary.BigEndian.PutUint16(p[4:6],77);copy(p[12:16],netip.MustParseAddr(source).AsSlice());copy(p[16:20],netip.MustParseAddr(destination).AsSlice());binary.BigEndian.PutUint16(p[10:12],dnsTestSum(p[:20]))
	}
	binary.BigEndian.PutUint16(p[h:h+2],sport);binary.BigEndian.PutUint16(p[h+2:h+4],dport)
	check:=h+6
	if proto==17 {binary.BigEndian.PutUint16(p[h+4:h+6],uint16(len(p)-h))}else{p[h+12]=0x50;p[h+13]=2;binary.BigEndian.PutUint32(p[h+4:h+8],123);check=h+16}
	copy(p[h+transport:],payload);value:=dnsTestTransportChecksum(p);if value==0&&proto==17 {value=65535};binary.BigEndian.PutUint16(p[check:check+2],value)
	return p
}
func dnsTestNat(t *testing.T)*dnsNat {
	t.Helper();n,err:=newDnsNat("10.77.0.2","fd77::2","10.77.0.2,fd77::2",40553);if err!=nil{t.Fatal(err)};n.clock=func()int64{return 0};t.Cleanup(n.close);return n
}
func TestManagedDnsNatDualStackKernelRoundTrip(t *testing.T){
	for _,v6:=range []bool{false,true}{for _,proto:=range []byte{6,17}{t.Run(fmt.Sprintf("v6=%t/proto=%d",v6,proto),func(t *testing.T){
		n:=dnsTestNat(t);source,alias:="10.77.0.2",dnsAlias4.String();if v6{source,alias="fd77::2",dnsAlias6.String()}
		original:=dnsTestPacket(v6,proto,source,alias,52100,53,[]byte("owned-query"));saved:=append([]byte(nil),original...)
		handled,packets:=n.reflect(original,0);if !handled||len(packets)!=1{t.Fatal("DNS was not reflected")};if !bytes.Equal(original,saved){t.Fatal("borrowed packet modified")}
		v,_:=dnsPacketView(packets[0]);translated:=binary.BigEndian.Uint16(packets[0][v.transport:v.transport+2])
		if v.src.String()!=alias||v.dst.String()!=source||binary.BigEndian.Uint16(packets[0][v.transport+2:v.transport+4])!=40553||dnsTestTransportChecksum(packets[0])!=0{t.Fatal("invalid kernel request tuple/checksum")}
		if !v6&&dnsTestSum(packets[0][:20])!=0{t.Fatal("IPv4 checksum")}
		client:=n.client(int32(proto),int32(translated));if client==nil||client.Source!=source||client.Destination!=alias||client.SourcePort!=52100{t.Fatal("missing original tuple")}
		response:=dnsTestPacket(v6,proto,source,alias,40553,translated,[]byte("owned-response"))
		handled,packets=n.reflect(response,1);if !handled||len(packets)!=1{t.Fatal("missing return reflection")}
		v,_=dnsPacketView(packets[0]);if v.src.String()!=alias||v.dst.String()!=source||binary.BigEndian.Uint16(packets[0][v.transport:v.transport+2])!=53||binary.BigEndian.Uint16(packets[0][v.transport+2:v.transport+4])!=52100||dnsTestTransportChecksum(packets[0])!=0{t.Fatal("invalid original response tuple/checksum")}
	})}}
}
func dnsTestFragments(p []byte,v6 bool)([]byte,[]byte){
	cut:=24
	if !v6{
		first:=append([]byte(nil),p[:20+cut]...);last:=append(append([]byte(nil),p[:20]...),p[20+cut:]...)
		for index,part:=range [][]byte{first,last}{binary.BigEndian.PutUint16(part[2:4],uint16(len(part)));flags:=uint16(0x2000);if index==1{flags=uint16(cut/8)};binary.BigEndian.PutUint16(part[6:8],flags);part[10]=0;part[11]=0;binary.BigEndian.PutUint16(part[10:12],dnsTestSum(part[:20]))};return first,last
	}
	makePart:=func(payload []byte,offset uint16)[]byte{part:=append([]byte(nil),p[:40]...);part[6]=44;fragment:=make([]byte,8);fragment[0]=p[6];binary.BigEndian.PutUint16(fragment[2:4],offset);binary.BigEndian.PutUint32(fragment[4:8],77);part=append(part,fragment...);part=append(part,payload...);binary.BigEndian.PutUint16(part[4:6],uint16(len(part)-40));return part}
	return makePart(p[40:40+cut],1),makePart(p[40+cut:],uint16(cut))
}
func TestManagedDnsNatOutOfOrderFragments(t *testing.T){
	for _,v6:=range []bool{false,true}{t.Run(fmt.Sprint(v6),func(t *testing.T){
		n:=dnsTestNat(t);src,alias:="10.77.0.2",dnsAlias4.String();if v6{src,alias="fd77::2",dnsAlias6.String()}
		original:=dnsTestPacket(v6,17,src,alias,52200,53,bytes.Repeat([]byte{0x51},64));first,last:=dnsTestFragments(original,v6)
		if handled,out:=n.reflect(last,0);!handled||len(out)!=0{t.Fatal("orphan fragment leaked")}
		_,out:=n.reflect(first,1);if len(out)!=2||n.pendingBytes!=0{t.Fatal("fragments not joined to bounded mapping")}
		h:=20;if v6{h=40};joined:=append([]byte(nil),original[:h]...);for _,part:=range out {v,_:=dnsPacketView(part);joined=append(joined,part[v.transport:]...)}
		v,_:=dnsPacketView(out[0]);copy(joined[map[bool]int{false:12,true:8}[v6]:],v.src.AsSlice());copy(joined[map[bool]int{false:16,true:24}[v6]:],v.dst.AsSlice())
		if dnsTestTransportChecksum(joined)!=0{t.Fatal("fragment pseudoheader checksum")}
	})}
}
func TestManagedDnsNatBoundsExpiryAndStop(t *testing.T){
	n:=dnsTestNat(t)
	for index:=0;index<1024;index++{_,out:=n.reflect(dnsTestPacket(false,17,"10.77.0.2",dnsAlias4.String(),uint16(1000+index),53,nil),0);if len(out)!=1{t.Fatal("unexpected mapping shortage")}}
	if _,out:=n.reflect(dnsTestPacket(false,17,"10.77.0.2",dnsAlias4.String(),9999,53,nil),0);len(out)!=0{t.Fatal("mapping bound exceeded")}
	n.clock=func()int64{return 30000};if n.client(17,32769)!=nil||len(n.byPort)!=0{t.Fatal("expired client survived")}
	if _,out:=n.reflect(dnsTestPacket(false,17,"10.77.0.2",dnsAlias4.String(),9999,53,nil),30000);len(out)!=1{t.Fatal("expiry did not free mappings")}
	n.close();if n.client(17,32769)!=nil{t.Fatal("tuple survived STOP")};if handled,out:=n.reflect(dnsTestPacket(false,17,"10.77.0.2",dnsAlias4.String(),9999,53,nil),30001);!handled||len(out)!=0{t.Fatal("closed alias escaped")}
}
func TestManagedDnsNatNewSynRetiresOldOriginalTupleLease(t *testing.T){
	n:=dnsTestNat(t);syn:=dnsTestPacket(false,6,"10.77.0.2",dnsAlias4.String(),52400,53,nil)
	_,first:=n.reflect(syn,0);if len(first)!=1{t.Fatal("initial SYN")};old:=binary.BigEndian.Uint16(first[0][20:22])
	_,retry:=n.reflect(syn,1);if len(retry)!=1||binary.BigEndian.Uint16(retry[0][20:22])!=old{t.Fatal("SYN retransmission rebound")}
	newSyn:=dnsTestPacket(false,6,"10.77.0.2",dnsAlias4.String(),52400,53,nil)
	binary.BigEndian.PutUint32(newSyn[24:28],456);newSyn[36]=0;newSyn[37]=0;binary.BigEndian.PutUint16(newSyn[36:38],dnsTestTransportChecksum(newSyn))
	_,next:=n.reflect(newSyn,2);if len(next)!=1||binary.BigEndian.Uint16(next[0][20:22])==old||n.client(6,int32(old))!=nil{t.Fatal("new SYN reused prior application lifetime")}
	response:=dnsTestPacket(false,6,"10.77.0.2",dnsAlias4.String(),40553,old,[]byte("late"))
	if _,out:=n.reflect(response,3);len(out)!=0{t.Fatal("retired original tuple produced reply")}
}
type dnsTestTun struct{tun.Device;incoming,outgoing [][]byte;closed bool}
func TestManagedDnsTunAcceptsBindBatchCapacity(t *testing.T){
	n:=dnsTestNat(t);alias:=dnsTestPacket(false,17,"10.77.0.2",dnsAlias4.String(),52310,53,nil)
	permitted:=dnsTestPacket(false,17,"10.77.0.2","198.18.0.2",52311,443,[]byte("after-batched-alias"))
	base:=&dnsTestTun{incoming:[][]byte{alias,permitted}};wrapped:=&managedDnsTun{Device:base,nat:n}
	bufs:=make([][]byte,128);sizes:=make([]int,128);for i:=range bufs{bufs[i]=make([]byte,4096)}
	if count,err:=wrapped.Read(bufs,sizes,16);err!=nil||count!=1||!bytes.Equal(bufs[0][16:16+sizes[0]],permitted)||len(base.outgoing)!=1{t.Fatal("bind batch capacity rejected, alias leaked or permitted packet changed")}
	for _,size:=range sizes[1:]{if size!=0{t.Fatal("unused batch slot consumed")}}
}
func(t *dnsTestTun)Read(bufs [][]byte,sizes []int,offset int)(int,error){if len(t.incoming)==0{return 0,io.EOF};p:=t.incoming[0];t.incoming=t.incoming[1:];copy(bufs[0][offset:],p);sizes[0]=len(p);return 1,nil}
func(t *dnsTestTun)Write(bufs [][]byte,offset int)(int,error){for _,p:=range bufs{t.outgoing=append(t.outgoing,append([]byte(nil),p[offset:]...))};return len(bufs),nil}
func(t *dnsTestTun)Close()error{t.closed=true;return nil}
func TestManagedDnsTunNoAliasPeerLeakAndPermittedAfter(t *testing.T){
	n:=dnsTestNat(t);alias:=dnsTestPacket(false,17,"10.77.0.2",dnsAlias4.String(),52300,53,nil);permitted:=dnsTestPacket(false,17,"10.77.0.2","198.18.0.2",52301,443,[]byte("permitted-after"))
	base:=&dnsTestTun{incoming:[][]byte{alias,permitted}};wrapped:=&managedDnsTun{Device:base,nat:n};buffer:=make([]byte,4096);sizes:=make([]int,1)
	if count,err:=wrapped.Read([][]byte{buffer},sizes,0);err!=nil||count!=1||!bytes.Equal(buffer[:sizes[0]],permitted)||len(base.outgoing)!=1{t.Fatal("alias entered peer or ordinary traffic changed")}
	base.outgoing=nil;spoof:=dnsTestPacket(false,17,dnsAlias4.String(),"10.77.0.2",53,52300,nil)
	if count,err:=wrapped.Write([][]byte{spoof,permitted},0);err!=nil||count!=2||len(base.outgoing)!=1||!bytes.Equal(base.outgoing[0],permitted){t.Fatal("peer spoof or permitted-after failure")}
	if err:=wrapped.Close();err!=nil||!base.closed||n.client(17,32769)!=nil{t.Fatal("owned duplicate not retired")}
}
