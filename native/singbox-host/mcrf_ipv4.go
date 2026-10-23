package route

import (
 "github.com/sagernet/sing-box/adapter"
 C "github.com/sagernet/sing-box/constant"
 M "github.com/sagernet/sing/common/metadata"
 "net/netip"
 "strings"
)

// Only explicitly marked IPv4-only adapters may replace a genuine IPv6 target.
// Resolve a selected group member without assuming all SOCKS exits are IPv4-only.
func mcrfIPv4OnlyOutput(out adapter.Outbound, manager adapter.OutboundManager) bool {
 for depth:=0;out!=nil&&depth<8;depth++ {
  if marked,ok:=out.(interface{McrfIPv4Only() bool});ok{return marked.McrfIPv4Only()}
  group,ok:=out.(interface{Now() string});if !ok||manager==nil{return false}
  next,found:=manager.Outbound(group.Now());if !found{return false};out=next
 }
 return false
}

func mcrfRecoverIPv4OnlyDestination(metadata *adapter.InboundContext,out adapter.Outbound,manager adapter.OutboundManager) bool {
 if metadata.InboundType!=C.TypeTun||!metadata.Destination.IsIPv6()||metadata.Destination.Addr.IsPrivate()||metadata.Destination.Addr.IsLoopback()||metadata.Destination.Addr.IsLinkLocalUnicast()||metadata.Destination.Addr.IsMulticast()||metadata.RouteOriginalDestination.IsValid()||!M.IsDomainName(metadata.Domain)||!strings.Contains(metadata.Domain,".")||!mcrfIPv4OnlyOutput(out,manager){return false}
 if _,err:=netip.ParseAddr(metadata.Domain);err==nil{return false}
 original:=metadata.Destination
 metadata.OriginDestination=original;metadata.RouteOriginalDestination=original
 metadata.Destination=M.Socksaddr{Fqdn:metadata.Domain,Port:original.Port}
 metadata.DestinationAddresses=nil;metadata.IPVersion=0
 // The existing NAT wrapper restores the client's original IPv6 UDP address.
 metadata.FakeIP=true
 return true
}
