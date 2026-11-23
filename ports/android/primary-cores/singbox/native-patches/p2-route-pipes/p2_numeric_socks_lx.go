//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"regexp"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const mcrfNumericSocksType="mcrf_numeric_socks"
var mcrfNumericSocksPassword=regexp.MustCompile(`^[0-9a-f]{64}$`)
type mcrfNumericSocksOptions struct {
	Server string `json:"server"`
	Port uint16 `json:"server_port"`
	Version string `json:"version"`
	Username string `json:"username"`
	Password string `json:"password"`
	Bind4 string `json:"inet4_bind_address"`
	Bind6 string `json:"inet6_bind_address"`
}
func McrfNumericSocksSchema()int32{return 1}
type mcrfNumericSocks struct {
	adapter.Outbound
	tag string
	lookup func(context.Context,string)([]netip.Addr,error)
}
func(p *mcrfNumericSocks)Type()string{return mcrfNumericSocksType}
func(p *mcrfNumericSocks)Tag()string{return p.tag}
func newMcrfNumericSocks(ctx context.Context,router adapter.Router,logger log.ContextLogger,tag string,o mcrfNumericSocksOptions)(adapter.Outbound,error){
	if !mcrfPipeTag.MatchString(tag)||o.Server!="127.0.0.1"||o.Port<1024||o.Version!="5"||o.Bind4!="127.0.0.1"||o.Bind6!="::1"||
		!mcrfPipeToken.MatchString(o.Username)||!mcrfNumericSocksPassword.MatchString(o.Password){return nil,errors.New("MCRF_NUMERIC_SOCKS_CONFIG")}
	dns:=service.FromContext[adapter.DNSRouter](ctx);if dns==nil{return nil,errors.New("MCRF_NUMERIC_SOCKS_DNS_REQUIRED")}
	bind4,bind6:=badoption.Addr(netip.MustParseAddr("127.0.0.1")),badoption.Addr(netip.IPv6Loopback())
	inner,err:=socks.NewOutbound(ctx,router,logger,tag,option.SOCKSOutboundOptions{
		DialerOptions:option.DialerOptions{AbstractDialerOptions:option.AbstractDialerOptions{Inet4BindAddress:&bind4,Inet6BindAddress:&bind6}},
		ServerOptions:option.ServerOptions{Server:o.Server,ServerPort:o.Port},Version:"5",Username:o.Username,Password:o.Password,
	})
	if err!=nil{return nil,errors.New("MCRF_NUMERIC_SOCKS_INIT")}
	return &mcrfNumericSocks{Outbound:inner,tag:tag,lookup:func(ctx context.Context,name string)([]netip.Addr,error){return dns.Lookup(ctx,name,adapter.DNSQueryOptions{})}},nil
}
func(p *mcrfNumericSocks)numbers(ctx context.Context,destination M.Socksaddr)(context.Context,[]netip.Addr,error){
	if ctx==nil||destination.Port==0{return ctx,nil,errors.New("MCRF_NUMERIC_SOCKS_DESTINATION")}
	ctx,metadata:=adapter.ExtendContext(ctx);metadata.Outbound=p.Tag();metadata.Destination=destination
	if destination.Addr.IsValid(){
		if destination.Addr.Is4In6(){return ctx,nil,errors.New("MCRF_NUMERIC_SOCKS_MAPPED")};return ctx,nil,nil
	}
	if !destination.IsDomain()||p.lookup==nil{return ctx,nil,errors.New("MCRF_NUMERIC_SOCKS_DNS_REQUIRED")}
	addresses,err:=p.lookup(ctx,destination.Fqdn)
	if err!=nil{return ctx,nil,errors.New("MCRF_NUMERIC_SOCKS_DNS_FAILED")}
	if len(addresses)==0{return ctx,nil,errors.New("MCRF_NUMERIC_SOCKS_DNS_EMPTY")}
	if len(addresses)>8{addresses=addresses[:8]}
	for _,address:=range addresses{if !address.IsValid()||address.Is4In6(){return ctx,nil,errors.New("MCRF_NUMERIC_SOCKS_DNS_INVALID")}}
	return ctx,addresses,nil
}
func(p *mcrfNumericSocks)DialContext(ctx context.Context,network string,destination M.Socksaddr)(net.Conn,error){
	child,addresses,err:=p.numbers(ctx,destination);if err!=nil{return nil,err}
	if len(addresses)==0{return p.Outbound.DialContext(child,network,destination)}
	return N.DialSerial(child,p.Outbound,network,destination,addresses)
}
func(p *mcrfNumericSocks)ListenPacket(ctx context.Context,destination M.Socksaddr)(net.PacketConn,error){
	child,addresses,err:=p.numbers(ctx,destination);if err!=nil{return nil,err}
	if len(addresses)==0{return p.Outbound.ListenPacket(child,destination)}
	packet,_,err:=N.ListenSerial(child,p.Outbound,destination,addresses);return packet,err
}
