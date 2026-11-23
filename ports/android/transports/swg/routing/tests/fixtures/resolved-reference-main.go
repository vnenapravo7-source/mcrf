// Read-only reference generator: run from the pinned selected primary module.
package main
import("context";"encoding/json";"net/netip";"os";"github.com/sagernet/sing-box/adapter";"github.com/sagernet/sing-box/log";"github.com/sagernet/sing-box/option";"github.com/sagernet/sing-box/route/rule";M "github.com/sagernet/sing/common/metadata")
type Case struct{Rule json.RawMessage `json:"rule"`; Domain bool `json:"domain"`; IPVersion int `json:"ipVersion"`; Addresses []string `json:"addresses"`; Match bool `json:"match"`}
func main(){
 rules:=[]string{`{"ip_cidr":"8.8.8.0/24"}`,`{"ip_cidr":"8.8.8.0/24","invert":true}`,`{"ip_is_private":true}`,`{"ip_is_private":true,"invert":true}`,`{"type":"logical","mode":"and","rules":[{"ip_cidr":"8.8.8.0/24"},{"ip_is_private":true,"invert":true}]}`,`{"type":"logical","mode":"or","rules":[{"ip_cidr":"8.8.8.0/24"},{"ip_is_private":true}]}`,`{"domain":"owned.invalid","ip_cidr":"8.8.8.0/24"}`,`{"source_ip_cidr":"10.89.0.0/24","ip_cidr":"8.8.8.0/24"}`,`{"ip_version":4}`,`{"ip_version":6}`,`{"type":"logical","mode":"and","invert":true,"rules":[{"ip_cidr":"8.8.8.0/24"},{"ip_is_private":true}]}`,`{"domain":"other.invalid","ip_cidr":"8.8.8.0/24","port":443}`}
 sets:=[][]string{{},{"8.8.8.8"},{"192.168.1.1"},{"8.8.8.8","9.9.9.9"},{"2001:db8::1"},{"8.8.8.8","10.0.0.1"}}
 rows:=[]Case{}
 for _,text:=range rules{var options option.Rule;if err:=options.UnmarshalJSONContext(context.Background(),[]byte(text));err!=nil{panic(err)};r,err:=rule.NewRule(context.Background(),log.NewNOPFactory().NewLogger("reference"),options,false);if err!=nil{panic(err)}
  for _,domain:=range []bool{false,true}{versions:=[]int{4};if domain{versions=[]int{0,4,6}}
   for _,version:=range versions{for _,addresses:=range sets{
    destination:=M.Socksaddr{Addr:netip.MustParseAddr("192.0.2.1"),Port:443};if domain{destination=M.Socksaddr{Fqdn:"owned.invalid",Port:443}}
    metadata:=adapter.InboundContext{Source:M.Socksaddr{Addr:netip.MustParseAddr("10.89.0.2"),Port:41234},Destination:destination,Domain:"owned.invalid",Network:"tcp",Protocol:"tls",IPVersion:uint8(version)}
    for _,address:=range addresses{metadata.DestinationAddresses=append(metadata.DestinationAddresses,netip.MustParseAddr(address))}
    rows=append(rows,Case{json.RawMessage(text),domain,version,addresses,r.Match(&metadata)})
   }}
  }
 }
 if err:=json.NewEncoder(os.Stdout).Encode(rows);err!=nil{panic(err)}
}
