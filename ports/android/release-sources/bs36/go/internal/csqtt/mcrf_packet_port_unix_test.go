//go:build linux || android

package csqtt

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestMcrfOriginalPacketPortDirectReadBounds(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	connections := make([]*net.UnixConn, 0, 2)
	for _, fd := range fds {
		file := os.NewFile(uintptr(fd), "owned-read-buffer-test")
		conn, e := net.FileConn(file)
		file.Close()
		if e != nil {
			t.Fatal(e)
		}
		connections = append(connections, conn.(*net.UnixConn))
		t.Cleanup(func() { conn.Close() })
		conn.SetDeadline(time.Now().Add(5 * time.Second))
	}
	port := &originalPacketPort{connection: connections[0], local: netip.MustParseAddr("198.18.0.1"), mtu: 1300, closed: make(chan struct{})}
	packet := make([]byte, 1301)
	packet[0] = 0x45
	copy(packet[12:16], []byte{198, 18, 0, 1})
	copy(packet[16:20], []byte{192, 0, 2, 1})
	for _, test := range []struct {
		size, buffer int
		valid        bool
	}{
		{20, 65535, true}, {1300, 65535, true}, {20, 32, true},
		{1300, 1299, false}, {1301, 65535, false}, {1300, 65535, true},
	} {
		packet[2], packet[3] = byte(test.size>>8), byte(test.size)
		if n, e := connections[1].Write(packet[:test.size]); e != nil || n != test.size {
			t.Fatal(n, e)
		}
		buffer := make([]byte, test.buffer)
		n, e := port.Read(buffer)
		if test.valid {
			if e != nil || n != test.size || !bytes.Equal(buffer[:n], packet[:test.size]) {
				t.Fatalf("size %d buffer %d: n=%d err=%v", test.size, test.buffer, n, e)
			}
		} else if e == nil || n != 0 {
			t.Fatalf("accepted truncated packet size %d buffer %d", test.size, test.buffer)
		}
	}
}

func TestMcrfOriginalPacketPortChild(t *testing.T) {
	value := os.Getenv("MCRF_ORIGINAL_PACKET_CHILD")
	if value == "" {
		return
	}
	var control OriginalPacketControl
	if err := json.Unmarshal([]byte(value), &control); err != nil {
		t.Fatal(err)
	}
	if os.Getenv("MCRF_ANDROID_OWNER_FIXTURE") == "1" {
		if err := unix.Setpgid(0, 0); err != nil {
			t.Fatal(err)
		}
		data, err := os.ReadFile("/proc/self/stat")
		if err != nil {
			t.Fatal(err)
		}
		end := strings.LastIndex(string(data), ") ")
		if end < 0 {
			t.Fatal("private process stat")
		}
		fields := strings.Fields(string(data)[end+2:])
		if len(fields) < 20 || fields[2] != strconv.Itoa(os.Getpid()) {
			t.Fatal("private process group")
		}
		fmt.Printf("MCRF_PROCESS_GROUP_V1|%s|%d|%s\n", control.Nonce, os.Getpid(), fields[19])
		ack := make(chan string, 1)
		go func() { value, _ := bufio.NewReader(os.Stdin).ReadString('\n'); ack <- value }()
		select {
		case value := <-ack:
			if value != "MCRF_OWNER_ACK|"+control.Nonce+"\n" {
				t.Fatal("private owner ACK")
			}
		case <-time.After(3 * time.Second):
			t.Fatal("private owner ACK timeout")
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	port, err := OpenOriginalPacketPort(ctx, control, netip.MustParseAddr("198.18.0.1"), 1300)
	if os.Getenv("MCRF_ORIGINAL_PACKET_REJECT") == "1" {
		if err == nil {
			_ = port.Close()
			t.Fatal("invalid adoption accepted")
		}
		if !strings.Contains(err.Error(), "MCRF_PACKET_ADOPTION_REJECTED") {
			t.Fatal(err)
		}
		return
	}
	if err != nil {
		t.Fatal(err)
	}
	defer port.Close()
	buffer := make([]byte, 1984)
	mode := os.Getenv("MCRF_ORIGINAL_PACKET_TEST_MODE")
	if strings.HasPrefix(mode, "relay@") {
		address, e := net.ResolveUDPAddr("udp4", strings.TrimPrefix(mode, "relay@"))
		if e != nil || !address.IP.IsLoopback() {
			t.Fatal("owned UDP dispatcher required")
		}
		socket, e := net.DialUDP("udp4", nil, address)
		if e != nil {
			t.Fatal(e)
		}
		e = RelayIP(ctx, port, socket)
		if !errors.Is(e, io.EOF) && ctx.Err() == nil && e != nil {
			t.Fatal("owned relay retirement", e)
		}
		return
	}
	if mode == "cancel" {
		blocked := make(chan error, 1)
		go func() { _, e := port.Read(buffer); blocked <- e }()
		cancel()
		select {
		case e := <-blocked:
			if e == nil {
				t.Fatal("cancelled read succeeded")
			}
		case <-time.After(time.Second):
			t.Fatal("cancelled port did not retire")
		}
		return
	}
	if mode == "truncated" || mode == "foreign" || mode == "ancillary" {
		_, e := port.Read(buffer)
		expected := "MCRF_PACKET_TRUNCATED"
		if mode == "foreign" {
			expected = "MCRF_PACKET_IP_OWNER"
		}
		if e == nil || !strings.Contains(e.Error(), expected) {
			t.Fatal("invalid packet not rejected", e)
		}
		return
	}
	for i := 0; i < 3; i++ {
		n, e := port.Read(buffer)
		if e != nil {
			t.Fatal(e)
		}
		packet := append([]byte(nil), buffer[:n]...)
		copy(packet[12:16], buffer[16:20])
		copy(packet[16:20], buffer[12:16])
		if n, e = port.Write(packet); e != nil || n != len(packet) {
			t.Fatal("reply", e)
		}
	}
	if _, e := port.Read(buffer); e == nil {
		t.Fatal("closed adopted peer read succeeded")
	}
}

func receiveOriginalPacketFD(t *testing.T, connection *net.UnixConn, pid int) *net.UnixConn {
	t.Helper()
	_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
	marker := make([]byte, 1)
	oob := make([]byte, unix.CmsgSpace(16))
	n, oobn, flags, _, err := connection.ReadMsgUnix(marker, oob)
	if err != nil || n != 1 || marker[0] != 'P' || flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 {
		t.Fatal("private FD marker", err)
	}
	messages, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		t.Fatal(err)
	}
	var received []int
	for _, message := range messages {
		fds, e := unix.ParseUnixRights(&message)
		if e != nil {
			t.Fatal(e)
		}
		received = append(received, fds...)
	}
	if len(received) != 1 {
		for _, fd := range received {
			_ = unix.Close(fd)
		}
		t.Fatal("not exactly one descriptor")
	}
	file := os.NewFile(uintptr(received[0]), "adopted-original-packets")
	defer file.Close()
	credentials, err := unix.GetsockoptUcred(received[0], unix.SOL_SOCKET, unix.SO_PEERCRED)
	if err != nil || int(credentials.Pid) != pid || int(credentials.Uid) != os.Getuid() {
		t.Fatal("packet socket creator identity", err)
	}
	typ, err := unix.GetsockoptInt(received[0], unix.SOL_SOCKET, unix.SO_TYPE)
	if err != nil || typ != unix.SOCK_SEQPACKET {
		t.Fatal("packet socket type", err)
	}
	peer, err := net.FileConn(file)
	if err != nil {
		t.Fatal(err)
	}
	return peer.(*net.UnixConn)
}

func originalPacketControlFixture(t *testing.T, mode string) (*exec.Cmd, *net.UnixConn, *net.UnixConn, OriginalPacketControl, *bytes.Buffer) {
	t.Helper()
	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		t.Fatal(err)
	}
	control := OriginalPacketControl{Nonce: hex.EncodeToString(nonce), Generation: 17, ParentPID: os.Getpid()}
	control.Name = "mcrf.carrier." + control.Nonce
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + control.Name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	_ = listener.SetDeadline(time.Now().Add(3 * time.Second))
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(executable, "-test.run=^TestMcrfOriginalPacketPortChild$", "-test.timeout=8s")
	value, _ := json.Marshal(control)
	command.Env = append(os.Environ(), "MCRF_ORIGINAL_PACKET_CHILD="+string(value))
	command.Env = append(command.Env, "MCRF_PROCESS_OWNER="+control.Nonce)
	if mode == "nonce" {
		command.Env = append(command.Env, "MCRF_ORIGINAL_PACKET_REJECT=1")
	}
	command.Env = append(command.Env, "MCRF_ORIGINAL_PACKET_TEST_MODE="+mode)
	output := new(bytes.Buffer)
	command.Stdout = output
	command.Stderr = output
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if command.ProcessState == nil {
			_ = command.Process.Kill()
			_ = command.Wait()
		}
	})
	channel, err := listener.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = channel.Close() })
	peer := receiveOriginalPacketFD(t, channel, command.Process.Pid)
	t.Cleanup(func() { _ = peer.Close() })
	line, err := bufio.NewReader(io.LimitReader(channel, 513)).ReadString('\n')
	if err != nil || len(line) > 512 {
		t.Fatal("metadata", err)
	}
	var metadata map[string]any
	if err = json.Unmarshal([]byte(line), &metadata); err != nil || len(metadata) != 6 || metadata["nonce"] != control.Nonce || metadata["role"] != "original_ip_stack" || metadata["local"] != "198.18.0.1" || metadata["generation"] != float64(control.Generation) || metadata["mtu"] != float64(1300) || metadata["schema"] != float64(1) {
		t.Fatal("owned metadata mismatch", err)
	}
	return command, channel, peer, control, output
}

func TestMcrfOriginalPacketPortHandshakePreservesPacketsAndFragments(t *testing.T) {
	command, channel, peer, control, output := originalPacketControlFixture(t, "normal")
	packets := make([][]byte, 3)
	for i := range packets {
		packet := make([]byte, 36+i*8)
		packet[0] = 0x45
		packet[2] = byte(len(packet) >> 8)
		packet[3] = byte(len(packet))
		packet[8] = 64
		packet[9] = 17
		copy(packet[12:16], []byte{198, 18, 0, 1})
		copy(packet[16:20], []byte{198, 18, 0, 2})
		for j := 20; j < len(packet); j++ {
			packet[j] = byte(j + i)
		}
		if i == 1 {
			packet[6] = 0x60 // Pinned gVisor preserves DF in locally emitted fragments.
		}
		if i == 2 {
			packet[6] = 0x40
			packet[7] = 3
		}
		packets[i] = packet
	}
	if _, err := peer.Write(packets[0]); err != nil {
		t.Fatal(err)
	}
	_ = peer.SetReadDeadline(time.Now().Add(40 * time.Millisecond))
	buffer := make([]byte, 1984)
	if _, err := peer.Read(buffer); err == nil {
		t.Fatal("packet processed before adoption ACK")
	}
	if _, err := fmt.Fprintf(channel, "MCRF_PACKET_OWNED|%s|%d\n", control.Nonce, control.Generation); err != nil {
		t.Fatal(err)
	}
	_ = peer.SetDeadline(time.Now().Add(3 * time.Second))
	for i, packet := range packets {
		if i > 0 {
			if _, err := peer.Write(packet); err != nil {
				t.Fatal(err)
			}
		}
		expected := append([]byte(nil), packet...)
		copy(expected[12:16], packet[16:20])
		copy(expected[16:20], packet[12:16])
		n, err := peer.Read(buffer)
		if err != nil || !bytes.Equal(buffer[:n], expected) {
			t.Fatal("original IP/fragment bytes changed", err)
		}
	}
	_ = peer.Close()
	if err := command.Wait(); err != nil {
		t.Fatal("owned child lifecycle", err, output.String())
	}
}

func TestMcrfOriginalPacketPortRejectsWrongAdoptionNonce(t *testing.T) {
	command, channel, peer, _, output := originalPacketControlFixture(t, "nonce")
	if _, err := io.WriteString(channel, "MCRF_PACKET_OWNED|00000000000000000000000000000000|17\n"); err != nil {
		t.Fatal(err)
	}
	if err := command.Wait(); err != nil {
		t.Fatal("rejection child", err, output.String())
	}
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := peer.Read(make([]byte, 1984)); err == nil {
		t.Fatal("rejected FD remained live")
	}
}

func TestMcrfOriginalPacketPortCancellationAndInvalidPackets(t *testing.T) {
	for _, mode := range []string{"cancel", "truncated", "foreign", "ancillary"} {
		t.Run(mode, func(t *testing.T) {
			command, channel, peer, control, output := originalPacketControlFixture(t, mode)
			if _, e := fmt.Fprintf(channel, "MCRF_PACKET_OWNED|%s|%d\n", control.Nonce, control.Generation); e != nil {
				t.Fatal(e)
			}
			if mode != "cancel" {
				size := 36
				if mode == "truncated" {
					size = 1301
				}
				packet := make([]byte, size)
				packet[0] = 0x45
				packet[2] = byte(size >> 8)
				packet[3] = byte(size)
				packet[8] = 64
				packet[9] = 17
				copy(packet[12:16], []byte{198, 18, 0, 1})
				copy(packet[16:20], []byte{198, 18, 0, 2})
				if mode == "foreign" {
					packet[15] = 9
				}
				var e error
				if mode == "ancillary" {
					f, err := os.Open("/dev/null")
					if err != nil {
						t.Fatal(err)
					}
					_, _, e = peer.WriteMsgUnix(packet, unix.UnixRights(int(f.Fd())), nil)
					_ = f.Close()
				} else {
					_, e = peer.Write(packet)
				}
				if e != nil {
					t.Fatal(e)
				}
			}
			if e := command.Wait(); e != nil {
				t.Fatal("invalid packet child", e, output.String())
			}
			_ = peer.SetReadDeadline(time.Now().Add(time.Second))
			if _, e := peer.Read(make([]byte, 1984)); e == nil {
				t.Fatal("retired peer still live")
			}
		})
	}
}
