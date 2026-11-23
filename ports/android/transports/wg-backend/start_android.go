package wgbackend

import (
	"errors"
	"golang.org/x/sys/unix"
)

// Start never owns or closes the caller's borrowed descriptor. No /dev/tun creation,
// root, external shell, gVisor or sing-box runtime is used.
func Start(borrowedTunFd int32, mtu int32, configuration string, protector Protector) (*Session, error) {
	return startOwned(borrowedTunFd, mtu, configuration, protector, nil)
}

func StartIsolated(borrowedTunFd int32, mtu int32, configuration string, protector Protector, owner PacketOwner) (*Session, error) {
	if owner == nil {
		return nil, errors.New("WG_OWNER_REQUIRED")
	}
	return startOwned(borrowedTunFd, mtu, configuration, protector, owner)
}

func startOwned(borrowedTunFd int32, mtu int32, configuration string, protector Protector, owner PacketOwner) (*Session, error) {
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
	if owner != nil {
		tunnel.owner = &ownerGate{lookup: owner}
	}
	bind, err := protectedBind(protector)
	if err != nil {
		_ = tunnel.Close()
		return nil, err
	}
	return startDevice(tunnel, bind, configuration)
}

// Separate INTERNAL per-server packet role. It never accepts a real TUN fd,
// creates no interface/listener and imports no gVisor/sing-box stack. The host
// must keep original-flow routing/DNS/NAT outside this encrypted packet backend.
// Source only until an exact new module capability and full dispatcher exist.
func StartPacketPipe(borrowedFd int32, mtu int32, configuration string, protector Protector, peerPID int32) (*Session, error) {
	return startPacketPipe(int(borrowedFd), int(mtu), configuration, protector, int(peerPID))
}

// Separate opt-in extension: no old Start call gains DNS interception. Only the
// caller's exact internal DNS aliases are reflected to its live high-port stub.
func StartManaged(borrowedTunFd int32, mtu int32, configuration string, protector Protector, address4, address6, sources string, port int32) (*Session, error) {
	return startManaged(borrowedTunFd, mtu, configuration, protector, address4, address6, sources, port, nil)
}

func StartManagedIsolated(borrowedTunFd int32, mtu int32, configuration string, protector Protector, address4, address6, sources string, port int32, owner PacketOwner) (*Session, error) {
	if owner == nil {
		return nil, errors.New("WG_OWNER_REQUIRED")
	}
	return startManaged(borrowedTunFd, mtu, configuration, protector, address4, address6, sources, port, owner)
}

func startManaged(borrowedTunFd int32, mtu int32, configuration string, protector Protector, address4, address6, sources string, port int32, owner PacketOwner) (*Session, error) {
	if borrowedTunFd < 0 || protector == nil || mtu < 1280 || mtu > 9000 {
		return nil, errors.New("WG_INPUT_REQUIRED")
	}
	nat, err := newDnsNat(address4, address6, sources, int(port))
	if err != nil {
		return nil, err
	}
	defer func() {
		if nat != nil {
			nat.close()
		}
	}()
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
	if owner != nil {
		tunnel.owner = &ownerGate{lookup: owner}
	}
	bind, err := protectedBind(protector)
	if err != nil {
		_ = tunnel.Close()
		return nil, err
	}
	session, err := startDevice(&managedDnsTun{Device: tunnel, nat: nat}, bind, configuration)
	if err != nil {
		nat.close()
		return nil, err
	}
	session.dns = nat
	nat = nil
	return session, nil
}
