package main

import (
	"crypto/tls"
	"crypto/x509"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	legacytun "github.com/amnezia-vpn/amneziawg-go/tun"
)
func TestP1FinalExactAwg3And31Parameters(t *testing.T){
	for _,version:=range []string{"awg3","awg3_1"}{
		ipc,conf,err:=fixtureParameters(version);if err!=nil{t.Fatal(err)}
		for _,field:=range []string{"header_protection_key=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20","content_padding_addition=0-8","s4=16","h1=123456-123460"}{if !strings.Contains(ipc,field+"\n"){t.Fatal("AWG3 field missing")}}
		if !strings.Contains(conf,"HeaderProtectionKey = ")||strings.Contains(conf,"header_protection_key"){t.Fatal("client canonical names missing")}
		if strings.Contains(ipc,"random_trailers=true")!=(version=="awg3_1"){t.Fatal("3.1 generation not explicit")}
	}
	if _,_,err:=fixtureParameters("awg4");err==nil{t.Fatal("unknown generation admitted")}
}
func TestP1FinalUntrustedHandshakeRefusesDefaultAndRetires(t *testing.T){
 var listeners []net.Listener
 server,err:=newFixtureTLS(func(_ *net.TCPAddr)(net.Listener,error){listener,err:=net.Listen("tcp4","127.0.0.1:0");if err==nil{listeners=append(listeners,listener)};return listener,err})
 if err!=nil{t.Fatal(err)};defer server.Close()
 client,err:=tls.DialWithDialer(&net.Dialer{Timeout:time.Second},"tcp",listeners[0].Addr().String(),&tls.Config{ServerName:"owned.mcrf.invalid",MinVersion:tls.VersionTLS12})
 if client!=nil{client.Close()};if err==nil{t.Fatal("owned untrusted server accepted system trust")}
 server.Close();if server.rejected.Load()!=1||server.accepted.Load()!=0||server.applicationBytes.Load()!=0{t.Fatal("trust rejection/application bytes proof wrong")}
}
func TestP1FinalNonTlsCannotOpenPublicRelay(t *testing.T){
 raw,client:=net.Pipe();relay:=&fixturePublicDNS{kind:"dot",port:853,streams:map[net.Conn]bool{raw:true}};relay.wg.Add(1)
 done:=make(chan struct{});go func(){relay.serve(raw);close(done)}()
 client.SetDeadline(time.Now().Add(time.Second));client.Write([]byte{0,0,0,0,0});client.Close()
 select{case <-done:case <-time.After(2*time.Second):t.Fatal("rejected stream not retired")};relay.Close()
 if relay.opened.Load()!=0||relay.refused.Load()!=1||relay.upBytes.Load()!=0||relay.downBytes.Load()!=0{t.Fatal("non TLS opened fixed public relay")}
}
type finalEventTun struct {legacytun.Device;events chan legacytun.Event}
func(t *finalEventTun)Events()<-chan legacytun.Event{return t.events}
func(t *finalEventTun)Close()error{close(t.events);return nil}
func(t *finalEventTun)File()*os.File{return nil}
func TestP1FinalIndependentNetstackEventBridgeCloses(t *testing.T){
	original:=&finalEventTun{events:make(chan legacytun.Event,1)};original.events<-legacytun.EventUp
	bridge:=bridgeNativeTun(original);select{case event:=<-bridge.Events():if int(event)!=int(legacytun.EventUp){t.Fatal("event changed")};case <-time.After(time.Second):t.Fatal("event lost")}
	if err:=bridge.Close();err!=nil{t.Fatal(err)};select{case _,open:=<-bridge.Events():if open{t.Fatal("owned events not closed")};case <-time.After(time.Second):t.Fatal("event goroutine not retired")}
}
func TestP1FinalUntrustedTlsCertificateHasExactSanAndNoSystemTrust(t *testing.T){
	certificate,err:=ephemeralFixtureCertificate();if err!=nil{t.Fatal(err)};leaf,err:=x509.ParseCertificate(certificate.Certificate[0]);if err!=nil{t.Fatal(err)}
	if err=leaf.VerifyHostname("owned.mcrf.invalid");err!=nil{t.Fatal("owned SAN absent")}
	if leaf.VerifyHostname("wrong.mcrf.invalid")==nil{t.Fatal("wrong name accepted")}
	if _,err=leaf.Verify(x509.VerifyOptions{DNSName:"owned.mcrf.invalid"});err==nil{t.Fatal("ephemeral self-signed certificate trusted by default")}
	if _,err=newFixturePublicDNS("arbitrary",nil);err==nil{t.Fatal("arbitrary public relay accepted")}
}
