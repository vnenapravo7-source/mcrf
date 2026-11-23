package mobile

import("bufio";"encoding/binary";"fmt";"io";"net";"net/http";"strconv";"sync";"sync/atomic";"testing";"time";"github.com/p1neappleXpress/OpenFlux/transport";"github.com/p1neappleXpress/OpenFlux/tunnel")

// Asynchronous in-memory carrier; every production Session/crypto/tunnel component is real.
type mcrfPathWire struct{mu sync.RWMutex;cb func([]byte);peer *mcrfPathWire;in chan []byte;done chan struct{};start,stop sync.Once;wg sync.WaitGroup;running atomic.Bool;sent atomic.Uint64;recv atomic.Uint64}
func(w *mcrfPathWire)Start()error{w.start.Do(func(){w.running.Store(true);w.wg.Add(1);go func(){defer w.wg.Done();for{select{case <-w.done:return;case p:=<-w.in:w.mu.RLock();cb:=w.cb;w.mu.RUnlock();if cb!=nil{w.recv.Add(uint64(len(p)));cb(p)}}}}()});return nil}
func(w *mcrfPathWire)Stop()error{w.stop.Do(func(){w.running.Store(false);close(w.done)});w.wg.Wait();return nil}
func(w *mcrfPathWire)Send(p []byte)error{w.sent.Add(uint64(len(p)));select{case w.peer.in<-append([]byte(nil),p...):return nil;case <-w.peer.done:return io.EOF}}
func(w *mcrfPathWire)Receive(cb func([]byte)){w.mu.Lock();w.cb=cb;w.mu.Unlock()}
func(w *mcrfPathWire)IsConnected()bool{return w.running.Load()}
func(w *mcrfPathWire)Stats()transport.TransportStats{return transport.TransportStats{BytesSent:w.sent.Load(),BytesReceived:w.recv.Load(),Connected:w.IsConnected()}}

func TestMcrfMobileSessionSOCKSHTTPMemoryCarrier(t *testing.T){
 SetDebugLevel(0);StopProxy();StopExit();defer StopProxy();defer StopExit()
 a:=&mcrfPathWire{in:make(chan []byte,512),done:make(chan struct{})};b:=&mcrfPathWire{in:make(chan []byte,512),done:make(chan struct{})};a.peer=b;b.peer=a
 makeSession:=func(raw transport.Transport,exit bool)*transport.Session{ s,err:=transport.NewSession(transport.PeerParameters{Capabilities:transport.CapabilityIPv4|transport.CapabilityTCP|transport.CapabilityUDP,MaxPacketSize:transport.MaxNegotiatedPacket},exit);if err!=nil{t.Fatal(err)};s.SetClassic(transport.CodecBatched);if err=s.AddTransport("synthetic",raw,"synthetic sufficiently long shared secret","synthetic-context",0);err!=nil{t.Fatal(err)};return s }
 ex:=makeSession(b,true);cl:=makeSession(a,false)
 if msg:=startExitWith(func()(transport.Transport,error){return ex,nil},nil);msg!=""{t.Fatal("exit initialization failed")}
 const target="198.18.0.1:80";restore:=tunnel.McrfFixtureSetExitDial(func(addr string)(net.Conn,error){if addr!=target{return nil,fmt.Errorf("unexpected fixture destination")};left,right:=net.Pipe();go func(){defer right.Close();right.SetDeadline(time.Now().Add(3*time.Second));if _,err:=http.ReadRequest(bufio.NewReader(right));err!=nil{return};body:="synthetic-through-session";fmt.Fprintf(right,"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",len(body),body)}();return left,nil});defer restore()
 probe,err:=net.Listen("tcp4","127.0.0.1:0");if err!=nil{t.Fatal(err)};socksAddr:=probe.Addr().String();probe.Close()
 if msg:=startProxyWith(func()(transport.Transport,error){return cl,nil},socksAddr,"fixture-user","fixture-pass","");msg!=""{t.Fatal("proxy initialization failed")}
 proxy.mu.Lock();clientTun:=proxy.tun;proxy.mu.Unlock();defer clientTun.Close();if cl.Mode()!="session"||ex.Mode()!="session"{t.Fatal("not established Session")};if _,ok:=cl.PeerParameters();!ok{t.Fatal("no negotiated peer association")}
 conn,err:=net.DialTimeout("tcp4",socksAddr,time.Second);if err!=nil{t.Fatal("SOCKS connect failed")};defer conn.Close();conn.SetDeadline(time.Now().Add(12*time.Second))
 conn.Write([]byte{5,1,2});var reply [2]byte;if _,err=io.ReadFull(conn,reply[:]);err!=nil||reply!=[2]byte{5,2}{t.Fatal("SOCKS method")}
 user,pass:=[]byte("fixture-user"),[]byte("fixture-pass");auth:=append([]byte{1,byte(len(user))},user...);auth=append(auth,byte(len(pass)));auth=append(auth,pass...);conn.Write(auth);if _,err=io.ReadFull(conn,reply[:]);err!=nil||reply!=[2]byte{1,0}{t.Fatal("SOCKS auth")}
 host,port,_:=net.SplitHostPort(target);ip:=net.ParseIP(host).To4();number,_:=strconv.Atoi(port);request:=append([]byte{5,1,0,1},ip...);request=binary.BigEndian.AppendUint16(request,uint16(number));conn.Write(request)
 var head [4]byte;if _,err=io.ReadFull(conn,head[:]);err!=nil||head[1]!=0{t.Fatal("SOCKS target TCP did not succeed")};switch head[3]{case 4:io.CopyN(io.Discard,conn,18);case 1:io.CopyN(io.Discard,conn,6);default:t.Fatal("SOCKS reply type")}
 fmt.Fprintf(conn,"GET / HTTP/1.1\r\nHost: synthetic\r\nConnection: close\r\n\r\n");res,err:=http.ReadResponse(bufio.NewReader(conn),nil);if err!=nil{t.Fatal("HTTP through Session failed")};defer res.Body.Close();body,err:=io.ReadAll(res.Body);if err!=nil||res.StatusCode!=200||string(body)!="synthetic-through-session"{t.Fatal("HTTP body did not traverse actual tunnel")}
 if a.sent.Load()==0||b.sent.Load()==0{t.Fatal("no bidirectional carrier")}
}
