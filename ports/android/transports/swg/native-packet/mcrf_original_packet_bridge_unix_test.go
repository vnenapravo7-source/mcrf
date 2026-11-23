//go:build linux || android

package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"golang.org/x/sys/unix"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
	"wg-turn-client/internal/csqtt"
)

func TestMcrfOriginalPacketBridgeChild(t *testing.T) {
	listen := os.Getenv("MCRF_ORIGINAL_BRIDGE_CHILD")
	if listen == "" {
		return
	}
	control, err := mcrfReadOriginalPacketControl(false)
	if err != nil || control == nil {
		t.Fatal("private child control", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var key csqtt.PacketAuth
	encodedKey := os.Getenv("MCRF_ORIGINAL_BRIDGE_FIXTURE_KEY")
	if len(encodedKey) != 2*len(key) {
		t.Fatal("private fixture key unavailable")
	}
	if _, err = hex.Decode(key[:], []byte(encodedKey)); err != nil {
		t.Fatal("private fixture key invalid")
	}
	err = mcrfRunOriginalPacketBridge(ctx, *control, csqtt.TunnelConfig{Address: netip.MustParseAddr("198.18.0.1")}, listen, make(chan struct{}), &key)
	if err == nil || err.Error() != "MCRF_PACKET_RELAY_STOPPED" {
		t.Fatal("unexpected endpoint retirement", err)
	}
}

func TestMcrfOriginalPacketBridgeOwnedUDPAndRetirement(t *testing.T) {
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(nonceBytes); err != nil {
		t.Fatal(err)
	}
	control := csqtt.OriginalPacketControl{Nonce: hex.EncodeToString(nonceBytes), Generation: 91, ParentPID: os.Getpid()}
	control.Name = "mcrf.carrier." + control.Nonce
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + control.Name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	_ = listener.SetDeadline(time.Now().Add(3 * time.Second))
	remote, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()
	_ = remote.SetDeadline(time.Now().Add(3 * time.Second))
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(executable, "-test.run=^TestMcrfOriginalPacketBridgeChild$", "-test.timeout=7s")
	encoded, _ := json.Marshal(control)
	key, err := csqtt.NewPacketAuth()
	if err != nil {
		t.Fatal(err)
	}
	command.Env = append(os.Environ(), "MCRF_PROCESS_OWNER="+control.Nonce, mcrfOriginalPacketEnvironment+"="+string(encoded), "MCRF_ORIGINAL_BRIDGE_CHILD="+remote.LocalAddr().String())
	command.Env = append(command.Env, "MCRF_ORIGINAL_BRIDGE_FIXTURE_KEY="+hex.EncodeToString(key[:]))
	output := new(bytes.Buffer)
	command.Stdout = output
	command.Stderr = output
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if command.ProcessState == nil {
			_ = command.Process.Kill()
			_ = command.Wait()
		}
	}()
	channel, err := listener.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	defer channel.Close()
	_ = channel.SetDeadline(time.Now().Add(3 * time.Second))
	one := make([]byte, 1)
	ancillary := make([]byte, 256)
	n, on, flags, _, err := channel.ReadMsgUnix(one, ancillary)
	if err != nil || n != 1 || one[0] != 'P' || flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 {
		t.Fatal("packet FD envelope", err)
	}
	messages, err := unix.ParseSocketControlMessage(ancillary[:on])
	if err != nil || len(messages) != 1 {
		t.Fatal("packet FD control", err)
	}
	fds, err := unix.ParseUnixRights(&messages[0])
	if err != nil || len(fds) != 1 {
		for _, fd := range fds {
			_ = unix.Close(fd)
		}
		t.Fatal("exact one packet FD", err)
	}
	file := os.NewFile(uintptr(fds[0]), "owned-packet-fixture")
	defer file.Close()
	peer, err := unix.GetsockoptUcred(fds[0], unix.SOL_SOCKET, unix.SO_PEERCRED)
	if err != nil || int(peer.Pid) != command.Process.Pid || int(peer.Uid) != os.Getuid() {
		t.Fatal("packet creator identity", err)
	}
	connection, err := net.FileConn(file)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
	metadata, err := bufio.NewReader(io.LimitReader(channel, 513)).ReadString('\n')
	var values map[string]any
	if err != nil || json.Unmarshal([]byte(metadata), &values) != nil || len(values) != 6 || values["role"] != "original_ip_stack" || values["nonce"] != control.Nonce || values["generation"] != float64(91) || values["mtu"] != float64(1500) {
		t.Fatal("packet metadata", err)
	}
	if _, err = io.WriteString(channel, "MCRF_PACKET_OWNED|"+control.Nonce+"|91\n"); err != nil {
		t.Fatal(err)
	}
	// Real packets above the old 1300-byte ceiling must cross both owned legs.
	// This catches an advertised-MTU-only fix that still drops larger frames.
	packetMAC := func(direction byte, body []byte) []byte {
		mac := hmac.New(sha256.New, key[:])
		_, _ = mac.Write([]byte("MCRF_CSQTT_PRIVATE_UDP_V1"))
		_, _ = mac.Write([]byte{direction})
		_, _ = mac.Write(body)
		return mac.Sum(nil)
	}
	var lastReply []byte
	for index, size := range []int{48, 1280, 1300, 1400, 1500} {
		request := make([]byte, size)
		request[0] = 0x45
		request[2] = byte(len(request) >> 8)
		request[3] = byte(len(request))
		request[8] = 64
		request[9] = 253
		copy(request[12:16], []byte{198, 18, 0, 1})
		copy(request[16:20], []byte{198, 18, 0, 2})
		for i := 20; i < len(request); i++ {
			request[i] = byte(i)
		}
		if _, err = connection.Write(request); err != nil {
			t.Fatal(err)
		}
		buffer := make([]byte, 2048)
		n, address, err := remote.ReadFromUDP(buffer)
		if err != nil || n != size+40 || !hmac.Equal(packetMAC(1, buffer[:n-32]), buffer[n-32:n]) ||
			!bytes.Equal(buffer[8:n-32], request) {
			t.Fatal("authenticated original bytes on same UDP dispatcher", err)
		}
		reply := bytes.Clone(request)
		copy(reply[12:16], request[16:20])
		copy(reply[16:20], request[12:16])
		// A completely unframed reply cannot reach the original socketpair.
		if _, err = remote.WriteToUDP(bytes.Repeat([]byte{9}, size), address); err != nil {
			t.Fatal(err)
		}
		if lastReply != nil {
			if _, err = remote.WriteToUDP(lastReply, address); err != nil {
				t.Fatal(err)
			}
		}
		frame := binary.BigEndian.AppendUint64(nil, uint64(index)+1)
		frame = append(frame, reply...)
		frame = append(frame, packetMAC(2, frame)...)
		lastReply = frame
		if _, err = remote.WriteToUDP(frame, address); err != nil {
			t.Fatal(err)
		}
		n, err = connection.Read(buffer)
		if err != nil || !bytes.Equal(buffer[:n], reply) {
			t.Fatal("original IP reply bytes", err)
		}
	}
	_ = connection.Close()
	_ = file.Close()
	done := make(chan error, 1)
	go func() { done <- command.Wait() }()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal("owned child shutdown", err)
		}
	case <-time.After(time.Second):
		t.Fatal("packet bridge did not retire with endpoint")
	}
	if !strings.Contains(output.String(), "MCRF_ORIGINAL_PACKET_READY\n") || strings.Contains(output.String(), "\nMCRF_READY\n") {
		t.Fatal("distinct readiness contract")
	}
}

func TestMcrfOriginalPacketPrivateConfig(t *testing.T) {
	nonce := "0123456789abcdef0123456789abcdef"
	t.Setenv("MCRF_PROCESS_OWNER", nonce)
	control := csqtt.OriginalPacketControl{Name: "mcrf.carrier." + nonce, Nonce: nonce, Generation: 91, ParentPID: os.Getppid()}
	valid, _ := json.Marshal(control)
	t.Setenv(mcrfOriginalPacketEnvironment, string(valid))
	if parsed, err := mcrfReadOriginalPacketControl(false); err != nil || parsed == nil || *parsed != control {
		t.Fatal("private host control rejected", err)
	}
	if _, err := mcrfReadOriginalPacketControl(true); err == nil {
		t.Fatal("probe selected original packet mode")
	}
	for _, invalid := range []string{"", "null", "{}", string(valid) + "{}", `{"name":"a","name":"b","nonce":"x","generation":91,"parent_pid":1}`} {
		t.Setenv(mcrfOriginalPacketEnvironment, invalid)
		if _, err := mcrfReadOriginalPacketControl(false); err == nil {
			t.Fatal("invalid private config accepted")
		}
	}
	for _, mutate := range []func(map[string]any){
		func(m map[string]any) { m["nonce"] = "bad" }, func(m map[string]any) { m["parent_pid"] = os.Getpid() },
		func(m map[string]any) { m["name"] = "foreign" }, func(m map[string]any) { m["generation"] = 0 },
		func(m map[string]any) { m["generation"] = "91" }, func(m map[string]any) { m["extra"] = true },
		func(m map[string]any) { m["parent_pid"] = nil },
	} {
		var values map[string]any
		_ = json.Unmarshal(valid, &values)
		mutate(values)
		raw, _ := json.Marshal(values)
		t.Setenv(mcrfOriginalPacketEnvironment, string(raw))
		if _, err := mcrfReadOriginalPacketControl(false); err == nil {
			t.Fatal("foreign or ambiguous control accepted")
		}
	}
}
