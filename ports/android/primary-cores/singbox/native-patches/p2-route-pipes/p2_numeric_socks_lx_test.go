//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

// Resolver/metadata models only, not actual SOCKS/Steer/Android traffic proof.
func TestMcrfPipeNumericSocksNeverLooksUpNumericTargets(t *testing.T){
	p:=&mcrfNumericSocks{tag:"owned",lookup:func(context.Context,string)([]netip.Addr,error){t.Fatal("numeric target performed DNS");return nil,nil}}
	for _,ip:=range []string{"192.0.2.1","2001:db8::1"}{
		ctx,addresses,err:=p.numbers(context.Background(),M.Socksaddr{Addr:netip.MustParseAddr(ip),Port:443})
		if err!=nil||len(addresses)!=0||adapter.ContextFrom(ctx).Destination.Addr.String()!=ip{t.Fatal("numeric target/metadata changed")}
	}
}
func TestMcrfPipeNumericSocksUsesDispatcherDnsWithMetadata(t *testing.T){
	calls:=0
	p:=&mcrfNumericSocks{tag:"owned"}
	p.lookup=func(ctx context.Context,name string)([]netip.Addr,error){calls++;metadata:=adapter.ContextFrom(ctx)
		if name!="owned.example.test"||metadata.Outbound!="owned"||metadata.Destination.Port!=443{t.Fatal("DNS lost dispatcher metadata")}
		return []netip.Addr{netip.MustParseAddr("192.0.2.1"),netip.MustParseAddr("2001:db8::1")},nil}
	_,addresses,err:=p.numbers(context.Background(),M.Socksaddr{Fqdn:"owned.example.test",Port:443})
	if err!=nil||len(addresses)!=2||calls!=1{t.Fatal("dispatcher DNS dual-stack result missing")}
}
func TestMcrfPipeNumericSocksDnsFailureNeverFallsBack(t *testing.T){
	p:=&mcrfNumericSocks{tag:"owned",lookup:func(context.Context,string)([]netip.Addr,error){return nil,errors.New("owned resolver refused")}}
	if _,_,err:=p.numbers(context.Background(),M.Socksaddr{Fqdn:"owned.example.test",Port:443});err==nil{t.Fatal("resolver failure became implicit fallback")}
	p.lookup=func(context.Context,string)([]netip.Addr,error){return []netip.Addr{netip.MustParseAddr("::ffff:192.0.2.1")},nil}
	if _,_,err:=p.numbers(context.Background(),M.Socksaddr{Fqdn:"owned.example.test",Port:443});err==nil{t.Fatal("mapped result accepted by numeric-only native boundary")}
}
