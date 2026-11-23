package transport
import("testing";"encoding/json";"strings";"github.com/p1neappleXpress/OpenFlux/mcrfdiag";"github.com/p1neappleXpress/OpenFlux/transport/control")
// Same observed public Boolean pattern can precede distinct real Session outcomes.
func TestV3ActualSessionPipeline(t *testing.T){
 for _,kind:=range []string{"short_O","same_crypto_role","wrong_crypto_key","plaintext_not_batch","invalid_envelope","same_envelope_role","valid_hello","invalid_hello","callback_missing","replay"}{t.Run(kind,func(t *testing.T){
  r:=mcrfdiag.BeginV3();secret:="synthetic sufficiently long shared secret";ctx:="synthetic-context"
  params:=PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}
  s,err:=NewSession(params,false);if err!=nil{t.Fatal(err)};s.SetClassic(CodecBatched);raw:=&testTransport{}
  if err=s.AddTransport("synthetic",raw,secret,ctx,0);err!=nil{t.Fatal(err)};link:=s.links["synthetic"];if err=s.startLink(link);err!=nil{t.Fatal(err)};defer s.Stop()
  var id [32]byte;id[0]=1;env:=&control.Envelope{Kind:control.KindHello,Role:control.RoleExit,Local:id,Hello:&control.HelloTail{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}}
  if kind=="same_envelope_role"{env.Role=control.RoleClient};if kind=="invalid_hello"{env.Local=[32]byte{}};envelope,err:=env.Encode();if err!=nil{t.Fatal(err)}
  plaintext:=encodeBatch([][]byte{envelope});if kind=="plaintext_not_batch"{plaintext=make([]byte,20);plaintext[0]=0x45};if kind=="invalid_envelope"{plaintext=encodeBatch([][]byte{{1}})}
  wire:=&testTransport{};key:=secret;if kind=="wrong_crypto_key"{key="other sufficiently long shared secret"};sender,_:=NewEncryptedTransport(wire,key,ctx,kind!="same_crypto_role");sender.Send(plaintext)
  packet:=wire.sent;if kind=="short_O"{packet=[]byte{'O'}};if kind=="callback_missing"{link.batched.Receive(nil)};raw.deliver(packet);if kind=="replay"{raw.deliver(packet)}
  switch kind{
  case"short_O":if link.encrypted.recvBadLen.Load()!=1{t.Fatal("not header length gate")}
  case"same_crypto_role":if link.encrypted.recvBadHdr.Load()!=1{t.Fatal("not direction gate")}
  case"wrong_crypto_key":if link.encrypted.recvFail.Load()!=1{t.Fatal("not Session AEAD gate")}
  case"plaintext_not_batch":if link.encrypted.recvOK.Load()!=1||s.cntDecodeErr.Load()!=0{t.Fatal("not pre-envelope batch drop")}
  case"invalid_envelope":if s.cntDecodeErr.Load()!=1{t.Fatal("not envelope decode gate")}
  case"same_envelope_role":if link.encrypted.recvOK.Load()!=1||s.cntHelloRecv.Load()!=0{t.Fatal("not envelope role gate")}
  case"valid_hello":if s.cntHelloRecv.Load()!=1{t.Fatal("valid Session hello not reached")};case"invalid_hello":if s.cntHelloReject.Load()!=1{t.Fatal("hello not rejected")};case"callback_missing":if s.cntHelloRecv.Load()!=0{t.Fatal("nil callback delivered")};case"replay":if link.encrypted.recvReplay.Load()!=1{t.Fatal("replay not rejected")}
  }
  var flags map[string]bool;json.Unmarshal([]byte(strings.TrimPrefix(r.Finish(),mcrfdiag.PrefixV3)),&flags);want:=map[string]string{"short_O":"sess_hdr_bad","same_crypto_role":"sess_dir_bad","wrong_crypto_key":"sess_dec_fail","plaintext_not_batch":"sess_batch_bad","invalid_envelope":"sess_env_bad","same_envelope_role":"sess_role_bad","valid_hello":"sess_hello_ok","invalid_hello":"sess_hello_bad","callback_missing":"sess_cb_missing","replay":"sess_replay"}[kind];if !flags[want]{t.Fatal("missing Session diagnostic",kind)};if !flags["demux_session"]||flags["demux_classic"]||flags["classic_decrypt_ok"]||flags["classic_decrypt_failed"]||flags["classic_frame_accepted"]{t.Fatal("observed Session-only pattern changed")}
 })}
}
