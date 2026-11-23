package csqtt

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"hash"
	"io"
	"net"
	"slices"
	"strings"
)

const (
	packetAuthEnv      = "MCRF_CSQTT_PRIVATE_UDP_KEY_V1"
	packetRequiredEnv  = "MCRF_CSQTT_PRIVATE_UDP_REQUIRED"
	packetAuthDomain   = "MCRF_CSQTT_PRIVATE_UDP_V1"
	packetAuthOverhead = 8 + sha256.Size
	privatePacketLimit = 1900
)

type PacketAuth [32]byte

// An app-owned bridge always creates a new key, even for the same profile.
func NewPacketAuth() (*PacketAuth, error) {
	auth := new(PacketAuth)
	if _, err := rand.Read(auth[:]); err != nil {
		return nil, errors.New("CSQTT private UDP key generation failed")
	}
	return auth, nil
}

func (a *PacketAuth) ChildEnvironment(env []string) []string {
	env = slices.DeleteFunc(env, func(value string) bool {
		return strings.HasPrefix(value, packetAuthEnv+"=") || strings.HasPrefix(value, packetRequiredEnv+"=")
	})
	return append(env, packetRequiredEnv+"=1", packetAuthEnv+"="+hex.EncodeToString(a[:]))
}

type packetReplayWindow struct {
	largest uint64
	seen    uint64
}

func (w *packetReplayWindow) accept(sequence uint64) bool {
	if sequence == 0 {
		return false
	}
	if sequence > w.largest {
		w.seen = w.seen<<(sequence-w.largest) | 1
		w.largest = sequence
		return true
	}
	delta := w.largest - sequence
	if delta >= 64 || w.seen&(1<<delta) != 0 {
		return false
	}
	w.seen |= 1 << delta
	return true
}

type authenticatedPacketSocket struct {
	*net.UDPConn
	tx, rx   hash.Hash
	sequence uint64
	replay   packetReplayWindow
	up, down [privatePacketLimit + packetAuthOverhead + 1]byte
}

// RelayIP owns one writer and one reader, each with independent state.
func (a *PacketAuth) Socket(socket *net.UDPConn) io.ReadWriteCloser {
	return &authenticatedPacketSocket{
		UDPConn: socket, tx: hmac.New(sha256.New, a[:]), rx: hmac.New(sha256.New, a[:]),
	}
}

func resetPacketMAC(mac hash.Hash, direction byte, data []byte) {
	mac.Reset()
	_, _ = io.WriteString(mac, packetAuthDomain)
	_, _ = mac.Write([]byte{direction})
	_, _ = mac.Write(data)
}

func (s *authenticatedPacketSocket) Write(payload []byte) (int, error) {
	if len(payload) == 0 || len(payload) > privatePacketLimit || s.sequence == ^uint64(0) {
		return 0, errors.New("CSQTT private UDP write exceeds limits")
	}
	s.sequence++
	frame := s.up[:8+len(payload)]
	binary.BigEndian.PutUint64(frame, s.sequence)
	copy(frame[8:], payload)
	resetPacketMAC(s.tx, 1, frame)
	frame = s.tx.Sum(frame)
	if n, err := s.UDPConn.Write(frame); err != nil || n != len(frame) {
		if err == nil {
			err = io.ErrShortWrite
		}
		return 0, err
	}
	return len(payload), nil
}

func (s *authenticatedPacketSocket) Read(dst []byte) (int, error) {
	var digest [sha256.Size]byte
	for {
		n, err := s.UDPConn.Read(s.down[:])
		if err != nil {
			return 0, err
		}
		if n <= packetAuthOverhead || n > privatePacketLimit+packetAuthOverhead {
			continue
		}
		body := s.down[:n-sha256.Size]
		resetPacketMAC(s.rx, 2, body)
		if !hmac.Equal(s.rx.Sum(digest[:0]), s.down[n-sha256.Size:n]) ||
			!s.replay.accept(binary.BigEndian.Uint64(body)) {
			continue
		}
		if len(body)-8 > len(dst) {
			return 0, io.ErrShortBuffer
		}
		return copy(dst, body[8:]), nil
	}
}
