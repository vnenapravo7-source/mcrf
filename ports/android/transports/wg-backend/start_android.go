package wgbackend

import (
	"errors"
	"golang.org/x/sys/unix"
)

// Start never owns or closes the caller's borrowed descriptor. No /dev/tun creation,
// root, external shell, gVisor or sing-box runtime is used.
func Start(borrowedTunFd int32, mtu int32, configuration string, protector Protector) (*Session, error) {
	if borrowedTunFd < 0 || protector == nil || mtu < 1280 || mtu > 9000 {
		return nil, errors.New("WG_INPUT_REQUIRED")
	}
	fd, err := unix.Dup(int(borrowedTunFd))
	if err != nil {
		return nil, errors.New("WG_TUN_DUP_FAILED")
	}
	unix.CloseOnExec(fd)
	tunnel, err := ownedTun(fd, int(mtu))
	if err != nil {
		_ = unix.Close(fd)
		return nil, errors.New("WG_TUN_REJECTED")
	}
	bind, err := protectedBind(protector)
	if err != nil {
		_ = tunnel.Close()
		return nil, err
	}
	return startDevice(tunnel, bind, configuration)
}

// Separate opt-in extension: no old Start call gains DNS interception. Only the
// caller's exact internal DNS aliases are reflected to its live high-port stub.
func StartManaged(borrowedTunFd int32,mtu int32,configuration string,protector Protector,address4,address6,sources string,port int32)(*Session,error){
	if borrowedTunFd<0||protector==nil||mtu<1280||mtu>9000{return nil,errors.New("WG_INPUT_REQUIRED")}
	nat,err:=newDnsNat(address4,address6,sources,int(port));if err!=nil{return nil,err}
	defer func(){if nat!=nil{nat.close()}}()
	fd,err:=unix.Dup(int(borrowedTunFd));if err!=nil{return nil,errors.New("WG_TUN_DUP_FAILED")};unix.CloseOnExec(fd)
	tunnel,err:=ownedTun(fd,int(mtu));if err!=nil{_ = unix.Close(fd);return nil,errors.New("WG_TUN_REJECTED")}
	bind,err:=protectedBind(protector);if err!=nil{_ = tunnel.Close();return nil,err}
	session,err:=startDevice(&managedDnsTun{Device:tunnel,nat:nat},bind,configuration);if err!=nil{nat.close();return nil,err}
	session.dns=nat;nat=nil;return session,nil
}
