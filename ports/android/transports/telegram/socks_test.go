package socks5

import("bytes";"io";"net";"testing";"time")

// Upstream net.Pipe fixtures read replies on the server end and deadlock.
// Keep all reads on the client and each request in one ordered write.
func TestMcrfGreeting(t *testing.T) {
    for _,tc:=range []struct{request,reply []byte;want error}{
        {[]byte{5,1,0},[]byte{5,0},nil},
        {[]byte{5,1,1},[]byte{5,255},ErrNoAuthAccepted},
        {[]byte{4,1},nil,ErrUnsupportedVersion},
    } {
        client,server:=net.Pipe();client.SetDeadline(time.Now().Add(time.Second));server.SetDeadline(time.Now().Add(time.Second))
        done:=make(chan error,1);go func(){defer client.Close();if _,err:=client.Write(tc.request);err!=nil {done<-err;return};reply:=make([]byte,len(tc.reply));_,err:=io.ReadFull(client,reply);if err==nil&&!bytes.Equal(reply,tc.reply){err=ErrAuthFailed};done<-err}()
        _,err:=HandleGreeting(server,&AuthConfig{});server.Close()
        if err!=tc.want {t.Fatalf("Greeting result: %v / %v",err,tc.want)}
        if err:=<-done;err!=nil {t.Fatal(err)}
    }
}
func TestMcrfRequest(t *testing.T) {
    for _,tc:=range []struct{request []byte;host string;port uint16}{
        {[]byte{5,1,0,1,127,0,0,1,31,144},"127.0.0.1",8080},
        {append(append([]byte{5,1,0,3,11},[]byte("example.com")...),0,80),"example.com",80},
        {[]byte{5,1,0,4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,187},"::1",443},
    } {
        client,server:=net.Pipe();client.SetDeadline(time.Now().Add(time.Second));server.SetDeadline(time.Now().Add(time.Second))
        done:=make(chan error,1);go func(){_,err:=client.Write(tc.request);client.Close();done<-err}()
        request,err:=ReadRequest(server);server.Close();if err!=nil{t.Fatal(err)};if err:=<-done;err!=nil{t.Fatal(err)}
        if request.DestAddr!=tc.host||request.DestPort!=tc.port{t.Fatal("Request fields changed")}
    }
    for _,code:=range []byte{ReplySucc,ReplyFail,ReplyHostUn,ReplyNetUn,255}{reply:=Reply(code);if len(reply)!=10||reply[0]!=5||reply[1]!=code{t.Fatal("Reply fields changed")}}
}
