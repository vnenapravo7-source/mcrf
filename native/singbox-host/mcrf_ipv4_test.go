package route
import("context";"net";"net/netip";"testing";"github.com/sagernet/sing-box/adapter";C "github.com/sagernet/sing-box/constant";M "github.com/sagernet/sing/common/metadata")
type mcrfV4Out struct{marked bool}
func(o *mcrfV4Out)Type()string{return C.TypeSOCKS};func(o *mcrfV4Out)Tag()string{return "csqtt"};func(o *mcrfV4Out)Network()[]string{return []string{"tcp","udp"}};func(o *mcrfV4Out)Dependencies()[]string{return nil};func(o *mcrfV4Out)McrfIPv4Only()bool{return o.marked};func(o *mcrfV4Out)DialContext(context.Context,string,M.Socksaddr)(net.Conn,error){panic("test must never dial")};func(o *mcrfV4Out)ListenPacket(context.Context,M.Socksaddr)(net.PacketConn,error){panic("test must never dial")}
type mcrfV4Group struct{adapter.Outbound;target string};func(g *mcrfV4Group)Now()string{return g.target}
type mcrfV4Manager struct{adapter.OutboundManager;nodes map[string]adapter.Outbound};func(m *mcrfV4Manager)Outbound(tag string)(adapter.Outbound,bool){o,ok:=m.nodes[tag];return o,ok}
func TestMcrfIPv4OnlyDestination(t *testing.T){
 fresh:=func()adapter.InboundContext{return adapter.InboundContext{InboundType:C.TypeTun,Destination:M.Socksaddr{Addr:netip.MustParseAddr("2a00:1450:4010:c0e::9b"),Port:443},Domain:"i.ytimg.com"}}
 marked:=&mcrfV4Out{true};m:=fresh();if !mcrfRecoverIPv4OnlyDestination(&m,marked,nil)||m.Destination.Fqdn!="i.ytimg.com"||m.OriginDestination.Addr!=fresh().Destination.Addr||!m.FakeIP{t.Fatal("IPv4-only fallback failed")}
 m=fresh();if mcrfRecoverIPv4OnlyDestination(&m,&mcrfV4Out{false},nil){t.Fatal("generic SOCKS IP intent changed")}
 for _,ip:=range []string{"1.1.1.1","fd00::1","::1","fe80::1","ff02::1"}{m=fresh();m.Destination.Addr=netip.MustParseAddr(ip);if mcrfRecoverIPv4OnlyDestination(&m,marked,nil){t.Fatal("real IPv4/private IPv6 changed",ip)}}
 m=fresh();m.RouteOriginalDestination=m.Destination;if mcrfRecoverIPv4OnlyDestination(&m,marked,nil){t.Fatal("explicit override changed")};m=fresh();m.InboundType=C.TypeMixed;if mcrfRecoverIPv4OnlyDestination(&m,marked,nil){t.Fatal("mixed inbound changed")}
 for _,domain:=range []string{"","2a00::1","1.1.1.1","localhost","bad name"}{m=fresh();m.Domain=domain;if mcrfRecoverIPv4OnlyDestination(&m,marked,nil){t.Fatal("invalid domain changed",domain)}}
 manager:=&mcrfV4Manager{nodes:map[string]adapter.Outbound{"csqtt":marked}};m=fresh();if !mcrfRecoverIPv4OnlyDestination(&m,&mcrfV4Group{target:"csqtt"},manager){t.Fatal("group selection lost IPv4 capability")};manager.nodes["loop"]=&mcrfV4Group{target:"loop"};if mcrfIPv4OnlyOutput(manager.nodes["loop"],manager){t.Fatal("group cycle accepted")}
}
