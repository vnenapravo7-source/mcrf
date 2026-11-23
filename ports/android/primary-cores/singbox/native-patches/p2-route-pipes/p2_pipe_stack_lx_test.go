//go:build with_gvisor && (linux || android)

package libbox

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"os"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/sys/unix"
)

// REAL dispatcher stack + private packet IO only; fake peer, no WG crypto,
// Android TUN, server, default DNS or public target. Do not label it WG proof.
func mcrfStackPair(t *testing.T)[2]int{
	t.Helper();fds,err:=unix.Socketpair(unix.AF_UNIX,unix.SOCK_DGRAM|unix.SOCK_NONBLOCK|unix.SOCK_CLOEXEC,0)
	if err!=nil{t.Fatal(err)};t.Cleanup(func(){_ = unix.Close(fds[0]);_ = unix.Close(fds[1])});return fds
}
func mcrfOwnedStack(t *testing.T,fds [2]int)*mcrfPipeStack{
	t.Helper();spec,err:=parseMcrfPipeSpec(1280,os.Getpid(),"10.89.0.2/32,fd89::2/128");if err!=nil{t.Fatal(err)}
	client,err:=newMcrfPipeStack(context.Background(),fds[0],spec);if err!=nil{t.Fatal(err)}
	t.Cleanup(func(){if err:=client.Close();err!=nil{t.Error(err)}});return client
}
func mcrfPacket(t *testing.T,fd int)[]byte{
	t.Helper();poll:=[]unix.PollFd{{Fd:int32(fd),Events:unix.POLLIN}}
	if n,err:=unix.Poll(poll,1500);err!=nil||n!=1||poll[0].Revents&unix.POLLIN==0{t.Fatal("no private packet before deadline")}
	packet:=make([]byte,65536);n,_,flags,_,err:=unix.Recvmsg(fd,packet,nil,unix.MSG_TRUNC)
	if err!=nil||flags&unix.MSG_TRUNC!=0||n<0||n>len(packet)||!validMcrfPipePacket(packet[:n],1280){t.Fatal("invalid complete private packet")}
	return packet[:n]
}
func mcrfChecksum(packet []byte)uint16{
	var sum uint32
	for len(packet)>=2{sum+=uint32(binary.BigEndian.Uint16(packet));packet=packet[2:]}
	if len(packet)==1{sum+=uint32(packet[0])<<8}
	for sum>>16!=0{sum=(sum&0xffff)+(sum>>16)};return ^uint16(sum)
}
func mcrfUdpReply(t *testing.T,packet []byte)[]byte{
	t.Helper();reply:=append([]byte(nil),packet...);var offset int;var pseudo []byte
	if reply[0]>>4==4{
		offset=int(reply[0]&15)*4;if reply[9]!=17||offset<20||offset>len(reply)-8{t.Fatal("fixture expected UDP4")}
		copy(reply[12:16],packet[16:20]);copy(reply[16:20],packet[12:16]);reply[10]=0;reply[11]=0
		binary.BigEndian.PutUint16(reply[10:12],mcrfChecksum(reply[:offset]))
		pseudo=append(pseudo,reply[12:20]...);pseudo=append(pseudo,0,17,byte((len(reply)-offset)>>8),byte(len(reply)-offset))
	}else{
		offset=40;if reply[6]!=17||len(reply)<48{t.Fatal("fixture expected UDP6 without extension headers")}
		copy(reply[8:24],packet[24:40]);copy(reply[24:40],packet[8:24]);pseudo=append(pseudo,reply[8:40]...)
		size:=uint32(len(reply)-40);pseudo=append(pseudo,byte(size>>24),byte(size>>16),byte(size>>8),byte(size),0,0,0,17)
	}
	copy(reply[offset:offset+2],packet[offset+2:offset+4]);copy(reply[offset+2:offset+4],packet[offset:offset+2])
	reply[offset+6]=0;reply[offset+7]=0
	check:=mcrfChecksum(append(pseudo,reply[offset:]...));if check==0{check=0xffff};binary.BigEndian.PutUint16(reply[offset+6:offset+8],check)
	return reply
}
func TestMcrfPipeStackUdpPrivateRoundtripBothFamilies(t *testing.T){
	for _,text:=range []string{"198.18.0.2","fd89::99"}{t.Run(text,func(t *testing.T){
		fds:=mcrfStackPair(t);client:=mcrfOwnedStack(t,fds)
		remote:=netip.AddrPortFrom(netip.MustParseAddr(text),5353);destination:=M.Socksaddr{Addr:remote.Addr(),Port:remote.Port()}
		connection,err:=client.ListenPacket(context.Background(),destination);if err!=nil{t.Fatal(err)};defer connection.Close()
		if err:=connection.SetDeadline(time.Now().Add(2*time.Second));err!=nil{t.Fatal(err)}
		payload:=[]byte("owned-private-udp")
		if n,err:=connection.WriteTo(payload,net.UDPAddrFromAddrPort(remote));err!=nil||n!=len(payload){t.Fatal("stack UDP write failed")}
		packet:=mcrfPacket(t,fds[1]);if !bytes.HasSuffix(packet,payload){t.Fatal("private packet payload mismatch")}
		if err:=unix.Sendto(fds[1],mcrfUdpReply(t,packet),unix.MSG_DONTWAIT,nil);err!=nil{t.Fatal(err)}
		out:=make([]byte,256);n,_,err:=connection.ReadFrom(out);if err!=nil||!bytes.Equal(out[:n],payload){t.Fatal("stack UDP reply failed")}
		if err:=client.Close();err!=nil{t.Fatal(err)}
		if _,err:=unix.FcntlInt(uintptr(fds[0]),unix.F_GETFD,0);err!=nil{t.Fatal("borrowed packet fd was closed")}
	})}
}
func TestMcrfPipeStackCloseRetiresActualPendingTcpDial(t *testing.T){
	fds:=mcrfStackPair(t);client:=mcrfOwnedStack(t,fds);done:=make(chan error,1)
	go func(){connection,err:=client.DialContext(context.Background(),"tcp",M.Socksaddr{Addr:netip.MustParseAddr("198.18.0.2"),Port:443});if connection!=nil{_ = connection.Close()};done<-err}()
	_ = mcrfPacket(t,fds[1]) // Actual emitted SYN before stop, not a timing guess.
	if err:=client.Close();err!=nil{t.Fatal(err)}
	select{case err:=<-done:if err==nil{t.Fatal("retired fake peer connected")};case <-time.After(2*time.Second):t.Fatal("pending dial did not retire")}
}
func TestMcrfPipeStackRefusesImplicitResolverAndWrongPeerOwner(t *testing.T){
	fds:=mcrfStackPair(t);spec,err:=parseMcrfPipeSpec(1280,os.Getpid()+1,"10.89.0.2/32");if err!=nil{t.Fatal(err)}
	if client,err:=newMcrfPipeStack(context.Background(),fds[0],spec);err==nil{client.Close();t.Fatal("wrong kernel peer accepted")}
	client:=mcrfOwnedStack(t,fds)
	if connection,err:=client.DialContext(context.Background(),"tcp",M.Socksaddr{Fqdn:"never-resolve.example.test",Port:443});err==nil{connection.Close();t.Fatal("implicit hostname lookup admitted")}
}
