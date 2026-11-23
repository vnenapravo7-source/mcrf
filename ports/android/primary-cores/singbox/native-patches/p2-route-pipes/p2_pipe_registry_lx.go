//go:build with_gvisor && (linux || android)

package libbox

import (
	"context"
	"errors"
	"net"
	"regexp"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

const mcrfPacketPipeType="mcrf_packet_pipe"
var mcrfPipeToken=regexp.MustCompile(`^[0-9a-f]{32}$`)
var mcrfPipeTag=regexp.MustCompile(`^[A-Za-z0-9._-]{1,96}$`)

type mcrfPipeClient interface {
	DialContext(context.Context,string,M.Socksaddr)(net.Conn,error)
	ListenPacket(context.Context,M.Socksaddr)(net.PacketConn,error)
	Close()error
	alive()bool
}
func(p *mcrfPipeStack) alive()bool{return !p.closed.Load()&&p.ctx.Err()==nil}
type mcrfPipeSlot struct{tag,token string;client mcrfPipeClient;claimed,failed bool}
type mcrfPipeRegistry struct{
	lock sync.Mutex
	generation int64
	sealed,closed bool
	ctx context.Context
	cancel context.CancelFunc
	slots map[string]*mcrfPipeSlot
	attaching sync.WaitGroup
}
var mcrfNativePipes=mcrfPipeRegistry{}

// Mechanical interface version, NOT runtime/capability acceptance. Java host
// must also require its exact source-bound module capability and private role.
func McrfPacketPipeSchema()int32{return 1}
func McrfAttachPacketPipe(generation int64,tag,token string,borrowedFD,ownerPID,mtu int32,addresses string)error{
	if generation<=0||!mcrfPipeTag.MatchString(tag)||!mcrfPipeToken.MatchString(token){return errors.New("MCRF_PIPE_IDENTITY")}
	spec,err:=parseMcrfPipeSpec(int(mtu),int(ownerPID),addresses);if err!=nil{return err}
	r:=&mcrfNativePipes
	r.lock.Lock()
	if r.closed||r.sealed||(r.generation!=0&&r.generation!=generation)||len(r.slots)>=8||r.slots[tag]!=nil{r.lock.Unlock();return errors.New("MCRF_PIPE_FRESH_REGISTRY")}
	for _,old:=range r.slots{if old.token==token{r.lock.Unlock();return errors.New("MCRF_PIPE_NONCE_REUSED")}}
	if r.generation==0{r.generation=generation;r.ctx,r.cancel=context.WithCancel(context.Background());r.slots=make(map[string]*mcrfPipeSlot)}
	slot:=&mcrfPipeSlot{tag:tag,token:token};r.slots[tag]=slot;r.attaching.Add(1);ctx:=r.ctx;r.lock.Unlock()
	defer r.attaching.Done()
	client,err:=newMcrfPipeStack(ctx,int(borrowedFD),spec)
	accepted:=r.finishAttach(slot,client,err)
	if !accepted{if client!=nil{_ = client.Close()};return errors.New("MCRF_PIPE_ATTACH_FAILED")}
	return nil // No target, route or WG handshake claimed.
}
func(r *mcrfPipeRegistry) finishAttach(slot *mcrfPipeSlot,client *mcrfPipeStack,failure error)bool{
	r.lock.Lock();defer r.lock.Unlock()
	// Never store a typed nil pointer in the lifecycle-owner interface: it
	// would look non-nil during later STOP and panic instead of retiring.
	if client!=nil{slot.client=client}
	accepted:=failure==nil&&!r.closed&&!r.sealed&&client!=nil&&client.alive()
	if !accepted{slot.failed=true};return accepted
}
func(r *mcrfPipeRegistry) match(generation int64,tag,token string,claim bool)(mcrfPipeClient,error){
	r.lock.Lock();defer r.lock.Unlock()
	slot:=r.slots[tag]
	if r.closed||generation!=r.generation||slot==nil||slot.token!=token||slot.failed||slot.client==nil||!slot.client.alive(){return nil,errors.New("MCRF_PIPE_NOT_OWNED")}
	if claim{if slot.claimed{return nil,errors.New("MCRF_PIPE_ALREADY_CLAIMED")};slot.claimed=true;r.sealed=true}
	return slot.client,nil
}
// Exact failed backend only. Keep the slot tombstone until actual fresh worker
// death, so a late config cannot resurrect it or silently bind another server.
func McrfFailPacketPipe(generation int64,tag,token string)error{
	return mcrfNativePipes.fail(generation,tag,token)
}
func(r *mcrfPipeRegistry) fail(generation int64,tag,token string)error{
	r.lock.Lock();slot:=r.slots[tag]
	if generation!=r.generation||slot==nil||slot.token!=token{r.lock.Unlock();return errors.New("MCRF_PIPE_NOT_OWNED")}
	slot.failed=true;client:=slot.client;r.lock.Unlock()
	if client!=nil{return client.Close()};return nil
}
func McrfClosePacketPipes(generation int64)error{
	return mcrfNativePipes.shutdown(generation)
}
func(r *mcrfPipeRegistry) shutdown(generation int64)error{
	if generation<=0{return errors.New("MCRF_PIPE_GENERATION")}
	r.lock.Lock()
	if r.generation==0{r.closed=true;r.lock.Unlock();return nil}
	if generation!=r.generation{r.lock.Unlock();return errors.New("MCRF_PIPE_GENERATION")}
	r.closed=true;if r.cancel!=nil{r.cancel()};r.lock.Unlock()
	// No Add can follow closed=true. Even a constructor racing STOP must finish
	// its exact close before this returns; no timer substitutes for ownership.
	r.attaching.Wait()
	r.lock.Lock();clients:=make([]mcrfPipeClient,0,len(r.slots));for _,slot:=range r.slots{slot.failed=true;if slot.client!=nil{clients=append(clients,slot.client)}};r.lock.Unlock()
	var failed bool;for _,client:=range clients{if client.Close()!=nil{failed=true}}
	if failed{return errors.New("MCRF_PIPE_CLOSE_PENDING")};return nil
}

type mcrfPipeOutboundOptions struct{
	Generation int64 `json:"run_generation"`
	Token string `json:"pipe_nonce"`
}
type mcrfPipeOutbound struct{
	outbound.Adapter
	lock sync.Mutex
	registry *mcrfPipeRegistry
	options mcrfPipeOutboundOptions
	client mcrfPipeClient
	closed bool
}
// Frozen config.go seam replaces only include.OutboundRegistry() with this
// factory. The type is unusable without an authenticated out-of-band fd attach.
func mcrfOutboundRegistry()*outbound.Registry{
	registry:=include.OutboundRegistry()
	outbound.Register[mcrfPipeOutboundOptions](registry,mcrfPacketPipeType,newMcrfPipeOutbound)
	outbound.Register[mcrfNumericSocksOptions](registry,mcrfNumericSocksType,newMcrfNumericSocks)
	return registry
}
func newMcrfPipeOutbound(_ context.Context,_ adapter.Router,_ log.ContextLogger,tag string,options mcrfPipeOutboundOptions)(adapter.Outbound,error){
	if options.Generation<=0||!mcrfPipeTag.MatchString(tag)||!mcrfPipeToken.MatchString(options.Token){return nil,errors.New("MCRF_PIPE_CONFIG")}
	if _,err:=mcrfNativePipes.match(options.Generation,tag,options.Token,false);err!=nil{return nil,err}
	return &mcrfPipeOutbound{Adapter:outbound.NewAdapter(mcrfPacketPipeType,tag,[]string{"tcp","udp"},nil),registry:&mcrfNativePipes,options:options},nil
}
func(p *mcrfPipeOutbound) Start(stage adapter.StartStage)error{
	if stage!=adapter.StartStateStart{return nil}
	p.lock.Lock();defer p.lock.Unlock()
	if p.closed||p.client!=nil{return errors.New("MCRF_PIPE_OUTBOUND_LIFETIME")}
	client,err:=p.registry.match(p.options.Generation,p.Tag(),p.options.Token,true);if err!=nil{return err};p.client=client;return nil
}
func(p *mcrfPipeOutbound) owner()(mcrfPipeClient,error){
	p.lock.Lock();defer p.lock.Unlock();if p.closed||p.client==nil||!p.client.alive(){return nil,errors.New("MCRF_PIPE_OUTBOUND_CLOSED")};return p.client,nil
}
func(p *mcrfPipeOutbound) DialContext(ctx context.Context,network string,destination M.Socksaddr)(net.Conn,error){
	client,err:=p.owner();if err!=nil{return nil,err};return client.DialContext(ctx,network,destination)
}
func(p *mcrfPipeOutbound) ListenPacket(ctx context.Context,destination M.Socksaddr)(net.PacketConn,error){
	client,err:=p.owner();if err!=nil{return nil,err};return client.ListenPacket(ctx,destination)
}
func(p *mcrfPipeOutbound) Close()error{
	p.lock.Lock();p.closed=true;client:=p.client;p.lock.Unlock()
	// CheckConfig/Validate may create and close an UNSTARTED wrapper repeatedly.
	// That must never consume/close the registered private packet owner.
	if client!=nil{return client.Close()};return nil
}
