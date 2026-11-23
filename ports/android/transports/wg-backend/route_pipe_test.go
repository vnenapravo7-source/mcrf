//go:build linux || android

package wgbackend

import (
	"bytes"
	"fmt"
	"os"
	"syscall"
	"testing"
	"time"
	"golang.org/x/sys/unix"
)

// Private channel cases plus an actual WG/AWG encrypted loopback fixture.
// Neither proves Android original-owner routing or a second-server dispatcher.
type observedPipeRaw struct{syscall.RawConn;waiting chan<-struct{}}
func(o *observedPipeRaw) observe(callback func(uintptr)bool)func(uintptr)bool{
	return func(fd uintptr)bool{ready:=callback(fd);if !ready{select{case o.waiting<-struct{}{}:default:}};return ready}
}
func(o *observedPipeRaw) Read(callback func(uintptr)bool)error{return o.RawConn.Read(o.observe(callback))}
func(o *observedPipeRaw) Write(callback func(uintptr)bool)error{return o.RawConn.Write(o.observe(callback))}
func waitPipeBlocked(t *testing.T,waiting <-chan struct{}){
	t.Helper();select{case <-waiting:case <-time.After(time.Second):t.Fatal("no actual EAGAIN before close")}
}
func pipePair(t *testing.T,kind int)[2]int{
	t.Helper();pair,err:=unix.Socketpair(unix.AF_UNIX,kind|unix.SOCK_CLOEXEC|unix.SOCK_NONBLOCK,0)
	if err!=nil{t.Fatal(err)}
	t.Cleanup(func(){_ = unix.Close(pair[0]);_ = unix.Close(pair[1])});return pair
}
func TestPacketPipeOwnershipAndBothIpFamilies(t *testing.T){
	pair:=pipePair(t,unix.SOCK_DGRAM)
	pipe,err:=openPacketPipe(pair[1],1280,os.Getpid());if err!=nil{t.Fatal(err)}
	defer pipe.Close()
	for _,version:=range []byte{4,6}{
		packet:=make([]byte,60);packet[0]=version<<4
		if version==4{packet[0]|=5;packet[3]=60}else{packet[5]=20}
		if err:=unix.Sendto(pair[0],packet,0,nil);err!=nil{t.Fatal(err)}
		buffer:=make([]byte,1600);sizes:=[]int{0}
		if n,err:=pipe.Read([][]byte{buffer},sizes,7);err!=nil||n!=1||sizes[0]!=60||!bytes.Equal(buffer[7:67],packet){t.Fatalf("packet read: %d %v",n,err)}
		if n,err:=pipe.Write([][]byte{buffer[:67]},7);err!=nil||n!=1{t.Fatalf("packet write: %d %v",n,err)}
		out:=make([]byte,1600);n,_,err:=unix.Recvfrom(pair[0],out,0);if err!=nil||!bytes.Equal(out[:n],packet){t.Fatal("whole reverse packet")}
	}
	if err:=pipe.Close();err!=nil{t.Fatal(err)}
	if _,err:=unix.FcntlInt(uintptr(pair[1]),unix.F_GETFD,0);err!=nil{t.Fatal("borrowed descriptor was closed")}
}
func TestPacketPipeRefusesOtherOwnerTypeAndSharedBlockingFlag(t *testing.T){
	pair:=pipePair(t,unix.SOCK_DGRAM)
	if p,err:=openPacketPipe(pair[1],1280,os.Getpid()+1);err==nil{p.Close();t.Fatal("wrong PID accepted")}
	stream:=pipePair(t,unix.SOCK_STREAM)
	if p,err:=openPacketPipe(stream[1],1280,os.Getpid());err==nil{p.Close();t.Fatal("stream accepted as packet channel")}
	if err:=unix.SetNonblock(pair[1],false);err!=nil{t.Fatal(err)}
	if p,err:=openPacketPipe(pair[1],1280,os.Getpid());err==nil{p.Close();t.Fatal("shared blocking flag silently changed")}
	flags,err:=unix.FcntlInt(uintptr(pair[1]),unix.F_GETFL,0);if err!=nil||flags&unix.O_NONBLOCK!=0{t.Fatal("borrowed flags changed")}
}
func TestPacketPipeNeverAdmitsTruncatedOrNonIpDatagram(t *testing.T){
	for _,packet:=range [][]byte{{}, {0x10}, bytes.Repeat([]byte{0x45},1500)}{
		pair:=pipePair(t,unix.SOCK_DGRAM);pipe,err:=openPacketPipe(pair[1],1280,os.Getpid());if err!=nil{t.Fatal(err)}
		if err:=unix.Sendto(pair[0],packet,0,nil);err!=nil{t.Fatal(err)}
		if n,err:=pipe.Read([][]byte{make([]byte,1280)},[]int{0},0);err==nil||n!=0{t.Fatal("invalid packet accepted")}
		pipe.Close()
	}
}
func TestPacketPipeCloseUnblocksActualPendingRead(t *testing.T){
	pair:=pipePair(t,unix.SOCK_DGRAM);pipe,err:=openPacketPipe(pair[1],1280,os.Getpid());if err!=nil{t.Fatal(err)}
	defer pipe.Close();waiting:=make(chan struct{},1);pipe.raw=&observedPipeRaw{RawConn:pipe.raw,waiting:waiting}
	done:=make(chan error,1)
	go func(){_,err:=pipe.Read([][]byte{make([]byte,1280)},[]int{0},0);done<-err}()
	waitPipeBlocked(t,waiting)
	if err:=pipe.Close();err!=nil{t.Fatal(err)}
	select{case err:=<-done:if err==nil{t.Fatal("closed read succeeded")};case <-time.After(time.Second):t.Fatal("actual pending read did not retire")}
}
func TestPacketPipeCloseUnblocksActualBackpressuredWrite(t *testing.T){
	pair:=pipePair(t,unix.SOCK_DGRAM);pipe,err:=openPacketPipe(pair[1],1280,os.Getpid());if err!=nil{t.Fatal(err)}
	defer pipe.Close()
	if err:=unix.SetsockoptInt(pair[1],unix.SOL_SOCKET,unix.SO_SNDBUF,1024);err!=nil{t.Fatal(err)}
	packet:=make([]byte,60);packet[0]=0x45;packet[3]=60
	full:=false
	for i:=0;i<4096;i++{err:=unix.Sendto(pair[1],packet,unix.MSG_DONTWAIT,nil);if err==unix.EAGAIN||err==unix.EWOULDBLOCK{full=true;break};if err!=nil{t.Fatal(err)}}
	if !full{t.Fatal("fixture queue did not become bounded/full")}
	waiting:=make(chan struct{},1);pipe.raw=&observedPipeRaw{RawConn:pipe.raw,waiting:waiting};done:=make(chan error,1)
	go func(){_,err:=pipe.Write([][]byte{packet},0);done<-err}()
	waitPipeBlocked(t,waiting)
	if err:=pipe.Close();err!=nil{t.Fatal(err)}
	select{case err:=<-done:if err==nil{t.Fatal("closed write succeeded")};case <-time.After(time.Second):t.Fatal("actual pending write did not retire")}
}

func TestPacketPipeEncryptedDualStack(t *testing.T){
	base:="jc=0\njmin=40\njmax=80\ns1=16\ns2=20\ns3=16\ns4=16\nh1=123456-123460\nh2=234567-234570\nh3=345678-345680\nh4=456789-456790\ni1=<r 8>\n"
	header:="header_protection_key=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20\ncontent_padding_addition=0-8\n"
	for _,variant:=range []struct{name,options string}{
		{"WG",""},{"AWG3",base+header},
		{"AWG3_1",base+header+"random_trailers=true\ndisable_cookies=true\nrekey_after_time=120\nrekey_timeout=5\nreject_after_time=180\nkeepalive_timeout=10\nmax_handshake_attempts=5\n"},
	}{t.Run(variant.name,func(t *testing.T){
		pair:=pipePair(t,unix.SOCK_DGRAM)
		host,err:=openPacketPipe(pair[0],1280,os.Getpid());if err!=nil{t.Fatal(err)}
		defer host.Close()
		privA,pubA:=privateKey(t);privB,pubB:=privateKey(t)
		pa,pb:=&preparation{allow:true},&preparation{allow:true}
		a,err:=startPacketPipe(pair[1],1280,"private_key="+privA+"\nlisten_port=0\n"+variant.options+"replace_peers=true\npublic_key="+pubB+"\nallowed_ip=10.77.0.2/32\nallowed_ip=fd77::2/128\n",pa,os.Getpid())
		if err!=nil{t.Fatal(err)};defer a.Close()
		peer:=newPacketTun();bind,err:=protectedBind(pb);if err!=nil{peer.Close();t.Fatal(err)}
		b,err:=startDevice(peer,bind,fmt.Sprintf("private_key=%s\nlisten_port=0\n%sreplace_peers=true\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=10.77.0.1/32\nallowed_ip=fd77::1/128\n",privB,variant.options,pubA,listenPort(t,a)))
		if err!=nil{t.Fatal(err)};defer b.Close()
		if a.core.IpcSet(fmt.Sprintf("public_key=%s\nendpoint=127.0.0.1:%d\n",pubB,listenPort(t,b)))!=nil{t.Fatal("private fixture endpoint update failed")}
		for family,request:=range [][]byte{packet(1,2),packet6(1,2)}{
			if err:=host.file.SetWriteDeadline(time.Now().Add(5*time.Second));err!=nil{t.Fatal(err)}
			if n,err:=host.Write([][]byte{request},0);err!=nil||n!=1{t.Fatalf("private encrypted request: %d %v",n,err)}
			select{case received:=<-peer.output:if !bytes.Equal(received,request){t.Fatal("decrypted private request changed")};case <-time.After(5*time.Second):t.Fatal("encrypted private request did not reach owned peer")}
			reply:=packet(2,1);if family==1{reply=packet6(2,1)}
			select{case peer.input<-reply:case <-time.After(5*time.Second):t.Fatal("owned peer input blocked")}
			if err:=host.file.SetReadDeadline(time.Now().Add(5*time.Second));err!=nil{t.Fatal(err)}
			buffer:=make([]byte,1280);sizes:=[]int{0}
			if n,err:=host.Read([][]byte{buffer},sizes,0);err!=nil||n!=1||!bytes.Equal(buffer[:sizes[0]],reply){t.Fatalf("decrypted private reply: %d %v",n,err)}
		}
		a.Close();b.Close()
		if pa.calls.Load()==0||pb.calls.Load()==0{t.Fatal("native encrypted endpoint bypassed protector")}
		if _,err:=unix.FcntlInt(uintptr(pair[1]),unix.F_GETFD,0);err!=nil{t.Fatal("session closed caller-owned descriptor")}
	})}
}
