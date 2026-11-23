//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"net"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	M "github.com/sagernet/sing/common/metadata"
)

// Model registry/lifecycle only. Real stack/channel cases live separately;
// these fixtures never claim a native packet, WG handshake or Android cleanup.
type mcrfPipeModel struct{closed bool;closes,failClose int}
func(p *mcrfPipeModel) alive()bool{return !p.closed}
func(p *mcrfPipeModel) Close()error{
	if p.failClose>0{p.failClose--;return errors.New("owned close pending")}
	if !p.closed{p.closed=true;p.closes++};return nil
}
func(p *mcrfPipeModel) DialContext(context.Context,string,M.Socksaddr)(net.Conn,error){return nil,errors.New("model cannot dial")}
func(p *mcrfPipeModel) ListenPacket(context.Context,M.Socksaddr)(net.PacketConn,error){return nil,errors.New("model cannot dial")}
func mcrfRegistryModel()(*mcrfPipeRegistry,*mcrfPipeModel,*mcrfPipeModel){
	ctx,cancel:=context.WithCancel(context.Background());one,two:=&mcrfPipeModel{},&mcrfPipeModel{}
	r:=&mcrfPipeRegistry{generation:7,ctx:ctx,cancel:cancel,slots:map[string]*mcrfPipeSlot{
		"one":{tag:"one",token:"11111111111111111111111111111111",client:one},
		"two":{tag:"two",token:"22222222222222222222222222222222",client:two}}}
	return r,one,two
}
func TestMcrfPipeRegistryBindsExactGenerationTagAndNonce(t *testing.T){
	r,_,_:=mcrfRegistryModel();defer r.shutdown(7)
	for _,request:=range []struct{generation int64;tag,token string}{{8,"one",r.slots["one"].token},{7,"two",r.slots["one"].token},{7,"missing",r.slots["one"].token}}{
		if _,err:=r.match(request.generation,request.tag,request.token,false);err==nil{t.Fatal("wrong private owner admitted")}
	}
	if _,err:=r.match(7,"one",r.slots["one"].token,true);err!=nil{t.Fatal(err)}
	if _,err:=r.match(7,"one",r.slots["one"].token,true);err==nil{t.Fatal("second runtime claim admitted")}
}
func TestMcrfPipeValidationCloseDoesNotConsumeRuntimeOwner(t *testing.T){
	r,one,_:=mcrfRegistryModel();defer r.shutdown(7)
	newWrapper:=func()*mcrfPipeOutbound{return &mcrfPipeOutbound{Adapter:outbound.NewAdapter(mcrfPacketPipeType,"one",[]string{"tcp","udp"},nil),registry:r,options:mcrfPipeOutboundOptions{7,r.slots["one"].token}}}
	for i:=0;i<3;i++{wrapper:=newWrapper();if err:=wrapper.Close();err!=nil{t.Fatal(err)}}
	if one.closed||one.closes!=0||r.slots["one"].claimed{t.Fatal("validation retired/consumed the runtime packet owner")}
	runtime:=newWrapper();if err:=runtime.Start(adapter.StartStateStart);err!=nil{t.Fatal(err)}
	if !r.slots["one"].claimed||!r.sealed{t.Fatal("runtime owner not sealed")}
	if err:=runtime.Close();err!=nil{t.Fatal(err)}
	if one.closes!=1{t.Fatal("runtime packet owner not actually closed by its wrapper")}
}
func TestMcrfPipeFailedServerNeverFallsBackOrResurrects(t *testing.T){
	r,one,two:=mcrfRegistryModel();defer r.shutdown(7)
	if err:=r.fail(7,"one",r.slots["one"].token);err!=nil{t.Fatal(err)}
	if _,err:=r.match(7,"one",r.slots["one"].token,false);err==nil{t.Fatal("failed slot resurrected")}
	if _,err:=r.match(7,"two",r.slots["two"].token,true);err!=nil{t.Fatal("other selected server was changed")}
	if !one.closed||two.closed||len(r.slots)!=2{t.Fatal("lost exact tombstone or unrelated owner")}
}
func TestMcrfPipeCloseFailureRetainsOwnerAndDoesNotBlockOtherRetirement(t *testing.T){
	r,one,two:=mcrfRegistryModel();one.failClose=1
	if err:=r.shutdown(7);err==nil{t.Fatal("pending close acknowledged")}
	if one.closed||!two.closed||r.slots["one"].client!=one||!r.closed{t.Fatal("exact failed owner lost")}
	if _,err:=r.match(7,"one",r.slots["one"].token,false);err==nil{t.Fatal("pending closed registry admitted traffic")}
	if err:=r.shutdown(7);err!=nil{t.Fatal(err)}
	if one.closes!=1||two.closes!=1{t.Fatal("retirement lost/reclosed an actual owner")}
}
func TestMcrfPipeFailedConstructorNeverLeavesATypedNilOwner(t *testing.T){
	r,_,_:=mcrfRegistryModel();slot:=&mcrfPipeSlot{tag:"pending",token:"33333333333333333333333333333333"};r.slots[slot.tag]=slot
	var missing *mcrfPipeStack
	if r.finishAttach(slot,missing,errors.New("owned constructor failure")){t.Fatal("failed constructor admitted")}
	if slot.client!=nil||!slot.failed{t.Fatal("typed nil or lost failed-slot tombstone")}
	if err:=r.shutdown(7);err!=nil{t.Fatal("failed constructor broke unrelated cleanup")}
}
