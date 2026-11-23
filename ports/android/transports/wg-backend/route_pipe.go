//go:build linux || android

package wgbackend

import (
	"errors"
	"os"
	"sync"
	"syscall"

	"github.com/sagernet/wireguard-go/tun"
	"golang.org/x/sys/unix"
)

// INTERNAL packet backend foundation, not a second Android TUN or a TCP/IP
// socket stack. The sole host dispatcher owns original UID/rules, destination
// DNS, stable flow pinning and address translation for each chosen server.
// This channel alone does not implement or advertise that dispatcher.
type packetPipe struct {
	file *os.File
	raw syscall.RawConn
	mtu int
	events chan tun.Event
	once sync.Once
	closeErr error
}

// Shared by the Android export and the Linux encrypted-channel fixture. Only
// the private packet boundary changes; encryption/protected UDP remain native.
func startPacketPipe(borrowed,mtu int,configuration string,protector Protector,peerPID int)(*Session,error){
	if protector==nil{return nil,errors.New("WG_PROTECTOR_REQUIRED")}
	pipe,err:=openPacketPipe(borrowed,mtu,peerPID);if err!=nil{return nil,err}
	bind,err:=protectedBind(protector);if err!=nil{_ = pipe.Close();return nil,err}
	return startDevice(pipe,bind,configuration)
}

// Duplicates immediately, owns ONLY its duplicate on every path. The caller
// must retain the exact borrowed descriptor throughout this synchronous call.
// Never change its shared O_NONBLOCK flag and never issue TUN ioctls here.
func openPacketPipe(borrowed, mtu, peerPID int) (*packetPipe,error) {
	if borrowed<0||mtu<1280||mtu>9000||peerPID<=0{return nil,errors.New("WG_PIPE_INPUT")}
	fd,err:=unix.FcntlInt(uintptr(borrowed),unix.F_DUPFD_CLOEXEC,0)
	if err!=nil{return nil,errors.New("WG_PIPE_DUP")}
	defer func(){if fd>=0{_ = unix.Close(fd)}}()
	address,err:=unix.Getsockname(fd)
	if err!=nil{return nil,errors.New("WG_PIPE_TYPE")}
	if _,ok:=address.(*unix.SockaddrUnix);!ok{return nil,errors.New("WG_PIPE_TYPE")}
	typeID,err:=unix.GetsockoptInt(fd,unix.SOL_SOCKET,unix.SO_TYPE)
	if err!=nil||typeID!=unix.SOCK_DGRAM{return nil,errors.New("WG_PIPE_TYPE")}
	peer,err:=unix.GetsockoptUcred(fd,unix.SOL_SOCKET,unix.SO_PEERCRED)
	if err!=nil||peer.Pid!=int32(peerPID)||peer.Uid!=uint32(os.Geteuid()){return nil,errors.New("WG_PIPE_OWNER")}
	flags,err:=unix.FcntlInt(uintptr(fd),unix.F_GETFL,0)
	if err!=nil||flags&unix.O_NONBLOCK==0{return nil,errors.New("WG_PIPE_BLOCKING")}
	file:=os.NewFile(uintptr(fd),"mcrf-wg-private-route")
	if file==nil{return nil,errors.New("WG_PIPE_FILE")}
	fd=-1 // os.File is now the sole owner, including RawConn setup failure.
	raw,err:=file.SyscallConn()
	if err!=nil{_ = file.Close();return nil,errors.New("WG_PIPE_POLL")}
	return &packetPipe{file:file,raw:raw,mtu:mtu,events:make(chan tun.Event)},nil
}

func pipePacket(packet []byte,mtu int) bool {
	if len(packet)<20||len(packet)>mtu{return false}
	switch packet[0]>>4 {
	case 4:
		header:=int(packet[0]&15)*4
		return header>=20&&header<=len(packet)&&int(packet[2])<<8|int(packet[3])==len(packet)
	case 6:
		return len(packet)>=40&&40+(int(packet[4])<<8|int(packet[5]))==len(packet)
	default:return false
	}
}
func(t *packetPipe) File()*os.File{return t.file}
func(t *packetPipe) Name()(string,error){return "mcrf-wg-private-route",nil}
func(t *packetPipe) MTU()(int,error){return t.mtu,nil}
func(t *packetPipe) Events()<-chan tun.Event{return t.events}
func(t *packetPipe) BatchSize()int{return 1}
func(t *packetPipe) Read(bufs [][]byte,sizes []int,offset int)(int,error){
	if len(bufs)<1||len(sizes)<1||offset<0||offset>=len(bufs[0]){return 0,errors.New("WG_PIPE_BUFFER")}
	var n,flags int;var readErr error
	err:=t.raw.Read(func(fd uintptr)bool{
		for {
			n,_,flags,_,readErr=unix.Recvmsg(int(fd),bufs[0][offset:],nil,unix.MSG_TRUNC|unix.MSG_DONTWAIT)
			if readErr==unix.EINTR{continue}
			return readErr!=unix.EAGAIN&&readErr!=unix.EWOULDBLOCK
		}
	})
	if err!=nil{return 0,errors.New("WG_PIPE_CLOSED")}
	if readErr!=nil{return 0,errors.New("WG_PIPE_READ")}
	if flags&unix.MSG_TRUNC!=0||n<0||n>len(bufs[0])-offset||!pipePacket(bufs[0][offset:offset+n],t.mtu){return 0,errors.New("WG_PIPE_PACKET")}
	sizes[0]=n;return 1,nil
}
func(t *packetPipe) Write(bufs [][]byte,offset int)(int,error){
	for index,buffer:=range bufs {
		if offset<0||offset>=len(buffer)||!pipePacket(buffer[offset:],t.mtu){return index,errors.New("WG_PIPE_PACKET")}
		var writeErr error
		err:=t.raw.Write(func(fd uintptr)bool{
			for {
				writeErr=unix.Sendto(int(fd),buffer[offset:],unix.MSG_DONTWAIT|unix.MSG_NOSIGNAL,nil)
				if writeErr==unix.EINTR{continue}
				return writeErr!=unix.EAGAIN&&writeErr!=unix.EWOULDBLOCK
			}
		})
		if err!=nil{return index,errors.New("WG_PIPE_CLOSED")}
		if writeErr!=nil{return index,errors.New("WG_PIPE_WRITE")}
	}
	return len(bufs),nil
}
func(t *packetPipe) Close()error{
	t.once.Do(func(){t.closeErr=t.file.Close();close(t.events)})
	return t.closeErr // Still NOT actual Android worker-death acknowledgement.
}
