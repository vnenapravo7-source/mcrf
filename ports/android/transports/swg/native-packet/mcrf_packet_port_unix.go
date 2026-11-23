//go:build linux || android

package csqtt

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"regexp"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

// OriginalPacketControl is private per-session configuration. It is never a
// public profile field or permission to retry/start another original child.
type OriginalPacketControl struct {
	Name       string `json:"name"`
	Nonce      string `json:"nonce"`
	Generation uint64 `json:"generation"`
	ParentPID  int    `json:"parent_pid"`
}

type originalPacketPort struct {
	connection *net.UnixConn
	local      netip.Addr
	mtu        int
	once       sync.Once
	closed     chan struct{}
}

// OpenOriginalPacketPort authenticates the actual direct parent over a private
// abstract control socket and transfers exactly one Go-created SEQPACKET FD.
// ACK means the parent has synchronously transferred FD ownership to its
// validated module worker; it does not assert VPN readiness. No packet relay is
// started here, and the caller must keep the same original Rust UDP socket.
func OpenOriginalPacketPort(ctx context.Context, control OriginalPacketControl, local netip.Addr, mtu int) (io.ReadWriteCloser, error) {
	if ctx == nil || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(control.Nonce) || control.Nonce != os.Getenv("MCRF_PROCESS_OWNER") || control.Name != "mcrf.carrier."+control.Nonce ||
		control.Generation == 0 || control.Generation > 1<<63-1 || control.ParentPID != os.Getppid() || control.ParentPID <= 0 ||
		!local.Is4() || local.IsUnspecified() || local.IsLoopback() || local.IsMulticast() || mtu < 576 || mtu > 1984 {
		return nil, errors.New("MCRF_PACKET_CONTROL_ARGUMENT")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	// Several route carriers may prepare concurrently while another waits for
	// CAPTCHA. Keep adoption bounded by the whole startup, not one 30s batch
	// member. This deadline belongs only to the discarded control connection;
	// the adopted packet connection never inherits a startup read deadline.
	deadline := time.Now().Add(360 * time.Second)
	if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
		deadline = limit
	}
	dialer := net.Dialer{Deadline: deadline}
	connection, err := dialer.DialContext(ctx, "unix", "@"+control.Name)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errors.New("MCRF_PACKET_CONTROL_CONNECT")
	}
	defer connection.Close()
	channel, ok := connection.(*net.UnixConn)
	if !ok {
		return nil, errors.New("MCRF_PACKET_CONTROL_TYPE")
	}
	_ = channel.SetDeadline(deadline)
	stopWatch := make(chan struct{})
	defer close(stopWatch)
	go func() {
		select {
		case <-ctx.Done():
			_ = channel.Close()
		case <-stopWatch:
		}
	}()
	raw, err := channel.SyscallConn()
	if err != nil {
		return nil, errors.New("MCRF_PACKET_CONTROL_FD")
	}
	var credentials *unix.Ucred
	var credentialError error
	err = raw.Control(func(fd uintptr) {
		credentials, credentialError = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	})
	if err != nil || credentialError != nil || credentials == nil || int(credentials.Pid) != control.ParentPID || int(credentials.Uid) != os.Getuid() {
		return nil, errors.New("MCRF_PACKET_CONTROL_OWNER")
	}
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return nil, errors.New("MCRF_PACKET_SOCKETPAIR")
	}
	// FileConn duplicates this side into Go's cancellable network poller.
	file := os.NewFile(uintptr(pair[0]), "mcrf-original-packets")
	packetConnection, err := net.FileConn(file)
	_ = file.Close()
	defer unix.Close(pair[1])
	if err != nil {
		return nil, errors.New("MCRF_PACKET_POLLER")
	}
	packets, ok := packetConnection.(*net.UnixConn)
	if !ok {
		_ = packetConnection.Close()
		return nil, errors.New("MCRF_PACKET_POLLER_TYPE")
	}
	port := &originalPacketPort{connection: packets, local: local, mtu: mtu, closed: make(chan struct{})}
	adopted := false
	defer func() {
		if !adopted {
			_ = port.Close()
		}
	}()
	n, oobn, err := channel.WriteMsgUnix([]byte{'P'}, unix.UnixRights(pair[1]), nil)
	if err != nil || n != 1 || oobn == 0 {
		return nil, errors.New("MCRF_PACKET_FD_TRANSFER")
	}
	metadata, err := json.Marshal(struct {
		Schema     int    `json:"schema"`
		Nonce      string `json:"nonce"`
		Generation uint64 `json:"generation"`
		Local      string `json:"local"`
		MTU        int    `json:"mtu"`
		Role       string `json:"role"`
	}{1, control.Nonce, control.Generation, local.String(), mtu, "original_ip_stack"})
	if err != nil || len(metadata) > 512 {
		return nil, errors.New("MCRF_PACKET_METADATA")
	}
	metadata = append(metadata, '\n')
	if n, err = channel.Write(metadata); err != nil || n != len(metadata) {
		return nil, errors.New("MCRF_PACKET_METADATA_WRITE")
	}
	ack, err := bufio.NewReader(io.LimitReader(channel, 128)).ReadString('\n')
	if err != nil || ack != fmt.Sprintf("MCRF_PACKET_OWNED|%s|%d\n", control.Nonce, control.Generation) {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errors.New("MCRF_PACKET_ADOPTION_REJECTED")
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	adopted = true
	go func() {
		select {
		case <-ctx.Done():
			_ = port.Close()
		case <-port.closed:
		}
	}()
	return port, nil
}

func (p *originalPacketPort) validate(packet []byte, inbound bool) error {
	if len(packet) < 20 || len(packet) > p.mtu || packet[0]>>4 != 4 {
		return errors.New("MCRF_PACKET_IP_BOUNDS")
	}
	header := int(packet[0]&15) * 4
	flags := uint16(packet[6])<<8 | uint16(packet[7])
	// The pinned native stack preserves DF while fragmenting locally emitted
	// packets (ipv4.buildNextFragment). This is a transparent IP carrier, not
	// a forwarding router deciding DF/PMTU: preserve those bytes for reassembly.
	if header < 20 || header > len(packet) || int(packet[2])<<8|int(packet[3]) != len(packet) || flags&0x8000 != 0 {
		return errors.New("MCRF_PACKET_IP_FORMAT")
	}
	offset := 12
	if inbound {
		offset = 16
	}
	if netip.AddrFrom4([4]byte{packet[offset], packet[offset+1], packet[offset+2], packet[offset+3]}) != p.local {
		return errors.New("MCRF_PACKET_IP_OWNER")
	}
	return nil
}

// Read is original IP emitted by the module toward the Rust UDP relay.
func (p *originalPacketPort) Read(buffer []byte) (int, error) {
	// RelayIP supplies its reusable 65535-byte buffer. Receive into it directly,
	// retaining the extra byte and MSG_TRUNC checks for oversized datagrams.
	// Small io.Reader callers keep the previous consume-and-reject semantics.
	packet := buffer
	if len(packet) < p.mtu+1 {
		packet = make([]byte, p.mtu+1)
	} else {
		packet = packet[:p.mtu+1]
	}
	n, oobn, flags, _, err := p.connection.ReadMsgUnix(packet, nil)
	if err != nil {
		return 0, err
	}
	if n == 0 {
		return 0, io.EOF
	}
	if oobn != 0 || flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 || n > p.mtu || n > len(buffer) {
		return 0, errors.New("MCRF_PACKET_TRUNCATED")
	}
	if err = p.validate(packet[:n], false); err != nil {
		return 0, err
	}
	if len(buffer) < p.mtu+1 {
		copy(buffer, packet[:n])
	}
	return n, nil
}

// Write delivers an unchanged original IP reply to the module stack.
func (p *originalPacketPort) Write(packet []byte) (int, error) {
	if err := p.validate(packet, true); err != nil {
		return 0, err
	}
	n, err := p.connection.Write(packet)
	if err == nil && n != len(packet) {
		err = io.ErrShortWrite
	}
	return n, err
}
func (p *originalPacketPort) Close() error {
	var err error
	p.once.Do(func() { close(p.closed); err = p.connection.Close() })
	return err
}
