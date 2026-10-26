// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var errLocal = errors.New("invalid private SOCKS request")

func writeAll(w io.Writer, p []byte) error {
	for len(p) > 0 {
		n, e := w.Write(p)
		if e != nil {
			return e
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		p = p[n:]
	}
	return nil
}
func authenticate(c net.Conn, user, password string) error {
	var h [2]byte
	if _, e := io.ReadFull(c, h[:]); e != nil {
		return e
	}
	if h[0] != 5 || h[1] == 0 {
		return errLocal
	}
	methods := make([]byte, int(h[1]))
	if _, e := io.ReadFull(c, methods); e != nil {
		return e
	}
	found := false
	for _, m := range methods {
		found = found || m == 2
	}
	if !found {
		writeAll(c, []byte{5, 255})
		return errLocal
	}
	if e := writeAll(c, []byte{5, 2}); e != nil {
		return e
	}
	if _, e := io.ReadFull(c, h[:]); e != nil {
		return e
	}
	if h[0] != 1 || h[1] == 0 {
		return errLocal
	}
	u := make([]byte, int(h[1]))
	if _, e := io.ReadFull(c, u); e != nil {
		return e
	}
	if _, e := io.ReadFull(c, h[:1]); e != nil {
		return e
	}
	p := make([]byte, int(h[0]))
	if _, e := io.ReadFull(c, p); e != nil {
		return e
	}
	if subtle.ConstantTimeCompare(u, []byte(user))&subtle.ConstantTimeCompare(p, []byte(password)) != 1 {
		writeAll(c, []byte{1, 1})
		return errLocal
	}
	return writeAll(c, []byte{1, 0})
}
func readAddress(r io.Reader) (M.Socksaddr, error) {
	var h [1]byte
	if _, e := io.ReadFull(r, h[:]); e != nil {
		return M.Socksaddr{}, e
	}
	kind := h[0]
	size := 0
	switch kind {
	case 1:
		size = 4
	case 4:
		size = 16
	case 3:
		if _, e := io.ReadFull(r, h[:]); e != nil {
			return M.Socksaddr{}, e
		}
		size = int(h[0])
		if size == 0 {
			return M.Socksaddr{}, errLocal
		}
	default:
		return M.Socksaddr{}, errLocal
	}
	data := make([]byte, size+2)
	if _, e := io.ReadFull(r, data); e != nil {
		return M.Socksaddr{}, e
	}
	port := binary.BigEndian.Uint16(data[size:])
	host := ""
	if kind == 1 || kind == 4 {
		ip, ok := netip.AddrFromSlice(data[:size])
		if !ok {
			return M.Socksaddr{}, errLocal
		}
		host = ip.String()
	} else {
		host = string(data[:size])
		for _, b := range data[:size] {
			if b <= 32 || b >= 127 {
				return M.Socksaddr{}, errLocal
			}
		}
	}
	result := M.ParseSocksaddrHostPort(host, port)
	if !result.IsValid() {
		return result, errLocal
	}
	return result, nil
}
func appendAddress(p []byte, a M.Socksaddr) ([]byte, error) {
	if a.Addr.IsValid() {
		if a.Addr.Is4() {
			p = append(p, 1)
			ip := a.Addr.As4()
			p = append(p, ip[:]...)
		} else {
			p = append(p, 4)
			ip := a.Addr.As16()
			p = append(p, ip[:]...)
		}
	} else {
		if len(a.Fqdn) == 0 || len(a.Fqdn) > 255 {
			return nil, errLocal
		}
		p = append(p, 3, byte(len(a.Fqdn)))
		p = append(p, a.Fqdn...)
	}
	return append(p, byte(a.Port>>8), byte(a.Port)), nil
}
func reply(c net.Conn, code byte, a M.Socksaddr) error {
	p, e := appendAddress([]byte{5, code, 0}, a)
	if e != nil {
		return e
	}
	return writeAll(c, p)
}
func zeroAddress() M.Socksaddr { return M.ParseSocksaddrHostPort("0.0.0.0", 0) }

// Own every accepted connection, including a blocked handshake. A canceled
// owner closes listener/clients; the child process is the final SDK backstop.
// Take a slot BEFORE Accept: overload is queued, never connection-513 reset.
func serve(ctx context.Context, ln *net.TCPListener, user, password string, dialer N.Dialer, udp bool, diag *diagnostics, ready func()) error {
	if a, ok := ln.Addr().(*net.TCPAddr); !ok || !a.IP.Equal(net.IPv4(127, 0, 0, 1)) {
		return errLocal
	}
	slots := make(chan struct{}, 512)
	var mu sync.Mutex
	clients := map[net.Conn]bool{}
	var wg sync.WaitGroup
	stop := context.AfterFunc(ctx, func() {
		ln.Close()
		mu.Lock()
		defer mu.Unlock()
		for c := range clients {
			c.Close()
		}
	})
	defer stop()
	defer func() {
		ln.Close()
		mu.Lock()
		for c := range clients {
			c.Close()
		}
		mu.Unlock()
		wg.Wait()
	}()
	ready()
	for {
		select {
		case slots <- struct{}{}:
		case <-ctx.Done():
			return ctx.Err()
		}
		c, e := ln.AcceptTCP()
		if e != nil {
			<-slots
			return e
		}
		mu.Lock()
		if ctx.Err() != nil {
			mu.Unlock()
			c.Close()
			<-slots
			return ctx.Err()
		}
		clients[c] = true
		mu.Unlock()
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { c.Close(); mu.Lock(); delete(clients, c); mu.Unlock(); <-slots }()
			handle(ctx, c, user, password, dialer, udp, diag)
		}()
	}
}
func handle(parent context.Context, c net.Conn, user, password string, dialer N.Dialer, udp bool, diag *diagnostics) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	if authenticate(c, user, password) != nil {
		return
	}
	var h [3]byte
	if _, e := io.ReadFull(c, h[:]); e != nil || h[0] != 5 || h[2] != 0 {
		return
	}
	a, e := readAddress(c)
	if e != nil {
		return
	}
	switch h[1] {
	case 1:
		if a.Port == 0 {
			reply(c, 8, zeroAddress())
			return
		}
		dialCtx, stop := context.WithTimeout(ctx, 20*time.Second)
		remote, e := dialer.DialContext(dialCtx, "tcp", a)
		stop()
		if e != nil {
			diag.report(e)
			reply(c, 1, zeroAddress())
			return
		}
		defer remote.Close()
		release := context.AfterFunc(ctx, func() { remote.Close() })
		defer release()
		if reply(c, 0, zeroAddress()) != nil {
			return
		}
		c.SetDeadline(time.Time{})
		pipe(c, remote)
	case 3:
		if !udp {
			reply(c, 7, zeroAddress())
			return
		}
		// Only exact authenticated 127.0.0.1:nonzero source is accepted. No wildcard
		// request, IP-only pin, first-sender learning or unauthenticated UDP ingress.
		peer, ok := c.RemoteAddr().(*net.TCPAddr)
		if !ok || !peer.IP.Equal(net.IPv4(127, 0, 0, 1)) || a.Addr != netip.MustParseAddr("127.0.0.1") || a.Port == 0 {
			reply(c, 8, zeroAddress())
			return
		}
		relay, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if e != nil {
			diag.report(e)
			return
		}
		defer relay.Close()
		release := context.AfterFunc(ctx, func() { relay.Close() })
		defer release()
		if reply(c, 0, M.SocksaddrFromNet(relay.LocalAddr())) != nil {
			return
		}
		c.SetDeadline(time.Time{})
		done := make(chan struct{})
		go func() { defer close(done); io.Copy(io.Discard, c); cancel() }()
		relayPackets(ctx, relay, a, dialer, diag)
		c.Close()
		<-done
	default:
		reply(c, 7, zeroAddress())
	}
}
func pipe(a, b net.Conn) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, e := io.Copy(b, a)
		if e != nil {
			a.Close()
			b.Close()
			return
		}
		N.CloseWrite(b)
	}()
	_, e := io.Copy(a, b)
	if e != nil {
		a.Close()
		b.Close()
	} else {
		N.CloseWrite(a)
	}
	<-done
}
func relayPackets(ctx context.Context, local *net.UDPConn, source M.Socksaddr, dialer N.Dialer, diag *diagnostics) {
	var remote net.PacketConn
	var finished chan struct{}
	defer func() {
		if remote != nil {
			remote.Close()
			<-finished
		}
	}()
	data := make([]byte, 65535)
	target := net.UDPAddrFromAddrPort(netip.AddrPortFrom(source.Addr, source.Port))
	for {
		n, from, e := local.ReadFromUDP(data)
		if e != nil {
			return
		}
		if !from.IP.Equal(target.IP) || from.Port != target.Port {
			continue
		}
		if n < 4 || data[0] != 0 || data[1] != 0 || data[2] != 0 {
			continue
		}
		reader := newPacketReader(data[3:n])
		a, e := readAddress(reader)
		if e != nil || a.Port == 0 {
			continue
		}
		if remote == nil {
			openCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
			remote, e = dialer.ListenPacket(openCtx, a)
			cancel()
			if e != nil {
				diag.report(e)
				return
			}
			finished = make(chan struct{})
			p := remote
			go func() {
				defer close(finished)
				defer local.Close()
				release := context.AfterFunc(ctx, func() { p.Close() })
				defer release()
				buffer := make([]byte, 65535)
				for {
					n, addr, e := p.ReadFrom(buffer)
					if e != nil {
						diag.report(e)
						local.Close()
						return
					}
					frame, e := appendAddress([]byte{0, 0, 0}, M.SocksaddrFromNet(addr))
					if e != nil || len(frame)+n > 65507 {
						continue
					}
					frame = append(frame, buffer[:n]...)
					if _, e = local.WriteToUDP(frame, target); e != nil {
						return
					}
				}
			}()
		}
		payload := data[n-reader.Len() : n]
		if _, e = remote.WriteTo(payload, a); e != nil {
			diag.report(e)
			return
		}
	}
}

// Reader exposes consumed framing without allocating/copying the UDP payload.
type packetReader struct {
	data   []byte
	offset int
}

func newPacketReader(p []byte) *packetReader { return &packetReader{data: p} }
func (p *packetReader) Read(b []byte) (int, error) {
	if p.offset == len(p.data) {
		return 0, io.EOF
	}
	n := copy(b, p.data[p.offset:])
	p.offset += n
	return n, nil
}
func (p *packetReader) Len() int { return len(p.data) - p.offset }
