package route
import("testing";"net/netip";"github.com/sagernet/sing-box/adapter";C "github.com/sagernet/sing-box/constant";R "github.com/sagernet/sing-box/route/rule";M "github.com/sagernet/sing/common/metadata")
func TestMcrfExternalFakeIP(t *testing.T){
 fresh:=func() adapter.InboundContext{return adapter.InboundContext{InboundType:C.TypeTun,Destination:M.Socksaddr{Addr:netip.MustParseAddr("198.18.1.99"),Port:443},Domain:"www.youtube.com"}}
 for _,kind:=range []string{"",C.TypeSOCKS,C.TypeSelector,C.TypeURLTest,C.TypeDirect,C.TypeWireGuard,C.TypeTrojan,C.TypeVLESS}{m:=fresh();if !mcrfRecoverExternalFakeIP(&m,kind)||m.Destination.Fqdn!="www.youtube.com"||m.Destination.Port!=443||!m.FakeIP||m.OriginDestination.Addr.String()!="198.18.1.99"{t.Fatal("fake IP not recovered",kind)};if mcrfRecoverExternalFakeIP(&m,kind){t.Fatal("recovery not idempotent")}}
 m:=fresh();if mcrfRecoverExternalFakeIP(&m,C.TypeBlock){t.Fatal("block dispatch changed")}
 for _,ip:=range []string{"fdfe:dcba:9876::c612:1","fdfe:dcba:9876::c613:ffff"}{m:=fresh();m.Destination.Addr=netip.MustParseAddr(ip);m.IPVersion=6;if !mcrfRecoverExternalFakeIP(&m,"")||!m.Destination.IsDomain()||m.IPVersion!=0||m.OriginDestination.Addr.String()!=ip{t.Fatal("IPv6 fake not recovered",ip)}}
 for _,ip:=range []string{"3.233.158.25","192.168.1.1","198.17.255.255","198.20.0.1","2001:db8::1","fdfe:dcba:9876::1","fdfe:dcba:9876::c614:1","fd00::123"}{m:=fresh();m.Destination.Addr=netip.MustParseAddr(ip);if mcrfRecoverExternalFakeIP(&m,C.TypeSOCKS){t.Fatal("real IP changed",ip)}}
 for _,domain:=range []string{"","1.1.1.1","bad name","localhost"}{m:=fresh();m.Domain=domain;if mcrfRecoverExternalFakeIP(&m,C.TypeSOCKS){t.Fatal("unsafe domain",domain)}}
 m=fresh();m.InboundType=C.TypeMixed;if mcrfRecoverExternalFakeIP(&m,C.TypeSOCKS){t.Fatal("explicit proxy IP intent changed")}
 m=fresh();m.RouteOriginalDestination=M.Socksaddr{Addr:netip.MustParseAddr("203.0.113.1"),Port:443};if mcrfRecoverExternalFakeIP(&m,C.TypeSOCKS){t.Fatal("explicit destination override changed")}
}

func TestMcrfExternalFakeIPRoutingAndUDP(t *testing.T){
 for _,ip:=range []string{"198.18.1.99","fdfe:dcba:9876::c612:163"}{
  m:=adapter.InboundContext{InboundType:C.TypeTun,Destination:M.Socksaddr{Addr:netip.MustParseAddr(ip),Port:443},Domain:"ya.ru",Network:"udp"}
  if R.NewIPIsPrivateItem(false).Match(&m){t.Fatal("synthetic address classified as private before routing",ip)}
  cidr:=netip.MustParsePrefix("0.0.0.0/0");if m.Destination.IsIPv6(){cidr=netip.MustParsePrefix("::/0")};item,err:=R.NewIPCIDRItem(false,[]string{cidr.String()});if err!=nil||!item.Match(&m){t.Fatal("explicit CIDR route lost before output selection",err)}
  if !mcrfRecoverExternalFakeIP(&m,""){t.Fatal("UDP recovery failed",ip)}
  if R.NewIPIsPrivateItem(false).Match(&m){t.Fatal("synthetic destination misrouted as private",ip)}
  domains,err:=R.NewDomainItem(nil,[]string{"ru"});if err!=nil||!domains.Match(&m){t.Fatal("explicit domain route lost",err)}
  nat:=newFakeIPNATPacketConn(nil,m.OriginDestination,m.Destination)
  real:=M.Socksaddr{Addr:netip.MustParseAddr("77.88.55.242"),Port:443}
  if got:=nat.rewriteWriteDestination(real);got.Addr!=m.OriginDestination.Addr||got.Port!=443{t.Fatal("UDP return lost synthetic family",got)}
  if got:=nat.rewriteReadDestination(m.OriginDestination);got.Fqdn!="ya.ru"||got.Port!=443{t.Fatal("UDP request lost domain",got)}
 }
}
type mcrfDNSMap struct{adapter.DNSRouter;name string};func(d *mcrfDNSMap)LookupReverseMapping(netip.Addr)(string,bool){return d.name,d.name!=""}
func TestMcrfExternalDNSName(t *testing.T){
 m:=adapter.InboundContext{InboundType:C.TypeTun,Destination:M.Socksaddr{Addr:netip.MustParseAddr("198.18.2.1"),Port:443},Domain:"cloudflare-ech.com"};original:=m.Destination
 if !mcrfRestoreExternalDNSName(&m,&mcrfDNSMap{name:"example.com"})||m.Domain!="example.com"||m.Destination!=original{t.Fatal("DNS name not restored or CIDR intent changed")}
 m.InboundType=C.TypeMixed;if mcrfRestoreExternalDNSName(&m,&mcrfDNSMap{name:"example.com"}){t.Fatal("proxy explicit IP changed")}
 m.InboundType=C.TypeTun;m.Destination.Addr=netip.MustParseAddr("1.1.1.1");if mcrfRestoreExternalDNSName(&m,&mcrfDNSMap{name:"example.com"}){t.Fatal("real IP reverse mapping altered sniffed domain")}
}
