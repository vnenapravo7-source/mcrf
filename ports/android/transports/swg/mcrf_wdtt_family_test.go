package main

import (
    "bytes"
    "context"
    "encoding/json"
    "errors"
    "fmt"
    "io"
    "net"
    "os"
    "path/filepath"
    "testing"
    "time"
)

type mcrfFamilyDialer struct {addresses []string; network,target string}
func(d *mcrfFamilyDialer)LookupContextHost(context.Context,string)([]string,error){return d.addresses,nil}
func(d *mcrfFamilyDialer)DialContext(_ context.Context,network,target string)(net.Conn,error){
    d.network=network;d.target=target;return nil,errors.New("fixture owned refusal")
}
func TestMcrfWdttExtendedHashPool(t *testing.T){
    p:=mcrfProfile{Peer:"192.0.2.1:46000",Password:"fixture",Workers:18,Device:"fixture-device",Listen:"127.0.0.1:22001",SOCKS:"127.0.0.1:22002",User:"user",Pass:"pass",Captcha:"auto",Fingerprint:"chrome"}
    for i:=0;i<12;i++{p.Hashes=append(p.Hashes,fmt.Sprintf("fixture-call-%d-0123456789abcdef",i))}
    path:=filepath.Join(t.TempDir(),"config.json");data,_:=json.Marshal(p)
    if err:=os.WriteFile(path,data,0600);err!=nil{t.Fatal(err)}
    got,err:=mcrfRead(path);if err!=nil||len(got.Hashes)!=12{t.Fatalf("extended pool lost: %v",err)}
}
func TestMcrfWdttIPv6TCPAddressAndFamily(t *testing.T){
    host,err:=readSocksHost(bytes.NewReader(net.ParseIP("2001:db8::42").To16()),socksAddressIPv6)
    if err!=nil||host!="2001:db8::42"{t.Fatal("IPv6 SOCKS address rejected")}
    d:=&mcrfFamilyDialer{addresses:[]string{"2001:db8::42"}};server:=socks5Server{dialer:d}
    target,err:=server.resolveIPv4(context.Background(),"fixture.invalid",443)
    if err!=nil||target!="[2001:db8::42]:443"{t.Fatal("AAAA-only target rejected")}
    client,peer:=net.Pipe();defer client.Close();defer peer.Close()
    client.SetDeadline(time.Now().Add(time.Second));peer.SetDeadline(time.Now().Add(time.Second))
    done:=make(chan error,1);go func(){done<-server.serveConnect(context.Background(),client,socksRequest{host:host,port:443})}()
    reply:=make([]byte,10);if _,err:=io.ReadFull(peer,reply);err!=nil{t.Fatal(err)}
    if err:=<-done;err==nil||d.network!="tcp"||d.target!=target{t.Fatal("IPv6 TCP dial lost family or refusal")}
}
func TestMcrfWdttIPv6UDPReplyAndRequestRoundTrip(t *testing.T){
    for _,host:=range []string{"192.0.2.42","2001:db8::42"}{
        packet,err:=buildSocksUDPDatagram(&net.UDPAddr{IP:net.ParseIP(host),Port:443},[]byte("owned-payload"))
        if err!=nil{t.Fatal(err)}
        request,payload,err:=parseSocksUDPDatagram(packet)
        if err!=nil||request.host!=host||request.port!=443||string(payload)!="owned-payload"{t.Fatal("UDP family/payload lost")}
    }
    if _,err:=readSocksHost(bytes.NewReader(make([]byte,15)),socksAddressIPv6);err==nil{t.Fatal("truncated IPv6 accepted")}
}
