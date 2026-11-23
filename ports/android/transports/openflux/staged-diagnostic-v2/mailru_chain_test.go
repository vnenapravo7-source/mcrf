package mailru
import("encoding/base64";"encoding/json";"strings";"testing";"github.com/p1neappleXpress/OpenFlux/mcrfdiag";"github.com/p1neappleXpress/OpenFlux/transport")
type v2Wire struct{*transport.BaseTransport;sent []byte}
func(w *v2Wire)Send(p []byte)error{w.sent=append([]byte(nil),p...);return nil}
func TestV2MailruThroughActualSessionChain(t *testing.T){for _,kind:=range []string{"own_echo","empty_peer","session_peer","classic_peer"}{t.Run(kind,func(t *testing.T){
 r:=mcrfdiag.BeginV2();wire:=&v2Wire{BaseTransport:transport.NewBaseTransport(transport.DefaultConfig())};secret:="synthetic sufficiently long shared secret";ctx:="synthetic-context"
 s,err:=transport.NewSession(transport.PeerParameters{Capabilities:transport.CapabilityIPv4|transport.CapabilityTCP,MaxPacketSize:transport.MaxNegotiatedPacket},true);if err!=nil{t.Fatal(err)};s.SetClassic(transport.CodecBatched)
 if err=s.AddTransport("synthetic",wire,secret,ctx,0);err!=nil{t.Fatal(err)};got:=0;s.Receive(func([]byte){got++});if err=s.Start();err!=nil{t.Fatal(err)};defer s.Stop()
 senderWire:=&v2Wire{BaseTransport:transport.NewBaseTransport(transport.DefaultConfig())};enc,_:=transport.NewEncryptedTransport(senderWire,secret,ctx,false);packet:=make([]byte,20);packet[0]=0x45;enc.Send(packet);payload:=transport.EncodeBatchFrame([][]byte{senderWire.sent});sender:="peer"
 switch kind{case"own_echo":sender="own";case"empty_peer":payload=nil;case"session_peer":payload=senderWire.sent}
 chat:=map[string]any{"type":"message","messages":[]any{map[string]any{"message":"OFX1"+base64.StdEncoding.EncodeToString(payload),"useridoriginal":sender}}};data,_:=json.Marshal([]any{"message",chat});frame:=append([]byte("42"),data...)
 tr:=NewMailruDocsTransport("",transport.DefaultConfig());session:=&DocSession{diag:r};session.Info.EditorUserID="own";tr.Receive(wire.CallReceive);tr.handleMessage(session,frame)
 s.Stop();var flags map[string]bool;json.Unmarshal([]byte(strings.TrimPrefix(r.Finish(),mcrfdiag.PrefixV2)),&flags)
 if (got==1)!=(kind=="classic_peer"){t.Fatal("actual chain delivery",kind)};switch kind{case"own_echo":if flags["mailru_peer_chat_decoded"]{t.Fatal("echo accepted")};case"empty_peer":if !flags["mailru_peer_chat_decoded"]||!flags["demux_empty"]{t.Fatal("empty peer not distinguished")};case"session_peer":if !flags["demux_session"]||flags["classic_decrypt_ok"]{t.Fatal("Session classified classic")};case"classic_peer":if !flags["mailru_peer_chat_decoded"]||!flags["demux_classic"]||!flags["classic_decrypt_ok"]||!flags["classic_frame_accepted"]{t.Fatal("full classic chain missing")}}
 })}}
