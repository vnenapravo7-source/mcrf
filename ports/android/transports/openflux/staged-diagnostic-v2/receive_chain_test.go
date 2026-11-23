package transport
import("encoding/json";"strings";"testing";"github.com/p1neappleXpress/OpenFlux/mcrfdiag")
func v2Flags(r *mcrfdiag.Run)map[string]bool{var flags map[string]bool;json.Unmarshal([]byte(strings.TrimPrefix(r.Finish(),mcrfdiag.PrefixV2)),&flags);return flags}
func TestV2RealReceiveChain(t *testing.T){
 secret:="synthetic sufficiently long shared secret";context:="synthetic-context"
 // Produce real AES-GCM records, not invented decrypted data.
 senderWire:=&testTransport{};sender,_:=NewEncryptedTransport(senderWire,secret,context,true)
 ipv4:=make([]byte,20);ipv4[0]=0x45;if err:=sender.Send(ipv4);err!=nil{t.Fatal(err)};cipher:=append([]byte(nil),senderWire.sent...)
 cases:=[]struct{name string;frame []byte;want string;accepted bool}{
  {"empty",nil,"demux_empty",false},
  {"session",cipher,"demux_session",false},
  {"unknown",[]byte{0x7f},"codec_unknown",false},
  {"badbatch",[]byte{2},"codec_decode_failed",false},
  {"badlegacy",[]byte{0x1f,0xff},"codec_decode_failed",false},
  {"emptybatch",encodeBatch(nil),"codec_no_payload",false},
  {"keepalive",[]byte{0},"codec_no_payload",false},
  {"shortcrypto",encodeBatch([][]byte{{1}}),"classic_header_invalid",false},
  {"classic",encodeBatch([][]byte{cipher}),"classic_frame_accepted",true},
 }
 for _,tc:=range cases{t.Run(tc.name,func(t *testing.T){r:=mcrfdiag.BeginV2();raw:=NewBaseTransport(DefaultConfig());d:=newFrameDemux(&v2Raw{BaseTransport:raw},"synthetic");codec:=NewCodecTransport(d.side(false),CodecBatched,true);enc,_:=NewEncryptedTransport(codec,secret,context,false);enc.mcrfClassicDiag=r
  s:=&Session{};link:=&transportLink{classicEnc:enc};got:=0;s.Receive(func([]byte){got++});enc.Receive(func(p []byte){s.receiveClassic(link,p)});d.side(true).Receive(func([]byte){})
  raw.CallReceive(tc.frame);flags:=v2Flags(r);if !flags[tc.want]||(got==1)!=tc.accepted{t.Fatalf("wrong branch %s",tc.name)};if !tc.accepted&&flags["classic_decrypt_ok"]{t.Fatal("early filter falsely decrypted")}
 })}
 for _,tc:=range []string{"magic","version","direction","wrongkey","replay"}{t.Run(tc,func(t *testing.T){r:=mcrfdiag.BeginV2();raw:=NewBaseTransport(DefaultConfig());d:=newFrameDemux(&v2Raw{BaseTransport:raw},"synthetic");codec:=NewCodecTransport(d.side(false),CodecBatched,true);key:=secret;if tc=="wrongkey"{key="other sufficiently long shared secret"};enc,_:=NewEncryptedTransport(codec,key,context,false);enc.mcrfClassicDiag=r;got:=0;enc.Receive(func([]byte){got++});d.side(true).Receive(func([]byte){})
  packet:=append([]byte(nil),cipher...);want:="classic_header_invalid";switch tc{case"magic":packet[1]^=0xff;case"version":packet[3]^=0xff;case"direction":packet[4]^=1;want="classic_direction_invalid";case"wrongkey":want="classic_decrypt_failed";case"replay":want="classic_replay_rejected"};frame:=encodeBatch([][]byte{packet});raw.CallReceive(frame);if tc=="replay"{raw.CallReceive(frame)};flags:=v2Flags(r);if !flags[want]||got!=map[bool]int{true:1,false:0}[tc=="replay"]{t.Fatal("crypto filter",tc)}
 })}
}
type v2Raw struct{*BaseTransport}
func(r *v2Raw)Send(p []byte)error{return nil}
func TestV2CallbackMissing(t *testing.T){r:=mcrfdiag.BeginV2();raw:=NewBaseTransport(DefaultConfig());raw.CallReceive([]byte{2});if !v2Flags(r)["raw_callback_missing"]{t.Fatal("raw nil")};r=mcrfdiag.BeginV2();raw=NewBaseTransport(DefaultConfig());d:=newFrameDemux(&v2Raw{BaseTransport:raw},"synthetic");d.side(true).Receive(func([]byte){});raw.CallReceive([]byte{2});flags:=v2Flags(r);if !flags["demux_classic"]||!flags["layer_callback_missing"]{t.Fatal("demux nil")}}
