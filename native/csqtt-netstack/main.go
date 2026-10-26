// MCRF loopback-only raw IP / SOCKS adapter. No TURN, VK, DTLS or WireGuard protocol.
package main

import (
 "bufio"
 "context"
 "flag"
 "fmt"
 "log"
 "net"
 "net/netip"
 "os"
 "strings"
 "sync"

 "golang.zx2c4.com/wireguard/tun/netstack"
)

func main() {
 peer := flag.String("csqtt-raw-peer", "", "loopback raw IP endpoint")
 ip := flag.String("csqtt-ip", "", "assigned IPv4")
 dns := flag.String("csqtt-dns", "", "tunnel DNS IPv4")
 listen := flag.String("socks-listen", "127.0.0.1:1080", "loopback SOCKS5 endpoint")
 mtu := flag.Int("mtu", 1280, "tunnel IP MTU (raw transport ceiling: 1280)")
 csqtt := flag.Bool("csqtt-transport", false, "CSQTT packet-framing transport: bridge MTU up to 1400")
 flag.Parse()
 ctx, cancel := context.WithCancel(context.Background())
 defer cancel()
 go func() { scan := bufio.NewScanner(os.Stdin); for scan.Scan() { if strings.TrimSpace(scan.Text()) == "STOP" { break } }; cancel() }()
 if err := runWithTransportMTU(ctx, *peer, *ip, *dns, *listen, *mtu, *csqtt); err != nil && ctx.Err() == nil { log.Print("FATAL: local IP adapter failed"); os.Exit(1) }
}

func run(ctx context.Context, peer, assignedIP, dns, listen string) error {
 return runWithMTU(ctx,peer,assignedIP,dns,listen,1280)
}
// Raw IP is bursty even when average bandwidth is modest. Windows' default
// UDP receive buffer loses a TCP window before the netstack reader runs.
// These are per-adapter socket buffers, not per-SOCKS-client allocations.
const rawSocketBufferBytes = 4 << 20

// Preserve the legacy adapter policy for QWDTT and old callers. CSQTT opts
// into a separate 1400-byte IP MTU and lets its existing wire protocol frame
// and reassemble packets >1280, avoiding IP fragmentation of game UDP.
func rawTransportMTU(requested int) int {
 if requested > 1280 { return 1280 }
 return requested
}

func configureRawSocket(socket *net.UDPConn) error {
 if err := socket.SetReadBuffer(rawSocketBufferBytes); err != nil { return fmt.Errorf("raw UDP receive buffer: %w", err) }
 if err := socket.SetWriteBuffer(rawSocketBufferBytes); err != nil { return fmt.Errorf("raw UDP send buffer: %w", err) }
 return nil
}
func runWithMTU(ctx context.Context, peer, assignedIP, dns, listen string, mtu int) error {
 return runWithTransportMTU(ctx,peer,assignedIP,dns,listen,mtu,false)
}
func runWithTransportMTU(ctx context.Context, peer, assignedIP, dns, listen string, mtu int, csqtt bool) error {
 if mtu < 576 || mtu > 9000 { return fmt.Errorf("invalid IP MTU") }
 if csqtt { if mtu>1400 { mtu=1400 } } else { mtu = rawTransportMTU(mtu) }
 endpoint, err := net.ResolveUDPAddr("udp4", peer)
 if err != nil || !endpoint.IP.IsLoopback() || endpoint.Port == 0 { return fmt.Errorf("raw endpoint must be loopback") }
 addr, err := netip.ParseAddr(assignedIP); if err != nil || !addr.Is4() { return fmt.Errorf("invalid tunnel IP") }
 resolver, err := netip.ParseAddr(dns); if err != nil || !resolver.Is4() { return fmt.Errorf("invalid tunnel DNS") }
 if _, _, err := validateProxyListenAddress(listen, "", false); err != nil { return err }
 device, network, err := netstack.CreateNetTUN([]netip.Addr{addr}, []netip.Addr{resolver}, mtu)
 if err != nil { return err }
 var once sync.Once; closeDevice := func() { once.Do(func() { device.Close() }) }; defer closeDevice()
 socket, err := net.DialUDP("udp4", nil, endpoint); if err != nil { return err }; defer socket.Close()
 if err := configureRawSocket(socket); err != nil { return err }
 ctx, cancel := context.WithCancel(ctx); defer cancel()
 stop := context.AfterFunc(ctx, func() { socket.Close(); closeDevice() }); defer stop()
 go func() { defer cancel(); buffers := [][]byte{make([]byte, 65536)}; sizes := make([]int, 1)
  for { n, err := device.Read(buffers, sizes, 0); if err != nil { return }; for i := 0; i < n; i++ { if _, err := socket.Write(buffers[i][:sizes[i]]); err != nil { return } } }
 }()
 go func() { defer cancel(); buffer := make([]byte, 65536)
  for { n, err := socket.Read(buffer); if err != nil { return }; if _, err := device.Write([][]byte{buffer[:n]}, 0); err != nil { return } }
 }()
 return runSocks5Server(ctx, listen, network, false, "", "", true, "", func(address string) { log.Printf("PROXY_READY|csqtt|%s", address) })
}
