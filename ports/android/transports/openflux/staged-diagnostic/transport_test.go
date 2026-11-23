package transport
import("strings";"testing";"github.com/p1neappleXpress/OpenFlux/mcrfdiag")
func TestMcrfClassicDiagnosticEncryption(t *testing.T){
 r:=mcrfdiag.Begin();wire:=&testTransport{};sender,err:=NewEncryptedTransport(wire,"first sufficiently long secret","document",false);if err!=nil{t.Fatal(err)};sender.Send([]byte("private synthetic packet"))
 // An ordinary Session encryption instance must not contribute classic flags.
 normalWire:=&testTransport{};normal,_:=NewEncryptedTransport(normalWire,"other sufficiently long secret","document",true);normal.Receive(func([]byte){t.Fatal("wrong key accepted")});normalWire.deliver(wire.sent)
 wrongWire:=&testTransport{};wrong,_:=NewEncryptedTransport(wrongWire,"other sufficiently long secret","document",true);wrong.mcrfClassicDiag=r;wrong.Receive(func([]byte){t.Fatal("wrong classic key accepted")});wrongWire.deliver(wire.sent)
 rightWire:=&testTransport{};right,_:=NewEncryptedTransport(rightWire,"first sufficiently long secret","document",true);right.mcrfClassicDiag=r;called:=false;right.Receive(func([]byte){called=true});rightWire.deliver(wire.sent)
 line:=r.Finish();if !called||!strings.Contains(line,`"classic_decrypt_ok":true`)||!strings.Contains(line,`"classic_decrypt_failed":true`)||strings.Contains(line,"private")||strings.Contains(line,"document"){t.Fatal("classic diagnostic/privacy")}
 r=mcrfdiag.Begin();normalWire.deliver(wire.sent);if strings.Contains(r.Finish(),":true"){t.Fatal("nonclassic contamination")}
}
func TestMcrfClassicDiagnosticFrame(t *testing.T){r:=mcrfdiag.Begin();link:=&transportLink{classicEnc:&EncryptedTransport{mcrfClassicDiag:r}};s:=&Session{};s.receiveClassic(link,[]byte{1});packet:=make([]byte,20);packet[0]=0x45;s.receiveClassic(link,packet);line:=r.Finish();if !strings.Contains(line,`"classic_frame_accepted":true`)||!strings.Contains(line,`"classic_frame_rejected":true`){t.Fatal("frame hooks")}}
