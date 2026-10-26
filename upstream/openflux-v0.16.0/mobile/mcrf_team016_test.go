// SPDX-License-Identifier: GPL-3.0-or-later
package mobile
import("encoding/json";"testing";"github.com/p1neappleXpress/OpenFlux/share";"github.com/p1neappleXpress/OpenFlux/transport")
func TestMcrf016DeadlineAppliesOnlyToFluxFast(t *testing.T){s,err:=transport.NewSession(transport.PeerParameters{Capabilities:transport.CapabilityIPv4|transport.CapabilityTCP,MaxPacketSize:transport.MaxNegotiatedPacket},false);if err!=nil{t.Fatal(err)};if mcrf016ConfigureHandshake(s,[]sessionSpec{{Type:"direct"}}){t.Fatal("direct timeout changed")};if !mcrf016ConfigureHandshake(s,[]sessionSpec{{Type:"fluxfast"}}){t.Fatal("FluxFast deadline not applied")}}
func TestMcrf016FluxFastKeepsDialAndEncryptionContext(t *testing.T){
 c:=share.Config{Negotiate:true,Secret:"synthetic-secret-123456",Context:"synthetic-context",Transports:[]share.Transport{{Type:"fluxfast",Name:"udp-path",Dial:"127.0.0.1:32123",Priority:90}}}
 if err:=c.Validate();err!=nil{t.Fatal(err)}
 var got struct{Context string `json:"context"`;Transports []sessionSpec `json:"transports"`}
 if json.Unmarshal([]byte(sessionSpecsOf(c)),&got)!=nil||got.Context!=c.Context||len(got.Transports)!=1||got.Transports[0].Params["dial"]!=c.Transports[0].Dial||got.Transports[0].Type!="fluxfast"||got.Transports[0].Name!="udp-path"||got.Transports[0].Priority!=90{t.Fatal("wire profile identity lost")}
}
func TestMcrf016FluxFastNeedsNegotiationAndSecret(t *testing.T){
 for _,c:=range []share.Config{{Transports:[]share.Transport{{Type:"fluxfast",Dial:"127.0.0.1:32123"}}},{Negotiate:true,Transports:[]share.Transport{{Type:"fluxfast",Dial:"127.0.0.1:32123"}}}}{if c.Validate()==nil{t.Fatal("unencrypted fluxfast admitted")}}
}
