package route

import (
 "net/netip"
 "strings"
 "github.com/sagernet/sing-box/adapter"
 C "github.com/sagernet/sing-box/constant"
 M "github.com/sagernet/sing/common/metadata"
)

func mcrfRestoreExternalDNSName(metadata *adapter.InboundContext, dns adapter.DNSRouter) bool {
 if metadata.InboundType!=C.TypeTun||!adapter.McrfIsExternalFakeIP(metadata.Destination.Addr){return false}
 name,ok:=dns.LookupReverseMapping(metadata.Destination.Addr)
 if !ok||!M.IsDomainName(name)||!strings.Contains(name,"."){return false}
 if _,err:=netip.ParseAddr(name);err==nil{return false}
 metadata.Domain=name
 return true
}

// An external resolver's fake-IP mapping is not in this core's DNS cache.
// Recover only after the route decision, preserving explicit CIDR rules.
// All output types receive a usable domain, without selecting another output.
func mcrfRecoverExternalFakeIP(metadata *adapter.InboundContext, outboundType string) bool {
 if metadata.InboundType != C.TypeTun || !metadata.Destination.IsIP() || !adapter.McrfIsExternalFakeIP(metadata.Destination.Addr) || !M.IsDomainName(metadata.Domain) || !strings.Contains(metadata.Domain,".") || metadata.RouteOriginalDestination.IsValid() { return false }
 if _, err := netip.ParseAddr(metadata.Domain); err == nil { return false }
 if outboundType == C.TypeBlock { return false }
 original := metadata.Destination
 metadata.OriginDestination = original
 metadata.RouteOriginalDestination = original
 metadata.Destination = M.Socksaddr{Fqdn: metadata.Domain,Port: original.Port}
 metadata.DestinationAddresses = nil
 metadata.IPVersion = 0
 // Reuse the existing UDP fake-IP NAT wrapper for QUIC return addresses.
 metadata.FakeIP = true
 return true
}
