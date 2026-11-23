//go:build with_lx_raw_packet && (linux || android)

package libbox

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/unix"
)

const mcrfRawPacketMaximum = 1984

type mcrfRawEndpoint struct {
	mu         sync.RWMutex
	closed     bool
	fd         int
	endpointID string
	generation uint64
	local      netip.Addr
	mtu        int
	identity   string
}

func (e *mcrfRawEndpoint) Identity() string         { return e.identity }
func (e *mcrfRawEndpoint) Generation() uint64       { return e.generation }
func (e *mcrfRawEndpoint) LocalAddress() netip.Addr { return e.local }
func (e *mcrfRawEndpoint) MTU() int                 { return e.mtu }

func (e *mcrfRawEndpoint) SendOriginalPacket(ctx context.Context, packet []byte) (adapter.RawPacketSendState, error) {
	if err := validateMcrfRawIPv4(packet, e.local, e.mtu, false); err != nil {
		return adapter.RawPacketRejected, err
	}
	if err := ctx.Err(); err != nil {
		return adapter.RawPacketRejected, err
	}
	e.mu.RLock()
	defer e.mu.RUnlock()
	if e.closed {
		return adapter.RawPacketRejected, errors.New("MCRF_RAW_ENDPOINT_CLOSED")
	}
	n, err := unix.SendmsgN(e.fd, packet, nil, nil, unix.MSG_DONTWAIT|unix.MSG_NOSIGNAL)
	if err == unix.EAGAIN || err == unix.EWOULDBLOCK {
		return adapter.RawPacketWouldBlock, nil
	}
	if err != nil {
		return adapter.RawPacketRejected, err
	}
	if n != len(packet) {
		return adapter.RawPacketRejected, errors.New("MCRF_RAW_NONATOMIC_SEND")
	}
	return adapter.RawPacketAccepted, nil
}

func (e *mcrfRawEndpoint) ReadOriginalPacket(ctx context.Context) ([]byte, error) {
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		packet, retry, err := e.readOriginalPacketOnce()
		if retry {
			continue
		}
		return packet, err
	}
}

// Hold ownership through both poll and recv: Close must not release this number
// while the kernel call could otherwise operate on a newly reused descriptor.
func (e *mcrfRawEndpoint) readOriginalPacketOnce() ([]byte, bool, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	if e.closed {
		return nil, false, errors.New("MCRF_RAW_ENDPOINT_CLOSED")
	}
	fds := []unix.PollFd{{Fd: int32(e.fd), Events: unix.POLLIN}}
	_, err := unix.Poll(fds, 100)
	if err == unix.EINTR {
		return nil, true, nil
	}
	if err != nil {
		return nil, false, err
	}
	if fds[0].Revents&(unix.POLLERR|unix.POLLHUP|unix.POLLNVAL) != 0 {
		return nil, false, errors.New("MCRF_RAW_ENDPOINT_CLOSED")
	}
	if fds[0].Revents&unix.POLLIN == 0 {
		return nil, true, nil
	}
	packet := make([]byte, e.mtu+1)
	n, oobn, flags, _, readErr := unix.Recvmsg(e.fd, packet, nil, unix.MSG_DONTWAIT|unix.MSG_TRUNC)
	if readErr == unix.EAGAIN || readErr == unix.EWOULDBLOCK || readErr == unix.EINTR {
		return nil, true, nil
	}
	if readErr != nil {
		return nil, false, readErr
	}
	if oobn != 0 || flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 || n > e.mtu || n > len(packet) {
		return nil, false, errors.New("MCRF_RAW_TRUNCATED_REPLY")
	}
	packet = packet[:n]
	if err = validateMcrfRawIPv4(packet, e.local, e.mtu, true); err != nil {
		return nil, false, err
	}
	return packet, false, nil
}

func (e *mcrfRawEndpoint) Close() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return nil
	}
	e.closed = true
	return unix.Close(e.fd)
}

func (e *mcrfRawEndpoint) WaitOriginalPacketWritable(ctx context.Context) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		e.mu.RLock()
		if e.closed {
			e.mu.RUnlock()
			return errors.New("MCRF_RAW_ENDPOINT_CLOSED")
		}
		fds := []unix.PollFd{{Fd: int32(e.fd), Events: unix.POLLOUT}}
		_, err := unix.Poll(fds, 100)
		e.mu.RUnlock()
		if err == unix.EINTR {
			continue
		}
		if err != nil {
			return err
		}
		if fds[0].Revents&(unix.POLLERR|unix.POLLHUP|unix.POLLNVAL) != 0 {
			return errors.New("MCRF_RAW_ENDPOINT_CLOSED")
		}
		if fds[0].Revents&unix.POLLOUT != 0 {
			return nil
		}
	}
}

func validateMcrfRawIPv4(packet []byte, local netip.Addr, mtu int, reply bool) error {
	if len(packet) < 20 || len(packet) > mtu || packet[0]>>4 != 4 {
		return errors.New("MCRF_RAW_IPV4_BOUNDS")
	}
	headerLength := int(packet[0]&15) * 4
	if headerLength < 20 || headerLength > len(packet) || int(packet[2])<<8|int(packet[3]) != len(packet) || (int(packet[6])<<8|int(packet[7]))&0xbfff != 0 {
		return errors.New("MCRF_RAW_IPV4_LENGTH_OR_FRAGMENT")
	}
	if reply {
		if netip.AddrFrom4([4]byte{packet[16], packet[17], packet[18], packet[19]}).Unmap() != local {
			return errors.New("MCRF_RAW_REPLY_LOCAL_ADDRESS")
		}
	} else if netip.AddrFrom4([4]byte{packet[12], packet[13], packet[14], packet[15]}).Unmap() != local {
		return errors.New("MCRF_RAW_SOURCE_ADDRESS")
	}
	return nil
}

func prepareMcrfRawPacketEndpointContext(ctx context.Context) (context.Context, func(string, int32, string, int64, int32, string, int32) error, func(string) error, func(), func()) {
	registry := adapter.NewOriginalPacketEndpointRegistry()
	ctx = service.ContextWith[adapter.OriginalPacketEndpointProvider](ctx, registry)
	register := func(tag string, fd int32, endpointID string, generation int64, peerPID int32, localAddress string, mtu int32) error {
		endpoint, err := duplicateAndValidateMcrfRawEndpoint(int(fd), endpointID, generation, peerPID, localAddress, mtu)
		if err != nil {
			return err
		}
		if err = registry.Register(tag, endpoint); err != nil {
			_ = endpoint.Close()
			return err
		}
		return nil
	}
	return ctx, register, registry.Unregister, registry.Close, registry.Close
}

func duplicateAndValidateMcrfRawEndpoint(fd int, endpointID string, generation int64, peerPID int32, localAddress string, mtu int32) (*mcrfRawEndpoint, error) {
	if fd < 0 || len(endpointID) != 32 || strings.Trim(endpointID, "0123456789abcdef") != "" || generation <= 0 || generation > 1<<63-1 || peerPID <= 0 || mtu < 20 || mtu > mcrfRawPacketMaximum {
		return nil, errors.New("MCRF_RAW_ENDPOINT_ARGUMENT")
	}
	local, err := netip.ParseAddr(localAddress)
	if err != nil || !local.Is4() {
		return nil, errors.New("MCRF_RAW_ENDPOINT_ADDRESS")
	}
	dupFD, err := unix.Dup(fd)
	if err != nil {
		return nil, err
	}
	cleanup := true
	defer func() {
		if cleanup {
			_ = unix.Close(dupFD)
		}
	}()
	unix.CloseOnExec(dupFD)
	if err = unix.SetNonblock(dupFD, true); err != nil {
		return nil, err
	}
	socketType, err := unix.GetsockoptInt(dupFD, unix.SOL_SOCKET, unix.SO_TYPE)
	if err != nil || socketType != unix.SOCK_SEQPACKET {
		return nil, errors.New("MCRF_RAW_ENDPOINT_SOCKET_TYPE")
	}
	localSocket, err := unix.Getsockname(dupFD)
	if err != nil {
		return nil, err
	}
	peerSocket, err := unix.Getpeername(dupFD)
	if err != nil {
		return nil, err
	}
	if _, ok := localSocket.(*unix.SockaddrUnix); !ok {
		return nil, errors.New("MCRF_RAW_ENDPOINT_NOT_UNIX")
	}
	if _, ok := peerSocket.(*unix.SockaddrUnix); !ok {
		return nil, errors.New("MCRF_RAW_ENDPOINT_NOT_CONNECTED")
	}
	cred, err := unix.GetsockoptUcred(dupFD, unix.SOL_SOCKET, unix.SO_PEERCRED)
	if err != nil || cred == nil || cred.Pid != peerPID || int(cred.Uid) != os.Getuid() {
		return nil, errors.New("MCRF_RAW_ENDPOINT_PEER_IDENTITY")
	}
	var stat unix.Stat_t
	if err = unix.Fstat(dupFD, &stat); err != nil {
		return nil, err
	}
	e := &mcrfRawEndpoint{fd: dupFD, endpointID: endpointID, generation: uint64(generation), local: local.Unmap(), mtu: int(mtu),
		identity: fmt.Sprintf("%s:%d:%d:%s", endpointID, generation, stat.Dev, strconv.FormatUint(stat.Ino, 16))}
	cleanup = false
	return e, nil
}
