package wireguardfixtures

// Test-only loopback peers using the SAME pinned AWG implementation as WarpScout.
// This is never linked into the application or an engine package.
import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

func fixturePort(t *testing.T) int {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

func TestWireGuardNativeLoopbackHTTP(t *testing.T) {
	for _, fixture := range []struct{ name, params string }{
		{"WG", ""},
		{"AWG1", "jc=3\njmin=10\njmax=30\ns1=16\ns2=20\nh1=12341\nh2=12342\nh3=12343\nh4=12344\n"},
		{"AWG2", "jc=3\njmin=10\njmax=30\ns1=16\ns2=20\ns3=12\ns4=12\nh1=12341-12350\nh2=12441-12450\nh3=12541-12550\nh4=12641-12650\ni1=<b 0x01020304><r 8>\n"},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			serverKey, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				t.Fatal(err)
			}
			clientKey, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				t.Fatal(err)
			}
			serverTun, serverNet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1")}, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			server := device.NewDevice(serverTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
			defer server.Close()
			port := fixturePort(t)
			err = server.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\n%sreplace_peers=true\npublic_key=%s\nallowed_ip=10.77.0.2/32\n", hex.EncodeToString(serverKey.Bytes()), port, fixture.params, hex.EncodeToString(clientKey.PublicKey().Bytes())))
			if err != nil {
				t.Fatal(err)
			}
			if err = server.Up(); err != nil {
				t.Fatal(err)
			}
			listener, err := serverNet.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 77, 0, 1), Port: 8080})
			if err != nil {
				t.Fatal(err)
			}
			body := strings.Repeat("mcrf-isolated-wireguard-fixture-", 4096)
			httpServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, body) }), ReadHeaderTimeout: 3 * time.Second}
			defer httpServer.Close()
			go httpServer.Serve(listener)
			clientTun, clientNet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.2")}, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			client := device.NewDevice(clientTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
			defer client.Close()
			err = client.IpcSet(fmt.Sprintf("private_key=%s\n%sreplace_peers=true\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=10.77.0.1/32\n", hex.EncodeToString(clientKey.Bytes()), fixture.params, hex.EncodeToString(serverKey.PublicKey().Bytes()), port))
			if err != nil {
				t.Fatal(err)
			}
			if err = client.Up(); err != nil {
				t.Fatal(err)
			}
			transport := &http.Transport{DialContext: clientNet.DialContext, DisableKeepAlives: true}
			defer transport.CloseIdleConnections()
			httpClient := &http.Client{Transport: transport, Timeout: 10 * time.Second}
			for attempt := 0; attempt < 2; attempt++ {
				if attempt == 1 {
					if err = client.Down(); err != nil { t.Fatal(err) }
					if err = client.Up(); err != nil { t.Fatal(err) }
				}
				response, err := httpClient.Get("http://10.77.0.1:8080/")
				if err != nil {
					t.Fatal(err)
				}
				bytes, err := io.ReadAll(io.LimitReader(response.Body, int64(len(body)+1)))
				response.Body.Close()
				if err != nil || string(bytes) != body {
					t.Fatal("tunnel HTTP body mismatch")
				}
			}
			state, err := client.IpcGet()
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(state, "last_handshake_time_sec=0\n") || !strings.Contains(state, "rx_bytes=") {
				t.Fatal("handshake/traffic counters not observed")
			}
		})
	}
}

func TestWireGuardNativeInvalidObfuscation(t *testing.T) {
	tun, _, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.2")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	for _, bad := range []string{"h1=4294967296\n", "i1=<unknown 12>\n", "s1=-1\n"} {
		if dev.IpcSet(bad) == nil {
			t.Fatal("invalid obfuscation accepted")
		}
	}
}
