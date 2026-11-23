// SPDX-License-Identifier: GPL-3.0-or-later
// Test-only original-protocol servers for composed Android app acceptance.
// Not part of any production module or release APK.
package main

import (
 "bufio"
 "context"
 "crypto/ed25519"
 "crypto/ecdsa"
 "crypto/elliptic"
 "crypto/rand"
 "crypto/x509"
 "crypto/x509/pkix"
 "encoding/json"
 "encoding/pem"
 "errors"
 "fmt"
 "io"
 "math/big"
 "net"
 "os"
 "strconv"
 "strings"
 "sync"
 "time"

 box "github.com/sagernet/sing-box"
 "github.com/sagernet/sing-box/include"
 "github.com/sagernet/sing-box/option"
 sjson "github.com/sagernet/sing/common/json"
 "golang.org/x/crypto/ssh"
 "golang.org/x/net/proxy"
)

type specification struct { Host string `json:"host"`; Port int `json:"port"`; User string `json:"user"`; Password string `json:"password"` }

func certificate() (string,string,error) {
 private,err:=ecdsa.GenerateKey(elliptic.P256(),rand.Reader);if err!=nil{return "","",err};public:=&private.PublicKey
 root:=&x509.Certificate{SerialNumber:big.NewInt(time.Now().UnixNano()),Subject:pkix.Name{CommonName:"unit.invalid"},DNSNames:[]string{"unit.invalid"},NotBefore:time.Now().Add(-time.Hour),NotAfter:time.Now().Add(time.Hour),IsCA:true,BasicConstraintsValid:true,KeyUsage:x509.KeyUsageDigitalSignature|x509.KeyUsageCertSign,ExtKeyUsage:[]x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
 der,err:=x509.CreateCertificate(rand.Reader,root,root,public,private);if err!=nil{return "","",err}
 key,err:=x509.MarshalPKCS8PrivateKey(private);if err!=nil{return "","",err}
 return string(pem.EncodeToMemory(&pem.Block{Type:"CERTIFICATE",Bytes:der})),string(pem.EncodeToMemory(&pem.Block{Type:"PRIVATE KEY",Bytes:key})),nil
}
func availablePort(host string,udp bool)(int,error){
 if udp {socket,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.ParseIP(host)});if err!=nil{return 0,err};defer socket.Close();return socket.LocalAddr().(*net.UDPAddr).Port,nil}
 socket,err:=net.ListenTCP("tcp4",&net.TCPAddr{IP:net.ParseIP(host)});if err!=nil{return 0,err};defer socket.Close();return socket.Addr().(*net.TCPAddr).Port,nil
}
func tlsServer(ctx context.Context,spec specification,kind,cert,key,secret string)(map[string]any,io.Closer,error){
 port,err:=availablePort(spec.Host,kind=="tuic");if err!=nil{return nil,nil,err}
 uuid:="fce8eeec-a4e4-4321-8849-ccd59ab87111"
 inbound:=map[string]any{"type":kind,"listen":spec.Host,"listen_port":port,"tls":map[string]any{"enabled":true,"certificate":[]string{cert},"key":[]string{key}}}
 profile:=map[string]any{"type":kind,"tag":"Owned "+kind,"server":spec.Host,"server_port":port,"password":secret,"tls":map[string]any{"enabled":true,"server_name":"unit.invalid","certificate":[]string{cert}}}
 switch kind {
 case "tuic":inbound["users"]=[]any{map[string]any{"uuid":uuid,"password":secret}};profile["uuid"]=uuid;inbound["tls"].(map[string]any)["alpn"]=[]string{"h3"};profile["tls"].(map[string]any)["alpn"]=[]string{"h3"}
 case "anytls":inbound["users"]=[]any{map[string]any{"name":"fixture","password":secret}}
 case "naive":inbound["network"]="tcp";inbound["users"]=[]any{map[string]any{"username":"fixture","password":secret}};profile["username"]="fixture"
 default:return nil,nil,errors.New("unsupported test protocol")
 }
 // ALL requested destinations go to the authenticated test responder. There
 // is no direct outbound and no general external forwarding in this server.
 data,err:=json.Marshal(map[string]any{"log":map[string]any{"disabled":true},"inbounds":[]any{inbound},"outbounds":[]any{map[string]any{"type":"socks","tag":"fixture","server":spec.Host,"server_port":spec.Port,"version":"5","username":spec.User+"-"+kind,"password":spec.Password}},"route":map[string]any{"final":"fixture"}});if err!=nil{return nil,nil,err}
 options,err:=sjson.UnmarshalExtendedContext[option.Options](include.Context(ctx),data);if err!=nil{return nil,nil,err}
 server,err:=box.New(box.Options{Context:include.Context(ctx),Options:options});if err!=nil{return nil,nil,err}
 if err=server.Start();err!=nil{server.Close();return nil,nil,err};return profile,server,nil
}
type sshServer struct {listener net.Listener;mu sync.Mutex;peers []net.Conn}
func(s *sshServer)Close()error{s.listener.Close();s.mu.Lock();defer s.mu.Unlock();for _,peer:=range s.peers{peer.Close()};return nil}
func startSSH(ctx context.Context,spec specification,secret string)(map[string]any,io.Closer,error){
 _,private,err:=ed25519.GenerateKey(rand.Reader);if err!=nil{return nil,nil,err};signer,err:=ssh.NewSignerFromKey(private);if err!=nil{return nil,nil,err}
 config:=&ssh.ServerConfig{PasswordCallback:func(meta ssh.ConnMetadata,password []byte)(*ssh.Permissions,error){if meta.User()!="fixture"||string(password)!=secret{return nil,errors.New("owned fixture authentication rejected")};return nil,nil}};config.AddHostKey(signer)
 listener,err:=net.Listen("tcp4",net.JoinHostPort(spec.Host,"0"));if err!=nil{return nil,nil,err};server:=&sshServer{listener:listener}
 socks,err:=proxy.SOCKS5("tcp",net.JoinHostPort(spec.Host,strconv.Itoa(spec.Port)),&proxy.Auth{User:spec.User+"-ssh",Password:spec.Password},&net.Dialer{Timeout:4*time.Second});if err!=nil{server.Close();return nil,nil,err}
 go func(){for {raw,err:=listener.Accept();if err!=nil{return};server.mu.Lock();server.peers=append(server.peers,raw);server.mu.Unlock();go func(){defer raw.Close();conn,channels,requests,err:=ssh.NewServerConn(raw,config);if err!=nil{return};defer conn.Close();go ssh.DiscardRequests(requests)
  for request:=range channels{var destination struct{Host string;Port uint32;Origin string;OriginPort uint32};if request.ChannelType()!="direct-tcpip"||ssh.Unmarshal(request.ExtraData(),&destination)!=nil{request.Reject(ssh.Prohibited,"owned forwarding only");continue}
   upstream,err:=socks.Dial("tcp",net.JoinHostPort(destination.Host,strconv.Itoa(int(destination.Port))));if err!=nil{request.Reject(ssh.ConnectionFailed,"owned responder refused");continue};channel,requests,err:=request.Accept();if err!=nil{upstream.Close();continue};go ssh.DiscardRequests(requests)
   go func(){defer channel.Close();defer upstream.Close();done:=make(chan struct{});go func(){defer close(done);io.Copy(upstream,channel);if half,ok:=upstream.(interface{CloseWrite()error});ok{half.CloseWrite()}}();io.Copy(channel,upstream);channel.CloseWrite();<-done}()
  }
 }()}}()
 go func(){<-ctx.Done();server.Close()}()
 return map[string]any{"type":"ssh","tag":"Owned ssh","server":spec.Host,"server_port":listener.Addr().(*net.TCPAddr).Port,"user":"fixture","password":secret,"host_key":strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))},server,nil
}
func run()error{
 reader:=bufio.NewReader(io.LimitReader(os.Stdin,65536));line,err:=reader.ReadBytes('\n');if err!=nil{return err};var spec specification;if err=json.Unmarshal(line,&spec);err!=nil{return err}
 ip:=net.ParseIP(spec.Host);if ip==nil||ip.To4()==nil||!ip.IsPrivate()||spec.Port<1024||spec.Port>65535||spec.User!="owned"||len(spec.Password)<32{return errors.New("private owned responder specification required")}
 ctx,cancel:=context.WithCancel(context.Background());defer cancel();var closers []io.Closer;defer func(){for _,closer:=range closers{closer.Close()}}()
 cert,key,err:=certificate();if err!=nil{return err};profiles:=map[string]any{}
 for _,kind:=range []string{"tuic","anytls","naive","ssh"}{secretBytes:=make([]byte,24);if _,err=rand.Read(secretBytes);err!=nil{return err};secret:=fmt.Sprintf("%x",secretBytes);var profile map[string]any;var closer io.Closer
  if kind=="ssh"{profile,closer,err=startSSH(ctx,spec,secret)}else{profile,closer,err=tlsServer(ctx,spec,kind,cert,key,secret)};if err!=nil{return fmt.Errorf("%s server: %w",kind,err)};closers=append(closers,closer);profiles[kind]=profile
 }
 if err=json.NewEncoder(os.Stdout).Encode(map[string]any{"state":"OWNED_SERVERS_READY","profiles":profiles});err!=nil{return err}
 _,err=io.Copy(io.Discard,reader);return err
}
func main(){if err:=run();err!=nil{fmt.Fprintln(os.Stderr,"OWNED_APP_SERVER_FAILURE",err);os.Exit(1)}}
