package csqtt

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"net"
	"os"
	"strconv"
	"testing"
	"time"
)

func TestPrivatePacketUDPGoldenVectors(t *testing.T) {
	key := bytes.Repeat([]byte{3}, 32)
	for _, item := range []struct {
		direction byte
		payload   string
		frame     string
	}{
		{1, "first", "00000000000000016669727374d6a17979c1fbf00c95826df3d614076bd17d52f4917b8619efe4bf6701e89dee"},
		{2, "reply", "00000000000000017265706c791010efbd8abb16decbf23f62809b6913ae020d063e8795e246043a7467353d03"},
	} {
		body := append(binary.BigEndian.AppendUint64(nil, 1), item.payload...)
		mac := hmac.New(sha256.New, key)
		resetPacketMAC(mac, item.direction, body)
		if hex.EncodeToString(mac.Sum(body)) != item.frame {
			t.Fatal("private packet canonical frame differs")
		}
	}
}

// Each generation runs its actual UDP Dispatcher with this helper as peer.
func TestPrivatePacketUDPGoPeer(t *testing.T) {
	rawPort := os.Getenv("MCRF_CSQTT_PRIVATE_UDP_TEST_PORT")
	if rawPort == "" {
		return
	}
	port, err := strconv.Atoi(rawPort)
	if err != nil || port < 1 || port > 65535 {
		t.Fatal("invalid paired UDP fixture port")
	}
	var key PacketAuth
	hexKey := os.Getenv(packetAuthEnv)
	if len(hexKey) != 64 {
		t.Fatal("paired UDP fixture key is absent")
	}
	if _, err = hex.Decode(key[:], []byte(hexKey)); err != nil {
		t.Fatal("invalid paired UDP fixture key")
	}
	address := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
	owner, err := net.DialUDP("udp4", nil, address)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	outsider, err := net.DialUDP("udp4", nil, address)
	if err != nil {
		t.Fatal(err)
	}
	defer outsider.Close()
	_ = owner.SetDeadline(time.Now().Add(5 * time.Second))
	_ = outsider.SetDeadline(time.Now().Add(5 * time.Second))

	payload := []byte{0x45, 0, 0, 28, 0, 0, 0, 0, 64, 17, 0, 0, 198, 18, 0, 1, 198, 18, 0, 2, 0x2e, 0xe0, 1, 0xbb, 0, 8, 0, 0}
	send := func(frame []byte) {
		if _, err := outsider.Write(frame); err != nil {
			t.Fatal(err)
		}
	}
	send(payload)
	badKey := key
	badKey[0] ^= 1
	if _, err = badKey.Socket(outsider).Write(payload); err != nil {
		t.Fatal(err)
	}
	auth := key.Socket(owner).(*authenticatedPacketSocket)
	if _, err = auth.Write(payload); err != nil {
		t.Fatal(err)
	}
	original := bytes.Clone(auth.up[:len(payload)+packetAuthOverhead])
	send(original)
	tampered := bytes.Clone(original)
	tampered[7] = 100
	send(tampered)
	tampered[8] ^= 1
	send(tampered)
	body := append(binary.BigEndian.AppendUint64(nil, 100), payload...)
	tag := hmac.New(sha256.New, key[:])
	resetPacketMAC(tag, 2, body)
	send(tag.Sum(body))
	var reply [64]byte
	n, err := auth.Read(reply[:])
	if err != nil || !bytes.Equal(reply[:n], payload) {
		t.Fatal("valid Go peer did not receive authenticated Rust reply", err)
	}
	_ = outsider.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, err := outsider.Read(reply[:]); err == nil {
		t.Fatal("replayed or forged datagram changed the Rust reply peer")
	} else if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
		t.Fatal("invalid outsider fixture read", err)
	}
}

func TestPrivatePacketUDPRejectsForgeryWrongKeyDirectionAndReplay(t *testing.T) {
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	local, err := net.DialUDP("udp4", nil, peer.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer local.Close()
	_ = local.SetDeadline(time.Now().Add(2 * time.Second))
	_ = peer.SetDeadline(time.Now().Add(2 * time.Second))
	var key PacketAuth
	key[0] = 3
	s := key.Socket(local)
	if _, err = s.Write([]byte("request")); err != nil {
		t.Fatal(err)
	}
	wire := make([]byte, 2048)
	n, address, err := peer.ReadFromUDP(wire)
	if err != nil || n != 47 || binary.BigEndian.Uint64(wire) != 1 || !bytes.Equal(wire[8:15], []byte("request")) {
		t.Fatal("wrong private UDP frame")
	}
	tx := hmac.New(sha256.New, key[:])
	resetPacketMAC(tx, 1, wire[:15])
	if !hmac.Equal(tx.Sum(nil), wire[15:n]) {
		t.Fatal("wrong request tag")
	}
	frame := func(sequence uint64, direction byte, key PacketAuth, data string) []byte {
		body := append(binary.BigEndian.AppendUint64(nil, sequence), data...)
		tag := hmac.New(sha256.New, key[:])
		resetPacketMAC(tag, direction, body)
		return tag.Sum(body)
	}
	send := func(frame []byte) {
		if _, err = peer.WriteToUDP(frame, address); err != nil {
			t.Fatal(err)
		}
	}
	send([]byte("unframed"))
	send(frame(999, 2, PacketAuth{4}, "forged"))
	send(frame(999, 1, key, "wrong direction"))
	one := frame(1, 2, key, "one")
	send(one)
	send(one)
	send(frame(2, 2, key, "two"))
	for _, expected := range []string{"one", "two"} {
		n, err = s.Read(wire)
		if err != nil || string(wire[:n]) != expected {
			t.Fatal("private UDP response did not authenticate in order", err)
		}
	}
}
