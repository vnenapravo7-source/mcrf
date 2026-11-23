//go:build with_lx_raw_packet && (linux || android)

package libbox

import (
 "context"
 "os"
 "testing"
 "time"
 "github.com/sagernet/sing-box/adapter"
 "golang.org/x/sys/unix"
)

func endpointPair(t *testing.T) (*mcrfRawEndpoint,int) {
 t.Helper()
 fds,err:=unix.Socketpair(unix.AF_UNIX,unix.SOCK_SEQPACKET|unix.SOCK_CLOEXEC,0);if err!=nil {t.Fatal(err)}
 e,err:=duplicateAndValidateMcrfRawEndpoint(fds[0],"0123456789abcdef0123456789abcdef",1,int32(os.Getpid()),"10.0.0.2",1500)
 _=unix.Close(fds[0]);if err!=nil {_=unix.Close(fds[1]);t.Fatal(err)}
 t.Cleanup(func(){_=e.Close();_=unix.Close(fds[1])})
 return e,fds[1]
}
func endpointPacket(reply bool) []byte {
 p:=make([]byte,20);p[0]=0x45;p[3]=20
 if reply {copy(p[16:20],[]byte{10,0,0,2})}else{copy(p[12:16],[]byte{10,0,0,2})}
 return p
}
func TestMcrfRawEndpointAtomicRoundTripAndCallerFdOwnership(t *testing.T) {
 e,peer:=endpointPair(t)
 p:=endpointPacket(false)
 state,err:=e.SendOriginalPacket(context.Background(),p);if err!=nil||state!=adapter.RawPacketAccepted {t.Fatal(state,err)}
 b:=make([]byte,64);n,_,flags,_,err:=unix.Recvmsg(peer,b,nil,0);if err!=nil||n!=20||flags!=0 {t.Fatal(n,flags,err)}
 if err=unix.Sendmsg(peer,endpointPacket(true),nil,nil,0);err!=nil {t.Fatal(err)}
 reply,err:=e.ReadOriginalPacket(context.Background());if err!=nil||len(reply)!=20 {t.Fatal(err)}
}
func TestMcrfRawEndpointCloseWakesReaderAndCannotReadReusedFd(t *testing.T) {
 e,_:=endpointPair(t);done:=make(chan error,1)
 go func(){_,err:=e.ReadOriginalPacket(context.Background());done<-err}()
 time.Sleep(20*time.Millisecond)
 if err:=e.Close();err!=nil {t.Fatal(err)}
 select {case err:=<-done:if err==nil {t.Fatal("closed read succeeded")};case <-time.After(time.Second):t.Fatal("reader was not retired")}
 if err:=e.Close();err!=nil {t.Fatal("close is not idempotent",err)}
 if state,_:=e.SendOriginalPacket(context.Background(),endpointPacket(false));state!=adapter.RawPacketRejected {t.Fatal("closed write admitted")}
}
func TestMcrfRawEndpointRejectsOversizedAndForeignReply(t *testing.T) {
 for _,p:=range [][]byte{make([]byte,1600),endpointPacket(false)} {
  e,peer:=endpointPair(t);if err:=unix.Sendmsg(peer,p,nil,nil,0);err!=nil {t.Fatal(err)}
  if _,err:=e.ReadOriginalPacket(context.Background());err==nil {t.Fatal("bad reply accepted")}
 }
}
