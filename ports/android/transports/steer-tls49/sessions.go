package main

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
)

type owner49 struct {
	ctx    context.Context
	cancel context.CancelFunc
}
type session49 struct {
	epoch           uint64
	raw             net.Conn
	tls             *utls.UConn
	stream          io.ReadWriter
	alpn            string
	standardBuffer  []byte
	standardPending []byte
	readMu          sync.Mutex
	writeMu         sync.Mutex
	ready           bool // protected by registry.mu
}
type registry49 struct {
	mu         sync.Mutex
	next       uint64
	owners     map[uint64]*owner49
	sessions   map[uint64]*session49
	handshakes chan struct{}
}

func newRegistry49() *registry49 {
	return &registry49{owners: map[uint64]*owner49{}, sessions: map[uint64]*session49{}, handshakes: make(chan struct{}, 4)}
}

var live49 = newRegistry49()

func (r *registry49) begin() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.owners) >= 16 || r.next == ^uint64(0) {
		return 0
	}
	r.next++
	ctx, cancel := context.WithCancel(context.Background())
	r.owners[r.next] = &owner49{ctx, cancel}
	return r.next
}

func (r *registry49) stop(epoch uint64) {
	r.mu.Lock()
	o := r.owners[epoch]
	delete(r.owners, epoch)
	owned := make([]*session49, 0)
	for id, s := range r.sessions {
		if s.epoch == epoch {
			owned = append(owned, s)
			delete(r.sessions, id)
		}
	}
	if o != nil {
		o.cancel()
	}
	r.mu.Unlock()
	// Raw Close interrupts blocked I/O without acquiring TLS's write lock or
	// sending close_notify. Do not shutdown an unrelated fd reused by C.
	for _, s := range owned {
		_ = s.raw.Close()
	}
}

func (r *registry49) close(id uint64) {
	r.mu.Lock()
	s := r.sessions[id]
	delete(r.sessions, id)
	r.mu.Unlock()
	if s != nil {
		_ = s.raw.Close()
	}
}

func (r *registry49) get(id uint64) *session49 {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := r.sessions[id]
	if s == nil || !s.ready || r.owners[s.epoch] == nil {
		return nil
	}
	return s
}

// raw must be a DUPLICATE of an already-connected and VPN-protected C socket.
// This code has no Dial/Resolver; it cannot open a second unprotected connection.
func (r *registry49) open(epoch uint64, raw net.Conn, o options49, roots []byte) (id uint64, err error) {
	if raw == nil {
		return 0, errOptions
	}
	if !decoderInstalled49 {
		_ = raw.Close()
		return 0, errPolicy
	}
	r.mu.Lock()
	owner := r.owners[epoch]
	if owner == nil || len(r.sessions) >= 512 || r.next == ^uint64(0) {
		r.mu.Unlock()
		_ = raw.Close()
		return 0, errStopped
	}
	r.next++
	id = r.next
	s := &session49{epoch: epoch, raw: raw}
	r.sessions[id] = s
	r.mu.Unlock()
	allocatedID := id // named return may be overwritten by `return 0, err`
	defer func() {
		if err != nil {
			r.close(allocatedID)
			id = 0
		}
	}()
	ctx, cancel := context.WithTimeout(owner.ctx, time.Duration(o.TimeoutMS)*time.Millisecond)
	defer cancel()
	select {
	case r.handshakes <- struct{}{}:
		defer func() { <-r.handshakes }()
	case <-ctx.Done():
		return 0, errStopped
	}
	if e := establish49(ctx, s, o, roots); e != nil {
		return id, e
	}
	negotiated := s.alpn
	if o.RequiredALPN == "h2" && negotiated != "h2" {
		return id, errALPN
	}
	// HTTP/1.1 over TLS also permits no negotiated ALPN, as the native path did.
	if o.RequiredALPN == "http/1.1" && negotiated != "" && negotiated != "http/1.1" {
		return id, errALPN
	}
	r.mu.Lock()
	valid := r.owners[epoch] == owner && r.sessions[id] == s && ctx.Err() == nil
	if valid {
		s.ready = true
	}
	r.mu.Unlock()
	if !valid {
		return id, errStopped
	}
	_ = raw.SetDeadline(time.Time{})
	return id, nil
}

// Only reads receive a short poll deadline: an incomplete TLS record must not
// block the single native event loop. uTLS preserves partial rawInput on a
// temporary timeout. Writes remain serialized and use a separate deadline.
func (s *session49) read(dst []byte, direct bool) (int, error) {
	s.readMu.Lock()
	defer s.readMu.Unlock()
	if s.tls == nil {
		if len(s.standardPending) > 0 {
			n := copy(dst, s.standardPending)
			s.standardPending = s.standardPending[n:]
			return n, nil
		}
		if direct {
			return 0, io.EOF
		}
		if s.standardBuffer == nil {
			s.standardBuffer = make([]byte, 16384)
		}
		_ = s.raw.SetReadDeadline(time.Now().Add(2 * time.Millisecond))
		n, e := s.stream.Read(s.standardBuffer)
		_ = s.raw.SetReadDeadline(time.Time{})
		if n > 0 {
			s.standardPending = s.standardBuffer[:n]
			copied := copy(dst, s.standardPending)
			s.standardPending = s.standardPending[copied:]
			return copied, nil
		}
		return n, e
	}
	if direct {
		if n := s.tls.Conn.MCRFTakePending49(dst); n > 0 {
			return n, nil
		}
		return 0, io.EOF // C now reads its original descriptor, not TLS.
	}
	_ = s.raw.SetReadDeadline(time.Now().Add(2 * time.Millisecond))
	n, e := s.tls.Read(dst)
	_ = s.raw.SetReadDeadline(time.Time{})
	return n, e
}

func (s *session49) write(data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	n, e := s.stream.Write(data)
	if e == nil && n != len(data) {
		e = io.ErrShortWrite
	}
	return e // Caller closes the session after ANY partial write/error.
}

func (s *session49) hasData(direct bool) bool {
	if s.tls != nil {
		return s.tls.Conn.MCRFBuffered49(direct)
	}
	s.readMu.Lock()
	defer s.readMu.Unlock()
	return len(s.standardPending) > 0
}

// Fixed status codes only. A certificate failure may never become plaintext.
func status49(e error) int {
	if e == nil {
		return 0
	}
	switch {
	case errors.Is(e, errTLSWrite49):
		return -109 // never confuse a fatal TLS control write with partial-read EAGAIN
	case errors.Is(e, errOptions):
		return -100
	case errors.Is(e, errIdentity):
		return -101
	case errors.Is(e, errALPN):
		return -102
	case errors.Is(e, errReality):
		return -103
	case errors.Is(e, errPolicy):
		return -104
	case errors.Is(e, errStopped), errors.Is(e, context.Canceled):
		return -105
	case errors.Is(e, errLimit):
		return -106
	case errors.Is(e, io.EOF):
		return -107
	}
	var ne net.Error
	if errors.As(e, &ne) && ne.Timeout() {
		return -108
	}
	return -109
}
