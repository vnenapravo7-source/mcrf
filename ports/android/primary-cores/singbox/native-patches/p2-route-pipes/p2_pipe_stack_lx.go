//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/sagernet/sing-box/transport/wireguard"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/sys/unix"
)

// The EXISTING external dispatcher's user-space stack supplies flow/packet
// adaptation only. WG/AWG encryption and endpoint sockets remain in the chosen
// compact external WG worker. No second Android TUN, native base APK, kernel
// target dial, or gVisor/full-core dependency inside the compact module.
type mcrfPipeStack struct {
	device wireguard.Device
	file *os.File
	raw syscall.RawConn
	spec mcrfPipeSpec
	ctx context.Context
	cancel context.CancelFunc
	closed atomic.Bool
	closeOnce sync.Once
	workers sync.WaitGroup
	closeErr error
}

func newMcrfPipeStack(ctx context.Context,borrowedFD int,spec mcrfPipeSpec)(*mcrfPipeStack,error){
	if ctx==nil||ctx.Err()!=nil||borrowedFD<0{return nil,errors.New("MCRF_PIPE_INPUT")}
	addresses:=make([]string,len(spec.addresses));for i,address:=range spec.addresses{addresses[i]=address.String()}
	checked,err:=parseMcrfPipeSpec(spec.mtu,spec.ownerPID,strings.Join(addresses,","))
	if err!=nil{return nil,err};spec=checked
	fd,err:=unix.FcntlInt(uintptr(borrowedFD),unix.F_DUPFD_CLOEXEC,0)
	if err!=nil{return nil,errors.New("MCRF_PIPE_DUP")}
	defer func(){if fd>=0{_ = unix.Close(fd)}}()
	address,err:=unix.Getsockname(fd);if err!=nil{return nil,errors.New("MCRF_PIPE_TYPE")}
	if _,ok:=address.(*unix.SockaddrUnix);!ok{return nil,errors.New("MCRF_PIPE_TYPE")}
	kind,err:=unix.GetsockoptInt(fd,unix.SOL_SOCKET,unix.SO_TYPE)
	if err!=nil||kind!=unix.SOCK_DGRAM{return nil,errors.New("MCRF_PIPE_TYPE")}
	peer,err:=unix.GetsockoptUcred(fd,unix.SOL_SOCKET,unix.SO_PEERCRED)
	if err!=nil||peer.Pid!=int32(spec.ownerPID)||peer.Uid!=uint32(os.Geteuid()){return nil,errors.New("MCRF_PIPE_OWNER")}
	flags,err:=unix.FcntlInt(uintptr(fd),unix.F_GETFL,0)
	if err!=nil||flags&unix.O_NONBLOCK==0{return nil,errors.New("MCRF_PIPE_BLOCKING")}
	file:=os.NewFile(uintptr(fd),"mcrf-private-route-pipe")
	if file==nil{return nil,errors.New("MCRF_PIPE_FILE")};fd=-1
	raw,err:=file.SyscallConn();if err!=nil{_ = file.Close();return nil,errors.New("MCRF_PIPE_POLL")}
	child,cancel:=context.WithCancel(ctx)
	device,err:=wireguard.NewDevice(wireguard.DeviceOptions{Context:child,MTU:uint32(spec.mtu),Address:spec.addresses})
	if err!=nil{cancel();_ = file.Close();return nil,errors.New("MCRF_PIPE_STACK")}
	if err=device.Start();err!=nil{cancel();_ = file.Close();_ = device.Close();return nil,errors.New("MCRF_PIPE_STACK_START")}
	client:=&mcrfPipeStack{device:device,file:file,raw:raw,spec:spec,ctx:child,cancel:cancel}
	client.workers.Add(3)
	go func(){defer client.workers.Done();client.fromPeer()}()
	go func(){defer client.workers.Done();client.toPeer()}()
	go func(){defer client.workers.Done();<-child.Done();client.beginClose()}()
	if child.Err()!=nil||client.closed.Load(){_ = client.Close();return nil,errors.New("MCRF_PIPE_RETIRED")}
	return client,nil // Private stack readiness, NOT WG handshake or site reachability.
}

func(p *mcrfPipeStack) beginClose(){
	p.closeOnce.Do(func(){
		p.closed.Store(true);p.cancel()
		fileErr:=p.file.Close();stackErr:=p.device.Close()
		if fileErr!=nil||stackErr!=nil{p.closeErr=errors.New("MCRF_PIPE_CLOSE_PENDING")}
	})
}
func(p *mcrfPipeStack) Close()error{
	p.beginClose();p.workers.Wait();return p.closeErr // Fresh worker death is still mandatory in main.
}
func(p *mcrfPipeStack) fromPeer(){
	defer p.beginClose()
	packet:=make([]byte,65536)
	for !p.closed.Load(){
		var n,flags int;var readErr error
		err:=p.raw.Read(func(fd uintptr)bool{
			for {n,_,flags,_,readErr=unix.Recvmsg(int(fd),packet,nil,unix.MSG_DONTWAIT|unix.MSG_TRUNC)
				if readErr==unix.EINTR{continue};return readErr!=unix.EAGAIN&&readErr!=unix.EWOULDBLOCK}
		})
		if err!=nil||readErr!=nil||flags&unix.MSG_TRUNC!=0||n<0||n>len(packet)||!validMcrfPipePacket(packet[:n],p.spec.mtu)||p.closed.Load(){return}
		// The stack may retain packet storage after Write returns. Transfer a
		// fresh immutable frame, never the next Recvmsg iteration's buffer.
		frame:=append([]byte(nil),packet[:n]...)
		if count,err:=p.device.Write([][]byte{frame},0);err!=nil||count!=1{return}
	}
}
func(p *mcrfPipeStack) toPeer(){
	defer p.beginClose()
	packet:=make([]byte,65536);sizes:=[]int{0}
	for !p.closed.Load(){
		count,err:=p.device.Read([][]byte{packet},sizes,0)
		if err!=nil||count!=1||sizes[0]<0||sizes[0]>len(packet)||!validMcrfPipePacket(packet[:sizes[0]],p.spec.mtu)||p.closed.Load(){return}
		var writeErr error
		err=p.raw.Write(func(fd uintptr)bool{
			for {writeErr=unix.Sendto(int(fd),packet[:sizes[0]],unix.MSG_DONTWAIT|unix.MSG_NOSIGNAL,nil)
				if writeErr==unix.EINTR{continue};return writeErr!=unix.EAGAIN&&writeErr!=unix.EWOULDBLOCK}
		})
		if err!=nil||writeErr!=nil{return}
	}
}
func(p *mcrfPipeStack) DialContext(ctx context.Context,network string,destination M.Socksaddr)(net.Conn,error){
	if ctx==nil||p.closed.Load()||!destination.Addr.IsValid()||destination.IsFqdn()||destination.Addr.Is4In6(){return nil,errors.New("MCRF_PIPE_NUMERIC_DESTINATION")}
	child,cancel:=context.WithCancel(ctx);stop:=context.AfterFunc(p.ctx,cancel);defer stop();defer cancel()
	connection,err:=p.device.DialContext(child,network,destination)
	if err!=nil{return nil,errors.New("MCRF_PIPE_CONNECT")}
	if p.closed.Load(){_ = connection.Close();return nil,errors.New("MCRF_PIPE_RETIRED")}
	return connection,nil
}
func(p *mcrfPipeStack) ListenPacket(ctx context.Context,destination M.Socksaddr)(net.PacketConn,error){
	if ctx==nil||p.closed.Load()||!destination.Addr.IsValid()||destination.IsFqdn()||destination.Addr.Is4In6(){return nil,errors.New("MCRF_PIPE_NUMERIC_DESTINATION")}
	connection,err:=p.device.ListenPacket(ctx,destination)
	if err!=nil{return nil,errors.New("MCRF_PIPE_PACKET_CONNECT")}
	if p.closed.Load(){_ = connection.Close();return nil,errors.New("MCRF_PIPE_RETIRED")}
	return connection,nil
}
