package main

import (
 "bufio"
 "bytes"
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "crypto/tls"
 "encoding/binary"
 "encoding/hex"
 "encoding/json"
 "fmt"
 "io"
 "net"
 "os"
 "path/filepath"
 "strings"
 "sync"
 "time"
)

// Separate exact TLS acceptance peer. The closed Python peer is untouched.
// VLESS identity is fresh, private and checked before the ONLY admitted targets.
// No arbitrary destination, UDP, hostname resolution or system routing change.
func runFinalVless(host,output,kind string)error{
 if kind!="dot"&&kind!="doh"{return fmt.Errorf("explicit fixed TLS DNS protocol required")}
 identity:=make([]byte,16);nonce:=make([]byte,16);payload:=make([]byte,96)
 for _,b:=range [][]byte{identity,nonce,payload}{if _,err:=rand.Read(b);err!=nil{return err}}
 identity[6]=(identity[6]&15)|64;identity[8]=(identity[8]&63)|128
 id:=fmt.Sprintf("%x-%x-%x-%x-%x",identity[:4],identity[4:6],identity[6:8],identity[8:10],identity[10:])
 token:=hex.EncodeToString(nonce);path:="/owned/"+token
 cert,err:=ephemeralFixtureCertificate();if err!=nil{return err}
 config:=&tls.Config{Certificates:[]tls.Certificate{cert},MinVersion:tls.VersionTLS12,MaxVersion:tls.VersionTLS13}
 relay:=&fixturePublicDNS{kind:kind,port:853,streams:make(map[net.Conn]bool)};if kind=="doh"{relay.port=443}
 untrusted:=&fixtureTLS{streams:make(map[net.Conn]bool)}
 listener,err:=net.Listen("tcp4",net.JoinHostPort(host,"0"));if err!=nil{return err}
 var mu sync.Mutex;var wg sync.WaitGroup;active:=make(map[net.Conn]bool);accepted:=[]map[string]any{};refused:=[]string{}
 record:=func(row map[string]any){mu.Lock();defer mu.Unlock();if len(accepted)<64{accepted=append(accepted,row)}}
 until:=time.Now().Add(120*time.Second)
 private:=map[string]any{"schema":2,"uri":fmt.Sprintf("vless://%s@%s?security=none&type=tcp&encryption=none",id,listener.Addr().String()),"listenIpv4":host,"listenPort":listener.Addr().(*net.TCPAddr).Port,"targetIpv4":"198.18.0.2","targetPort":80,"nonce":token,"path":path,"payloadHex":hex.EncodeToString(payload),"deadlineSeconds":120,"maxConnections":64}
 raw,err:=json.Marshal(private);if err!=nil{listener.Close();return err}
 if err=os.WriteFile(filepath.Join(output,"fixture-private.json"),raw,0600);err!=nil{listener.Close();return err}
 handle:=func(stream net.Conn)error{
  stream.SetDeadline(time.Now().Add(18*time.Second));header:=make([]byte,18);if _,err:=io.ReadFull(stream,header);err!=nil{return err}
  if header[0]!=0||header[17]!=0||subtle.ConstantTimeCompare(header[1:17],identity)!=1{return fmt.Errorf("IDENTITY")}
  target:=make([]byte,4);if _,err:=io.ReadFull(stream,target);err!=nil{return err}
  if target[0]!=1||(target[3]!=1&&target[3]!=3){return fmt.Errorf("DESTINATION")}
  n:=4;if target[3]==3{n=16};ip:=make([]byte,n);if _,err:=io.ReadFull(stream,ip);err!=nil{return err}
  address:=net.IP(ip).String();port:=int(binary.BigEndian.Uint16(target[1:3]))
  if (address=="1.1.1.1"||address=="8.8.8.8")&&port==relay.port{
   if _,err:=stream.Write([]byte{0,0});err!=nil{return err}
   relay.mu.Lock();relay.streams[stream]=true;relay.wg.Add(1);relay.mu.Unlock();relay.serve(stream);return nil
  }
  untrustedPort:=1853;if kind=="doh"{untrustedPort=1443}
  if address=="198.18.0.253"&&port==untrustedPort{
   if _,err:=stream.Write([]byte{0,0});err!=nil{return err}
   untrusted.mu.Lock();untrusted.streams[stream]=true;untrusted.wg.Add(1);untrusted.mu.Unlock();untrusted.serve(stream,config);return nil
  }
  if port!=80||(address!="198.18.0.2"&&address!="fd77::2"){return fmt.Errorf("DESTINATION")}
  reader:=bufio.NewReaderSize(stream,4096);var lines []string;size:=0
  for {line,err:=reader.ReadString('\n');if err!=nil{return err};size+=len(line);if size>4096{return fmt.Errorf("HTTP_SIZE")};if line=="\r\n"{break};if !strings.HasSuffix(line,"\r\n"){return fmt.Errorf("HTTP_FORMAT")};lines=append(lines,strings.TrimSuffix(line,"\r\n"))}
  if len(lines)!=4{return fmt.Errorf("HTTP_FIELDS")};parts:=strings.Split(lines[0]," ")
  if len(parts)!=3||parts[1]!=path||parts[2]!="HTTP/1.1"||(parts[0]!="GET"&&parts[0]!="POST"){return fmt.Errorf("HTTP_REQUEST")}
  fields:=map[string]string{};for _,line:=range lines[1:]{kv:=strings.SplitN(line,":",2);if len(kv)!=2{return fmt.Errorf("HTTP_FIELDS")};k:=strings.ToLower(kv[0]);if _,exists:=fields[k];exists{return fmt.Errorf("HTTP_FIELDS")};fields[k]=strings.TrimSpace(kv[1])}
  expectedHost:=address;if n==16{expectedHost="["+address+"]"};body:=[]byte{};status:="204 No Content";length:="0"
  if parts[0]=="POST"{body=make([]byte,len(payload));if _,err:=io.ReadFull(reader,body);err!=nil||!bytes.Equal(body,payload){return fmt.Errorf("HTTP_BODY")};status="200 OK";length="96"}
  if fields["host"]!=expectedHost||fields["content-length"]!=length||fields["connection"]!="close"{return fmt.Errorf("HTTP_FIELDS")}
  reply:=append([]byte{0,0},[]byte("HTTP/1.1 "+status+"\r\nContent-Length: "+length+"\r\nConnection: close\r\n\r\n")...);reply=append(reply,body...)
  if _,err:=stream.Write(reply);err!=nil{return err};hash:=sha256.Sum256(reply)
  family:=4;if n==16{family=6};record(map[string]any{"kind":"HTTP","method":parts[0],"family":family,"target":address,"request_body_bytes":len(body),"response_sha256":hex.EncodeToString(hash[:])});return nil
 }
 for count:=0;count<64&&time.Now().Before(until);{
  if _,err:=os.Stat(filepath.Join(output,"stop"));err==nil{break}
  listener.(*net.TCPListener).SetDeadline(time.Now().Add(250*time.Millisecond));stream,err:=listener.Accept();if err!=nil{if e,ok:=err.(net.Error);ok&&e.Timeout(){continue};break};count++
  mu.Lock();if len(active)>=16{mu.Unlock();stream.Close();continue};active[stream]=true;wg.Add(1);mu.Unlock()
  go func(){defer wg.Done();defer func(){stream.Close();mu.Lock();delete(active,stream);mu.Unlock()}();if err:=handle(stream);err!=nil{code:="OWNED_STREAM_CLOSED";for _,known:=range []string{"IDENTITY","DESTINATION","HTTP_SIZE","HTTP_FORMAT","HTTP_FIELDS","HTTP_REQUEST","HTTP_BODY"}{if err.Error()==known{code=known}};mu.Lock();refused=append(refused,code);mu.Unlock()}}()
 }
 listener.Close();mu.Lock();for stream:=range active{stream.Close()};mu.Unlock();wg.Wait();relay.Close();untrusted.Close()
 receipt:=map[string]any{"schema":2,"accepted":accepted,"refused":refused,"forwarded_connections":relay.opened.Load(),"listener_closed":true,"all_sockets_closed":len(active)==0,"tls_untrusted_rejected":untrusted.rejected.Load(),"tls_untrusted_accepted":untrusted.accepted.Load(),"tls_application_bytes":untrusted.applicationBytes.Load(),"tls_public_up":relay.upBytes.Load(),"tls_public_down":relay.downBytes.Load(),"tls_public_refused":relay.refused.Load(),"public_mode":kind}
 data,err:=json.Marshal(receipt);if err!=nil{return err};return os.WriteFile(filepath.Join(output,"peer-receipt.json"),data,0600)
}
