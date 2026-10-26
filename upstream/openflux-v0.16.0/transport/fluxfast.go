package transport

import (
	"bytes"
	"context"
	crand "crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/p1neappleXpress/OpenFlux/netbind"
	"github.com/p1neappleXpress/OpenFlux/utils"
)

const (
	fluxFastMagic0  byte = 0xF1
	fluxFastMagic1  byte = 0xFA
	fluxFastVersion      = 1

	fluxFastTypeData           byte = 0
	fluxFastTypePing           byte = 1
	fluxFastTypePong           byte = 2
	fluxFastTypePathReq        byte = 3
	fluxFastTypePathRes        byte = 4
	fluxFastHeaderSize              = 20
	fluxFastMaxPayload              = 1200
	fluxFastMaxFragments            = 255
	fluxFastChallengeTTL            = 3 * time.Second
	fluxFastChallengeRetry          = 100 * time.Millisecond
	fluxFastMaxReassemblyRecs       = 256
	fluxFastMaxReassemblyBytes      = 8 << 20
)

// FluxFastConfig configures the FluxFastTransport, a UDP carrier for
// already-framed tunnel records. See docs/fluxfast.md for the wire format.
type FluxFastConfig struct {
	// ListenAddr is the exit's UDP address ("host:port", ":port", or
	// "0.0.0.0:0"). The actual address after Start is LocalAddr.
	ListenAddr string
	// DialAddr is the client's exit address ("host:port").
	DialAddr string
	// IsExit makes the transport answer on ListenAddr instead of dialing.
	IsExit bool
	// Chunk is the largest record payload in one datagram. Keep 20+Chunk
	// under the path MTU, or the IP stack fragments the datagram.
	Chunk int
	// MTU is what the tunnel stack should use. The default leaves room
	// for IPv4, UDP and the FluxFast header.
	MTU int
	// QueueBytes caps records waiting for the writer.
	QueueBytes int
	// Unpreferred turns off Preferred.
	Unpreferred bool
	// HandshakeTimeout bounds DNS and the first socket setup.
	HandshakeTimeout time.Duration
	// KeepAliveInterval is how often a quiet peer is pinged.
	KeepAliveInterval time.Duration
	// DeadAfter is how long without a valid packet before IsConnected
	// turns false. The socket stays open, so a roaming peer can return.
	DeadAfter time.Duration
	// ReassemblyTimeout drops an incomplete record.
	ReassemblyTimeout time.Duration
	// QuietTakeover is how long the active peer must be silent before a
	// different connection id may take the port (after path validation).
	QuietTakeover time.Duration
	// MaxRecordBytes is the largest record accepted from the tunnel.
	MaxRecordBytes int
}

// DefaultFluxFastConfig returns the documented v1 defaults.
func DefaultFluxFastConfig() FluxFastConfig {
	return FluxFastConfig{
		Chunk:             1180,
		MTU:               1280,
		QueueBytes:        512 << 10,
		HandshakeTimeout:  15 * time.Second,
		KeepAliveInterval: 5 * time.Second,
		DeadAfter:         30 * time.Second,
		ReassemblyTimeout: 3 * time.Second,
		QuietTakeover:     25 * time.Second,
		MaxRecordBytes:    64 << 10,
	}
}

func normalizeFluxFastConfig(c FluxFastConfig) FluxFastConfig {
	d := DefaultFluxFastConfig()
	if c.Chunk == 0 {
		c.Chunk = d.Chunk
	}
	if c.Chunk < 64 {
		c.Chunk = 64
	}
	if c.Chunk > fluxFastMaxPayload {
		c.Chunk = fluxFastMaxPayload
	}
	if c.MTU == 0 {
		c.MTU = d.MTU
	}
	if c.MTU < 576 {
		c.MTU = 576
	}
	if c.QueueBytes == 0 {
		c.QueueBytes = d.QueueBytes
	}
	if c.QueueBytes < 64<<10 {
		c.QueueBytes = 64 << 10
	}
	if c.HandshakeTimeout <= 0 {
		c.HandshakeTimeout = d.HandshakeTimeout
	}
	if c.KeepAliveInterval <= 0 {
		c.KeepAliveInterval = d.KeepAliveInterval
	}
	if c.DeadAfter <= 0 {
		c.DeadAfter = d.DeadAfter
	}
	if c.ReassemblyTimeout <= 0 {
		c.ReassemblyTimeout = d.ReassemblyTimeout
	}
	if c.QuietTakeover <= 0 {
		c.QuietTakeover = d.QuietTakeover
	}
	if c.MaxRecordBytes == 0 {
		c.MaxRecordBytes = d.MaxRecordBytes
	}
	if c.MaxRecordBytes < 64 {
		c.MaxRecordBytes = 64
	}
	if c.MaxRecordBytes > 4<<20 {
		c.MaxRecordBytes = 4 << 20
	}
	return c
}

type fluxFastHeader struct {
	typ     byte
	flags   byte
	connID  uint64
	seq     uint32
	frag    byte
	total   byte
	payload []byte
}

type fluxFastPending struct {
	addr     *net.UDPAddr
	connID   uint64
	token    [8]byte
	sent     time.Time
	deadline time.Time
}

type fluxFastRecord struct {
	frags [][]byte
	got   int
	total int
	bytes int
	first time.Time
}

// FluxFastTransport is a UDP carrier for already-framed tunnel records.
// It is one port for one active peer: a second client waits for the active
// one to go quiet, then validates its path and takes the port.
type FluxFastTransport struct {
	*BaseTransport
	config FluxFastConfig

	conn       *net.UDPConn
	remote     *net.UDPAddr
	connID     uint64
	queue      chan []byte
	queueBytes atomic.Int64
	sendSeq    atomic.Uint32
	stopOnce   sync.Once
	drops      atomic.Uint64

	mu       sync.RWMutex
	peerID   uint64
	peerAddr *net.UDPAddr
	lastRecv time.Time
	lastPing time.Time
	pending  *fluxFastPending

	reasmMu    sync.Mutex
	reasm      map[uint32]*fluxFastRecord
	reasmBytes int
}

// NewFluxFastTransport builds a FluxFastTransport from a base config.
func NewFluxFastTransport(base TransportConfig, cfg FluxFastConfig) *FluxFastTransport {
	cfg = normalizeFluxFastConfig(cfg)
	return &FluxFastTransport{
		BaseTransport: NewBaseTransport(base),
		config:        cfg,
		reasm:         make(map[uint32]*fluxFastRecord),
	}
}

func (t *FluxFastTransport) Start() error {
	if t.IsRunning() {
		return nil
	}
	cfg := t.config
	var conn *net.UDPConn
	if cfg.IsExit {
		addr := cfg.ListenAddr
		if addr == "" {
			addr = "0.0.0.0:0"
		}
		ua, err := resolveFluxFastAddr(addr, cfg.HandshakeTimeout)
		if err != nil {
			return fmt.Errorf("fluxfast: resolve listen %q: %w", addr, err)
		}
		conn, err = net.ListenUDP("udp", ua)
		if err != nil {
			return fmt.Errorf("fluxfast: listen %q: %w", addr, err)
		}
	} else {
		if cfg.DialAddr == "" {
			return errors.New("fluxfast: no dial address")
		}
		remote, err := resolveFluxFastAddr(cfg.DialAddr, cfg.HandshakeTimeout)
		if err != nil {
			return fmt.Errorf("fluxfast: resolve dial %q: %w", cfg.DialAddr, err)
		}
		local := "0.0.0.0:0"
		if remote.IP.To4() == nil {
			local = "[::]:0"
		}
		conn, err = netbind.ListenUDPFor(t.GetConfig().Network, local)
		if err != nil {
			return fmt.Errorf("fluxfast: udp socket: %w", err)
		}
		t.remote = remote
		t.peerAddr = cloneUDPAddr(remote)
	}
	t.conn = conn
	t.connID = newFluxFastID()
	capacity := cfg.QueueBytes / cfg.Chunk
	if capacity < 16 {
		capacity = 16
	}
	if capacity > 4096 {
		capacity = 4096
	}
	t.queue = make(chan []byte, capacity)
	t.reasm = make(map[uint32]*fluxFastRecord)
	if err := t.BaseTransport.Start(); err != nil {
		conn.Close()
		return err
	}
	t.SetConnected(false)
	go t.readLoop()
	go t.writeLoop()
	go t.keepAliveLoop()
	if !cfg.IsExit {
		go t.sendPing()
	}
	return nil
}

func (t *FluxFastTransport) Stop() error {
	t.stopOnce.Do(func() {
		if t.conn != nil {
			t.conn.Close()
		}
		_ = t.BaseTransport.Stop()
	})
	return nil
}

func (t *FluxFastTransport) Send(data []byte) error {
	if !t.IsRunning() {
		return errors.New("fluxfast: not running")
	}
	if len(data) > t.config.MaxRecordBytes {
		return fmt.Errorf("fluxfast: record size %d over %d: %w", len(data), t.config.MaxRecordBytes, ErrRecordTooLarge)
	}
	buf := append([]byte(nil), data...)
	n := int64(len(buf))
	if t.queueBytes.Add(n) > int64(t.config.QueueBytes) {
		t.queueBytes.Add(-n)
		t.drops.Add(1)
		return errors.New("fluxfast: send queue full")
	}
	select {
	case t.queue <- buf:
		return nil
	default:
		t.queueBytes.Add(-n)
		t.drops.Add(1)
		return errors.New("fluxfast: send queue full")
	}
}

func (t *FluxFastTransport) IsConnected() bool     { return t.BaseTransport.IsConnected() }
func (t *FluxFastTransport) Stats() TransportStats { return t.BaseTransport.Stats() }
func (t *FluxFastTransport) CheapBytes() bool      { return true }
func (t *FluxFastTransport) CarrierMTU() int       { return t.config.MTU }
func (t *FluxFastTransport) PreferredBatchBytes() int {
	return t.config.Chunk * 3
}

func (t *FluxFastTransport) Preferred() bool {
	if t.config.Unpreferred {
		return false
	}
	switch os.Getenv("OPENFLUX_FLUXFAST_FIRST") {
	case "0":
		return false
	case "1":
		return true
	}
	return true
}

// LocalAddr is the bound UDP address, useful for tests and for an exit that
// asked for port 0.
func (t *FluxFastTransport) LocalAddr() string {
	if t.conn == nil {
		return t.config.ListenAddr
	}
	return t.conn.LocalAddr().String()
}

// Drops counts datagrams discarded for any reason.
func (t *FluxFastTransport) Drops() uint64 { return t.drops.Load() }

func (t *FluxFastTransport) readLoop() {
	buf := make([]byte, 65535)
	for {
		n, addr, err := t.conn.ReadFromUDP(buf)
		if err != nil {
			if !t.IsRunning() {
				return
			}
			select {
			case <-t.Done():
				return
			default:
				continue
			}
		}
		t.RecordReceive(n)
		t.ingest(buf[:n], addr)
	}
}

func (t *FluxFastTransport) writeLoop() {
	for {
		select {
		case <-t.Done():
			return
		case rec := <-t.queue:
			t.queueBytes.Add(-int64(len(rec)))
			if err := t.sendRecord(rec); err != nil {
				t.drops.Add(1)
				utils.Debugf("[FLUXFAST] send: %v", err)
			}
		}
	}
}

func (t *FluxFastTransport) wireConnID() uint64 {
	t.mu.RLock()
	id := t.peerID
	t.mu.RUnlock()
	if id != 0 {
		return id
	}
	return t.connID
}

func (t *FluxFastTransport) sendRecord(rec []byte) error {
	chunk := t.config.Chunk
	total := (len(rec) + chunk - 1) / chunk
	if total == 0 {
		total = 1
	}
	if total > fluxFastMaxFragments {
		return ErrRecordTooLarge
	}
	flags := byte(0)
	if total > 1 {
		flags = 1
	}
	connID := t.wireConnID()
	seq := t.sendSeq.Add(1)
	for i := 0; i < total; i++ {
		lo := i * chunk
		hi := lo + chunk
		if hi > len(rec) {
			hi = len(rec)
		}
		p := make([]byte, fluxFastHeaderSize+hi-lo)
		encodeFluxFastHeader(p, fluxFastTypeData, flags, connID, seq, byte(i), byte(total), hi-lo)
		copy(p[fluxFastHeaderSize:], rec[lo:hi])
		if err := t.writeData(p); err != nil {
			return err
		}
	}
	return nil
}

func (t *FluxFastTransport) writeData(p []byte) error {
	t.mu.RLock()
	addr := t.peerAddr
	t.mu.RUnlock()
	if addr == nil {
		return errors.New("fluxfast: no peer address")
	}
	return t.writeTo(p, addr)
}

func (t *FluxFastTransport) writeControl(typ byte, connID uint64, payload []byte, addr *net.UDPAddr) {
	if addr == nil {
		return
	}
	p := make([]byte, fluxFastHeaderSize+len(payload))
	encodeFluxFastHeader(p, typ, 0, connID, 0, 0, 1, len(payload))
	copy(p[fluxFastHeaderSize:], payload)
	if err := t.writeTo(p, addr); err != nil {
		utils.Debugf("[FLUXFAST] control %d: %v", typ, err)
	}
}

func (t *FluxFastTransport) writeTo(p []byte, addr *net.UDPAddr) error {
	if t.conn == nil {
		return errors.New("fluxfast: socket not started")
	}
	_, err := t.conn.WriteToUDP(p, addr)
	if err == nil {
		t.RecordSend(len(p))
	}
	return err
}

func encodeFluxFastHeader(p []byte, typ, flags byte, connID uint64, seq uint32, frag, total byte, n int) {
	p[0], p[1] = fluxFastMagic0, fluxFastMagic1
	p[2] = byte(fluxFastVersion<<4) | (typ & 0x0F)
	p[3] = flags
	binary.BigEndian.PutUint64(p[4:12], connID)
	binary.BigEndian.PutUint32(p[12:16], seq)
	p[16], p[17] = frag, total
	binary.BigEndian.PutUint16(p[18:20], uint16(n))
}

func parseFluxFastPacket(p []byte) (fluxFastHeader, bool) {
	var h fluxFastHeader
	if len(p) < fluxFastHeaderSize {
		return h, false
	}
	if p[0] != fluxFastMagic0 || p[1] != fluxFastMagic1 {
		return h, false
	}
	if p[2]>>4 != fluxFastVersion {
		return h, false
	}
	h.typ = p[2] & 0x0F
	if h.typ > fluxFastTypePathRes {
		return h, false
	}
	h.flags = p[3]
	if h.flags&^0x01 != 0 {
		return h, false
	}
	h.connID = binary.BigEndian.Uint64(p[4:12])
	h.seq = binary.BigEndian.Uint32(p[12:16])
	h.frag, h.total = p[16], p[17]
	n := int(binary.BigEndian.Uint16(p[18:20]))
	if n != len(p)-fluxFastHeaderSize {
		return h, false
	}
	if n > fluxFastMaxPayload {
		return h, false
	}
	if h.total == 0 || h.frag >= h.total {
		return h, false
	}
	if h.total > 1 {
		if h.flags&0x01 == 0 {
			return h, false
		}
	} else if h.flags&0x01 != 0 || h.frag != 0 {
		return h, false
	}
	switch h.typ {
	case fluxFastTypePing, fluxFastTypePong:
		if n != 0 {
			return h, false
		}
	case fluxFastTypePathReq, fluxFastTypePathRes:
		if n != 8 {
			return h, false
		}
	}
	h.payload = p[fluxFastHeaderSize:]
	return h, true
}

func (t *FluxFastTransport) ingest(p []byte, addr *net.UDPAddr) {
	h, ok := parseFluxFastPacket(p)
	if !ok {
		t.drops.Add(1)
		return
	}
	now := time.Now()
	switch h.typ {
	case fluxFastTypePathRes:
		t.handlePathResponse(h, addr, now)
		return
	case fluxFastTypePathReq:
		t.handlePathChallenge(h, addr)
		return
	case fluxFastTypePing, fluxFastTypePong:
		if !t.validatePeer(h.connID, addr, now) {
			t.drops.Add(1)
			return
		}
		t.markPeerSeen(now)
		if h.typ == fluxFastTypePing {
			t.writeControl(fluxFastTypePong, h.connID, nil, addr)
		}
	case fluxFastTypeData:
		if !t.validatePeer(h.connID, addr, now) {
			t.drops.Add(1)
			return
		}
		t.markPeerSeen(now)
		t.handleData(h)
	default:
		t.drops.Add(1)
	}
}

// validatePeer accepts a packet from the active peer, or starts a path
// challenge for a new address or, after QuietTakeover, a new connection id.
// A challenge is the only way a different path becomes active.
func (t *FluxFastTransport) validatePeer(id uint64, addr *net.UDPAddr, now time.Time) bool {
	var send bool
	var token [8]byte
	var challengeAddr *net.UDPAddr
	var challengeID uint64

	t.mu.Lock()
	if t.peerID == 0 {
		t.peerID = id
		t.peerAddr = cloneUDPAddr(addr)
		t.lastRecv = now
		t.mu.Unlock()
		return true
	}
	if id == t.peerID && sameUDPAddr(addr, t.peerAddr) {
		t.mu.Unlock()
		return true
	}
	if id == t.peerID {
		challengeID = id
		challengeAddr = addr
	} else if now.Sub(t.lastRecv) >= t.config.QuietTakeover {
		challengeID = id
		challengeAddr = addr
	} else {
		t.mu.Unlock()
		return false
	}
	if t.pending == nil || !sameUDPAddr(t.pending.addr, challengeAddr) || now.Sub(t.pending.sent) >= fluxFastChallengeRetry {
		token = randomFluxFastToken()
		t.pending = &fluxFastPending{
			addr:     cloneUDPAddr(challengeAddr),
			connID:   challengeID,
			token:    token,
			sent:     now,
			deadline: now.Add(fluxFastChallengeTTL),
		}
		send = true
	}
	t.mu.Unlock()

	if send {
		t.writeControl(fluxFastTypePathReq, challengeID, token[:], challengeAddr)
	}
	return false
}

func (t *FluxFastTransport) handlePathResponse(h fluxFastHeader, addr *net.UDPAddr, now time.Time) {
	t.mu.Lock()
	p := t.pending
	if p == nil || !sameUDPAddr(p.addr, addr) || !bytes.Equal(p.token[:], h.payload) || now.After(p.deadline) {
		t.mu.Unlock()
		t.drops.Add(1)
		return
	}
	peerID, peerAddr := p.connID, p.addr
	t.peerID = peerID
	t.peerAddr = peerAddr
	t.lastRecv = now
	t.pending = nil
	t.mu.Unlock()

	t.resetReassembly()
	t.SetConnected(true)
	// Tell the peer the path is live. A client that just restarted has not
	// heard anything from us at its new connection id, and without a packet
	// it would stay "not connected" while its data already flows.
	t.writeControl(fluxFastTypePing, peerID, nil, peerAddr)
}

func (t *FluxFastTransport) handlePathChallenge(h fluxFastHeader, addr *net.UDPAddr) {
	t.mu.RLock()
	known := h.connID == t.peerID
	if !known && t.pending != nil {
		known = h.connID == t.pending.connID && sameUDPAddr(t.pending.addr, addr)
	}
	// A client that has just started or restarted has no peer yet, and the
	// exit's first contact after QuietTakeover is a PATH_REQ naming this
	// client's own connection id. Answer it from the address we dialed, or
	// the takeover could never complete: the exit sends no data until the
	// path is proven and the client cannot prove it on its own.
	if !known && !t.config.IsExit && t.peerID == 0 && sameUDPAddr(addr, t.remote) {
		known = true
	}
	t.mu.RUnlock()
	if known {
		t.writeControl(fluxFastTypePathRes, h.connID, h.payload, addr)
	}
}

func (t *FluxFastTransport) markPeerSeen(now time.Time) {
	t.mu.Lock()
	t.lastRecv = now
	t.mu.Unlock()
	t.SetConnected(true)
}

func (t *FluxFastTransport) handleData(h fluxFastHeader) {
	if h.total == 1 {
		if len(h.payload) > t.config.MaxRecordBytes {
			t.drops.Add(1)
			return
		}
		t.deliver(h.payload)
		return
	}

	t.reasmMu.Lock()
	if rec, ok := t.reasm[h.seq]; ok && rec.total != int(h.total) {
		delete(t.reasm, h.seq)
		t.reasmBytes -= rec.bytes
	}
	rec := t.reasm[h.seq]
	if rec == nil {
		if len(t.reasm) >= fluxFastMaxReassemblyRecs || t.reasmBytes+len(h.payload) > fluxFastMaxReassemblyBytes {
			t.reasmMu.Unlock()
			t.drops.Add(1)
			return
		}
		rec = &fluxFastRecord{
			frags: make([][]byte, int(h.total)),
			total: int(h.total),
			first: time.Now(),
		}
		t.reasm[h.seq] = rec
	}
	if rec.frags[h.frag] != nil {
		t.reasmMu.Unlock()
		return
	}
	frag := append([]byte(nil), h.payload...)
	rec.frags[h.frag] = frag
	rec.got++
	rec.bytes += len(frag)
	t.reasmBytes += len(frag)
	if rec.got != rec.total {
		t.reasmMu.Unlock()
		return
	}
	out := make([]byte, 0, rec.bytes)
	for _, f := range rec.frags {
		out = append(out, f...)
	}
	delete(t.reasm, h.seq)
	t.reasmBytes -= rec.bytes
	t.reasmMu.Unlock()

	if len(out) > t.config.MaxRecordBytes {
		t.drops.Add(1)
		return
	}
	t.deliver(out)
}

func (t *FluxFastTransport) deliver(p []byte) {
	t.CallReceive(append([]byte(nil), p...))
}

func (t *FluxFastTransport) resetReassembly() {
	t.reasmMu.Lock()
	t.reasm = make(map[uint32]*fluxFastRecord)
	t.reasmBytes = 0
	t.reasmMu.Unlock()
}

func (t *FluxFastTransport) reap(now time.Time) {
	t.reasmMu.Lock()
	for seq, rec := range t.reasm {
		if now.Sub(rec.first) >= t.config.ReassemblyTimeout {
			delete(t.reasm, seq)
			t.reasmBytes -= rec.bytes
		}
	}
	t.reasmMu.Unlock()
}

func (t *FluxFastTransport) keepAliveLoop() {
	interval := t.config.KeepAliveInterval
	tick := interval / 2
	if tick > time.Second {
		tick = time.Second
	}
	if tick < 20*time.Millisecond {
		tick = 20 * time.Millisecond
	}
	ticker := time.NewTicker(tick)
	defer ticker.Stop()
	for {
		select {
		case <-t.Done():
			return
		case now := <-ticker.C:
			t.reap(now)
			t.checkDead(now)
			t.maybePing(now)
		}
	}
}

func (t *FluxFastTransport) checkDead(now time.Time) {
	t.mu.RLock()
	peer := t.peerID
	last := t.lastRecv
	t.mu.RUnlock()
	if peer != 0 && !last.IsZero() && now.Sub(last) > t.config.DeadAfter {
		t.SetConnected(false)
	}
}

func (t *FluxFastTransport) maybePing(now time.Time) {
	t.mu.RLock()
	peer := t.peerID
	addr := t.peerAddr
	lastPing := t.lastPing
	lastRecv := t.lastRecv
	t.mu.RUnlock()
	if addr == nil {
		return
	}
	if t.config.IsExit && peer == 0 {
		return
	}
	if now.Sub(lastPing) < t.config.KeepAliveInterval {
		return
	}
	if peer != 0 && !lastRecv.IsZero() && now.Sub(lastRecv) < t.config.KeepAliveInterval {
		return
	}
	id := peer
	if id == 0 {
		id = t.connID
	}
	t.writeControl(fluxFastTypePing, id, nil, addr)
	t.mu.Lock()
	t.lastPing = now
	t.mu.Unlock()
}

func (t *FluxFastTransport) sendPing() {
	t.mu.RLock()
	peer := t.peerID
	addr := t.peerAddr
	t.mu.RUnlock()
	if addr == nil {
		return
	}
	id := peer
	if id == 0 {
		id = t.connID
	}
	t.writeControl(fluxFastTypePing, id, nil, addr)
	t.mu.Lock()
	t.lastPing = time.Now()
	t.mu.Unlock()
}

// resolveFluxFastAddr resolves "host:port" under the handshake timeout, so a
// slow or dead DNS server cannot hold Start longer than any other handshake.
// A literal address skips DNS entirely.
func resolveFluxFastAddr(address string, timeout time.Duration) (*net.UDPAddr, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	if port == "" {
		return nil, fmt.Errorf("%q has no port", address)
	}
	portNum, err := fluxFastPort(port)
	if err != nil {
		return nil, err
	}
	if host == "" {
		return &net.UDPAddr{Port: portNum}, nil
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return &net.UDPAddr{IP: addr.AsSlice(), Port: portNum, Zone: addr.Zone()}, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no address for %q", host)
	}
	return &net.UDPAddr{IP: ips[0].AsSlice(), Port: portNum, Zone: ips[0].Zone()}, nil
}

func fluxFastPort(port string) (int, error) {
	if n, err := strconv.Atoi(port); err == nil {
		if n < 0 || n > 65535 {
			return 0, fmt.Errorf("port %d out of range", n)
		}
		return n, nil
	}
	return net.LookupPort("udp", port)
}

func cloneUDPAddr(a *net.UDPAddr) *net.UDPAddr {
	if a == nil {
		return nil
	}
	ip := append(net.IP(nil), a.IP...)
	return &net.UDPAddr{IP: ip, Port: a.Port, Zone: a.Zone}
}

func sameUDPAddr(a, b *net.UDPAddr) bool {
	return a != nil && b != nil && a.Port == b.Port && a.IP.Equal(b.IP)
}

func newFluxFastID() uint64 {
	var b [8]byte
	if _, err := crand.Read(b[:]); err == nil {
		if id := binary.BigEndian.Uint64(b[:]); id != 0 {
			return id
		}
	}
	id := uint64(time.Now().UnixNano())
	if id == 0 {
		id = 1
	}
	return id
}

func randomFluxFastToken() [8]byte {
	var b [8]byte
	if _, err := crand.Read(b[:]); err != nil {
		binary.BigEndian.PutUint64(b[:], uint64(time.Now().UnixNano()))
	}
	return b
}
