package transport
import("testing";"github.com/p1neappleXpress/OpenFlux/transport/control")
func TestMcrfHelloAcceptanceReadinessAndDataAssociation(t *testing.T){
 params:=PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}
 s,err:=NewSession(params,false);if err!=nil{t.Fatal(err)};s.SetClassic(CodecBatched);wire:=&testTransport{};if err=s.AddTransport("synthetic",wire,"synthetic sufficiently long shared secret","synthetic-context",0);err!=nil{t.Fatal(err)};link:=s.links["synthetic"];if err=s.startLink(link);err!=nil{t.Fatal(err)};defer s.Stop()
 var sender [32]byte;sender[0]=1;hello:=&control.Envelope{Kind:control.KindHello,Role:control.RoleExit,Local:sender,Hello:&control.HelloTail{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}}
 s.receiveHello(link,hello);if s.cntHelloAccept.Load()!=1||s.ready{t.Fatal("initial accepted hello incorrectly established session")};if _,ready:=s.PeerParameters();ready{t.Fatal("initial hello reported negotiated peer")};if !s.IsConnected(){t.Fatal("classic carrier readiness was lost")}
 packet:=testIPv4(60,6);env:=&control.Envelope{Kind:control.KindIPv4,Role:control.RoleExit,Local:sender,Peer:s.local,Data:&control.DataTail{Sequence:1}};raw,_:=env.Encode();raw=append(raw,packet...);got:=0;s.Receive(func([]byte){got++});s.receiveIPv4(link,raw,env);if got!=0||s.cntDataDrop.Load()!=1{t.Fatal("data before ready accepted")}
 hello.Peer=s.local;s.receiveHello(link,hello);if !s.ready{t.Fatal("echo did not establish")};if _,ready:=s.PeerParameters();!ready{t.Fatal("peer association missing")}
 wrong:=*env;wrong.Peer=[32]byte{};s.receiveIPv4(link,raw,&wrong);if got!=0||s.cntDataDrop.Load()!=2{t.Fatal("wrong association accepted")}
 s.receiveIPv4(link,raw,env);if got!=1{t.Fatal("valid associated data missing")};s.receiveIPv4(link,raw,env);if got!=1||s.cntDataDrop.Load()!=3{t.Fatal("replayed data accepted")}
}
