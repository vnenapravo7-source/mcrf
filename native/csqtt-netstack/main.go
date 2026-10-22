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
 mtu := flag.Int("mtu", 1300, "tunnel IP MTU")
 flag.Parse()
 ctx, cancel := context.WithCancel(context.Background())
 defer cancel()
 go func() { scan := bufio.NewScanner(os.Stdin); for scan.Scan() { if strings.TrimSpace(scan.Text()) == "STOP" { break } }; cancel() }()
 if err := runWithMTU(ctx, *peer, *ip, *dns, *listen, *mtu); err != nil && ctx.Err() == nil { log.Print("FATAL: local IP adapter failed"); os.Exit(1) }
}

func run(ctx context.Context, peer, assignedIP, dns, listen string) error {
 return runWithMTU(ctx,peer,assignedIP,dns,listen,1300)
}
func runWithMTU(ctx context.Context, peer, assignedIP, dns, listen string, mtu int) error {
 if mtu < 576 || mtu > 9000 { return fmt.Errorf("invalid IP MTU") }
 endpoint, err := net.ResolveUDPAddr("udp4", peer)
 if err != nil || !endpoint.IP.IsLoopback() || endpoint.Port == 0 { return fmt.Errorf("raw endpoint must be loopback") }
 addr, err := netip.ParseAddr(assignedIP); if err != nil || !addr.Is4() { return fmt.Errorf("invalid tunnel IP") }
 resolver, err := netip.ParseAddr(dns); if err != nil || !resolver.Is4() { return fmt.Errorf("invalid tunnel DNS") }
 if _, _, err := validateProxyListenAddress(listen, "", false); err != nil { return err }
 device, network, err := netstack.CreateNetTUN([]netip.Addr{addr}, []netip.Addr{resolver}, mtu)
 if err != nil { return err }
 var once sync.Once; closeDevice := func() { once.Do(func() { device.Close() }) }; defer closeDevice()
 socket, err := net.DialUDP("udp4", nil, endpoint); if err != nil { return err }; defer socket.Close()
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
