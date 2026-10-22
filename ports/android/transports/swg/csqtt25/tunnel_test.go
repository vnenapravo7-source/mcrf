package csqtt25

import (
	"strings"
	"testing"
	"wg-turn-client/internal/csqtt"
)

func Test25TunnelOptionalPolicyDoesNotBecomeRevisionOrRouting(t *testing.T){
	for _,extra:=range []string{"",":workers=20",":pad=32",":eyJkb21haW5zIjpbXX0="}{
		frame,err:=NormalizeTunnel("TUNCONF:10.66.67.42:1.1.1.1,8.8.8.8:19000:stream-v2"+extra)
		if err!=nil{t.Fatal(err)}
		parsed,err:=csqtt.ParseTunnelConfig(frame)
		if err!=nil||parsed.ClientPort!=19000||parsed.Revision!="stream-v2"||len(parsed.DNS)!=2{t.Fatal("panel frame cannot enter shared IP bridge")}
	}
	if _,err:=NormalizeTunnel("TUNCONF:10.66.67.42:1.1.1.1:0:1");err!=nil {t.Fatal("numeric revision rejected")}
}
func Test25TunnelIPv6DnsAndMalformedFramesAreHandledWithoutSecrets(t *testing.T){
	frame,err:=NormalizeTunnel("TUNCONF:10.66.67.42:1.1.1.1,2606:4700:4700::1111:0:stream-v2:workers=20")
	if err!=nil{t.Fatal(err)}
	parsed,err:=csqtt.ParseTunnelConfig(frame);if err!=nil||len(parsed.DNS)!=2||!parsed.DNS[1].Is6(){t.Fatal("IPv6 DNS damaged by colon splitting")}
	for _,raw:=range []string{"fixture-secret","TUNCONF:0.0.0.0:1.1.1.1:1:stream-v2:fixture-secret","TUNCONF:10.0.0.1:fixture-secret:1:stream-v2","TUNCONF:10.0.0.1:1.1.1.1:65536:stream-v2"}{
		_,err=NormalizeTunnel(raw);if err==nil||strings.Contains(err.Error(),"fixture-secret"){t.Fatal("invalid frame accepted or leaked")}
	}
}
