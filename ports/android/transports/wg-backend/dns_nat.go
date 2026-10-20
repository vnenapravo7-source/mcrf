package wgbackend

import (
	"encoding/binary"
	"errors"
	"net/netip"
	"sync"
	"time"
)

// No user-space TCP implementation, resolver, public socket or new upstream.
// Only local kernel packets addressed to the reserved DNS aliases are reflected
// to a same-family, worker-owned high-port listener and back. Actual owner and
// DNS transport policy remain mandatory worker-side checks, not NAT authority.
var dnsAlias4 = netip.MustParseAddr("198.19.255.53")
var dnsAlias6 = netip.MustParseAddr("fdfe:dcba:9876:dd54::53")

type dnsKey struct { src netip.Addr; port uint16; proto byte }
type dnsMapping struct {
	key dnsKey
	alias, local netip.Addr
	translated uint16
	expires int64
	syn uint32
}
type fragmentKey struct { src, dst netip.Addr; id uint32; proto byte }
type fragmentRewrite struct { src, dst netip.Addr; expires int64 }
type dnsNat struct {
	mu sync.Mutex
	closed bool
	local4, local6 netip.Addr
	locals map[netip.Addr]bool
	port uint16
	next uint16
	byClient map[dnsKey]*dnsMapping
	byPort map[uint32]*dnsMapping
	fragments map[fragmentKey]fragmentRewrite
	pending map[fragmentKey][][]byte
	pendingUntil map[fragmentKey]int64
	pendingBytes int
	clock func()int64
}

func newDnsNat(address4,address6,sources string, port int) (*dnsNat,error) {
	if port<1024||port>65535||len(sources)==0||len(sources)>1024 {return nil,errors.New("WG_DNS_CONFIG")}
	n:=&dnsNat{port:uint16(port),next:32768,locals:make(map[netip.Addr]bool),byClient:make(map[dnsKey]*dnsMapping),byPort:make(map[uint32]*dnsMapping),fragments:make(map[fragmentKey]fragmentRewrite),pending:make(map[fragmentKey][][]byte),pendingUntil:make(map[fragmentKey]int64)}
	created:=time.Now();n.clock=func()int64{return time.Since(created).Milliseconds()}
	parse:=func(text string, bits int)(netip.Addr,error){
		if text=="" {return netip.Addr{},nil};ip,err:=netip.ParseAddr(text)
		if err!=nil||ip.BitLen()!=bits||ip.Is4In6()||ip.Zone()!=""||ip.IsUnspecified()||ip.IsMulticast()||ip.IsLoopback()||ip==dnsAlias4||ip==dnsAlias6 {return netip.Addr{},errors.New("WG_DNS_ADDRESS")};return ip,nil
	}
	var err error
	if n.local4,err=parse(address4,32);err!=nil{return nil,err};if n.local6,err=parse(address6,128);err!=nil{return nil,err}
	if !n.local4.IsValid()&&!n.local6.IsValid(){return nil,errors.New("WG_DNS_ADDRESS")}
	start:=0
	for at:=0;at<=len(sources);at++ {if at!=len(sources)&&sources[at]!=',' {continue};text:=sources[start:at];start=at+1
		ip,e:=netip.ParseAddr(text);if e!=nil||ip.Zone()!=""||ip.Is4In6()||ip.IsUnspecified()||ip.IsMulticast()||ip.IsLoopback()||ip==dnsAlias4||ip==dnsAlias6||len(n.locals)>=16{return nil,errors.New("WG_DNS_SOURCES")};n.locals[ip]=true
	}
	if n.local4.IsValid()&&!n.locals[n.local4]||n.local6.IsValid()&&!n.locals[n.local6] {return nil,errors.New("WG_DNS_LISTENER_SOURCE")}
	return n,nil
}

type dnsPacket struct {
	src,dst netip.Addr
	srcAt,dstAt,size,transport int
	proto byte
	fragment bool
	first bool
	id uint32
}

func dnsPacketView(p []byte)(dnsPacket,bool) {
	v:=dnsPacket{first:true}
	if len(p)<20{return v,false}
	switch p[0]>>4 {
	case 4:
		h:=int(p[0]&15)*4;size:=int(binary.BigEndian.Uint16(p[2:4]));if h<20||h>60||size!=len(p)||size<h{return v,false}
		v.src=netip.AddrFrom4([4]byte(p[12:16]));v.dst=netip.AddrFrom4([4]byte(p[16:20]));v.srcAt=12;v.dstAt=16;v.size=4;v.transport=h;v.proto=p[9]
		f:=binary.BigEndian.Uint16(p[6:8]);v.fragment=f&0x3fff!=0;v.first=f&0x1fff==0;v.id=uint32(binary.BigEndian.Uint16(p[4:6]))
	case 6:
		if len(p)<40||int(binary.BigEndian.Uint16(p[4:6]))+40!=len(p){return v,false}
		v.src=netip.AddrFrom16([16]byte(p[8:24]));v.dst=netip.AddrFrom16([16]byte(p[24:40]));v.srcAt=8;v.dstAt=24;v.size=16;v.transport=40;v.proto=p[6]
		for count:=0;v.proto!=6&&v.proto!=17;count++ {
			if count>=8||v.transport+2>len(p){return v,false}
			switch v.proto {
			case 0,43,60:
				if v.proto==43&&(v.transport+4>len(p)||p[v.transport+3]!=0){return v,false}
				length:=(int(p[v.transport+1])+1)*8;if v.transport+length>len(p){return v,false};v.proto=p[v.transport];v.transport+=length
			case 44:
				if v.fragment||v.transport+8>len(p){return v,false};v.fragment=true;v.first=binary.BigEndian.Uint16(p[v.transport+2:v.transport+4])&0xfff8==0;v.id=binary.BigEndian.Uint32(p[v.transport+4:v.transport+8]);v.proto=p[v.transport];v.transport+=8
				// A fragment key must identify the SAME transport in every part.
				// Extension chains after Fragment are not reflected or forwarded.
				if v.proto!=6&&v.proto!=17{return v,false}
				if !v.first{return v,true}
			default:return v,false
			}
		}
	default:return v,false
	}
	return v,true
}

func internalDns(p []byte) bool {
	if len(p)>=20&&p[0]>>4==4 {return netip.AddrFrom4([4]byte(p[12:16]))==dnsAlias4||netip.AddrFrom4([4]byte(p[16:20]))==dnsAlias4}
	if len(p)>=40&&p[0]>>4==6 {return netip.AddrFrom16([16]byte(p[8:24]))==dnsAlias6||netip.AddrFrom16([16]byte(p[24:40]))==dnsAlias6}
	return false
}

func checksumDelta(old uint16,before,after []byte)uint16 {
	sum:=uint32(^old)
	for at:=0;at<len(before);at+=2 {sum+=uint32(^binary.BigEndian.Uint16(before[at:at+2]))+uint32(binary.BigEndian.Uint16(after[at:at+2]))}
	for sum>>16!=0 {sum=(sum&65535)+(sum>>16)};return ^uint16(sum)
}

func rewriteDns(p []byte,v dnsPacket,src,dst netip.Addr,sourcePort,destinationPort uint16) ([]byte,bool) {
	out:=append([]byte(nil),p...)
	before:=append(append([]byte(nil),p[v.srcAt:v.srcAt+v.size]...),p[v.dstAt:v.dstAt+v.size]...)
	after:=append(append([]byte(nil),src.AsSlice()...),dst.AsSlice()...)
	if v.first {
		minimum,check:=8,6;if v.proto==6 {minimum=20;check=16}
		if v.transport+minimum>len(p){return nil,false}
		before=append(before,p[v.transport:v.transport+4]...);after=binary.BigEndian.AppendUint16(after,sourcePort);after=binary.BigEndian.AppendUint16(after,destinationPort)
		old:=binary.BigEndian.Uint16(p[v.transport+check:v.transport+check+2])
		if v.proto==17&&old==0&&v.size==16{return nil,false}
		if v.proto!=17||old!=0 {value:=checksumDelta(old,before,after);if v.proto==17&&value==0{value=65535};binary.BigEndian.PutUint16(out[v.transport+check:v.transport+check+2],value)}
		binary.BigEndian.PutUint16(out[v.transport:v.transport+2],sourcePort);binary.BigEndian.PutUint16(out[v.transport+2:v.transport+4],destinationPort)
	}
	copy(out[v.srcAt:v.srcAt+v.size],src.AsSlice());copy(out[v.dstAt:v.dstAt+v.size],dst.AsSlice())
	if v.size==4 {out[10]=0;out[11]=0;var sum uint32;for at:=0;at<v.transport;at+=2{sum+=uint32(binary.BigEndian.Uint16(out[at:at+2]))};for sum>>16!=0{sum=(sum&65535)+(sum>>16)};binary.BigEndian.PutUint16(out[10:12],^uint16(sum))}
	return out,true
}

func portKey(proto byte,port uint16)uint32{return uint32(proto)<<16|uint32(port)}
func (n *dnsNat) reap(now int64) {
	for key,m:=range n.byPort {if m.expires<=now {delete(n.byPort,key);if n.byClient[m.key]==m{delete(n.byClient,m.key)}}}
	for key,m:=range n.fragments {if m.expires<=now{delete(n.fragments,key)}}
	for key,until:=range n.pendingUntil {if until<=now {for _,p:=range n.pending[key]{n.pendingBytes-=len(p)};delete(n.pending,key);delete(n.pendingUntil,key)}}
}

// handled=false means EXACT packet pass-through. handled=true never enters WG:
// only returned reflections may be written to the local owned TUN duplicate.
func (n *dnsNat) reflect(p []byte,now int64)(handled bool,packets [][]byte) {
	if !internalDns(p){return false,nil}
	n.mu.Lock();defer n.mu.Unlock();if n.closed{return true,nil};n.reap(now)
	v,ok:=dnsPacketView(p);if !ok||v.proto!=6&&v.proto!=17{return true,nil}
	alias,local:=dnsAlias4,n.local4;if v.size==16{alias,local=dnsAlias6,n.local6}
	if !local.IsValid()||v.dst!=alias||!n.locals[v.src]{return true,nil}
	fkey:=fragmentKey{v.src,v.dst,v.id,v.proto}
	if !v.first {
		if rule,exists:=n.fragments[fkey];exists {if out,valid:=rewriteDns(p,v,rule.src,rule.dst,0,0);valid{return true,[][]byte{out}};return true,nil}
		if len(n.pendingUntil)>=128||n.pendingBytes+len(p)>524288||len(n.pending[fkey])>=32{return true,nil}
		n.pending[fkey]=append(n.pending[fkey],append([]byte(nil),p...));n.pendingUntil[fkey]=now+2000;n.pendingBytes+=len(p);return true,nil
	}
	if v.transport+8>len(p){return true,nil}
	sourcePort:=binary.BigEndian.Uint16(p[v.transport:v.transport+2]);destinationPort:=binary.BigEndian.Uint16(p[v.transport+2:v.transport+4])
	var m *dnsMapping;var src,dst netip.Addr;var newSource,newDestination uint16
	if v.src==local&&sourcePort==n.port {
		m=n.byPort[portKey(v.proto,destinationPort)];if m==nil||m.alias!=alias||n.byClient[m.key]!=m{return true,nil}
		src,dst,newSource,newDestination=m.alias,m.key.src,53,m.key.port
	}else{
		if destinationPort!=53||sourcePort==0{return true,nil}
		key:=dnsKey{v.src,sourcePort,v.proto};m=n.byClient[key]
		var syn uint32;newSyn:=false
		if v.proto==6 {if v.transport+20>len(p){return true,nil};newSyn=p[v.transport+13]&0x12==2;syn=binary.BigEndian.Uint32(p[v.transport+4:v.transport+8])}
		if m==nil||newSyn&&m.syn!=syn {
			if v.proto==6&&!newSyn||len(n.byPort)>=1024{return true,nil}
			m=nil
			for tries:=0;tries<65536;tries++ {n.next++;if n.next<1024{n.next=1024};if n.next!=n.port&&n.byPort[portKey(v.proto,n.next)]==nil{m=&dnsMapping{key:key,alias:alias,local:local,translated:n.next,syn:syn};break}}
			if m==nil{return true,nil};n.byClient[key]=m;n.byPort[portKey(v.proto,m.translated)]=m
		}
		src,dst,newSource,newDestination=alias,local,m.translated,n.port
	}
	m.expires=now+30000;if v.proto==6{m.expires=now+120000}
	out,valid:=rewriteDns(p,v,src,dst,newSource,newDestination);if !valid{return true,nil};packets=append(packets,out)
	if v.fragment {
		if len(n.fragments)>=256 {return true,nil};n.fragments[fkey]=fragmentRewrite{src,dst,now+10000}
		for _,pending:=range n.pending[fkey] {n.pendingBytes-=len(pending);if part,valid:=dnsPacketView(pending);valid {if reflected,valid:=rewriteDns(pending,part,src,dst,0,0);valid{packets=append(packets,reflected)}}}
		delete(n.pending,fkey);delete(n.pendingUntil,fkey)
	}
	return true,packets
}

// gomobile-safe immutable observation. It is NOT proof of a live application;
// the worker checks the original kernel tuple on each use, including replies.
type DnsClient struct { Source string; SourcePort int32; Destination string; Protocol int32 }
func (n *dnsNat) client(proto,port int32)*DnsClient {
	n.mu.Lock();defer n.mu.Unlock();if n.closed||proto!=6&&proto!=17||port<1||port>65535{return nil};n.reap(n.clock())
	m:=n.byPort[portKey(byte(proto),uint16(port))];if m==nil||n.byClient[m.key]!=m{return nil};return &DnsClient{m.key.src.String(),int32(m.key.port),m.alias.String(),int32(m.key.proto)}
}
func (n *dnsNat) close(){n.mu.Lock();defer n.mu.Unlock();n.closed=true;n.byClient=nil;n.byPort=nil;n.fragments=nil;n.pending=nil;n.pendingUntil=nil;n.pendingBytes=0}
