package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// A fresh, deliberately UNTRUSTED certificate. Private key remains in memory,
// never in client.conf, JSON, argv, a log or an installed Android trust store.
func ephemeralFixtureCertificate()(tls.Certificate,error){
	key,err:=ecdsa.GenerateKey(elliptic.P256(),rand.Reader);if err!=nil{return tls.Certificate{},err}
	serial,err:=rand.Int(rand.Reader,new(big.Int).Lsh(big.NewInt(1),128));if err!=nil{return tls.Certificate{},err}
	now:=time.Now();template:=&x509.Certificate{SerialNumber:serial,Subject:pkix.Name{CommonName:"owned.mcrf.invalid"},DNSNames:[]string{"owned.mcrf.invalid"},NotBefore:now.Add(-time.Hour),NotAfter:now.Add(time.Hour),KeyUsage:x509.KeyUsageDigitalSignature,ExtKeyUsage:[]x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},BasicConstraintsValid:true}
	template.IPAddresses=[]net.IP{net.ParseIP("198.18.0.253")}
	der,err:=x509.CreateCertificate(rand.Reader,template,template,&key.PublicKey,key);if err!=nil{return tls.Certificate{},err}
	return tls.Certificate{Certificate:[][]byte{der},PrivateKey:key},nil
}
type fixtureTLS struct {
	mu sync.Mutex;closed bool;listeners []net.Listener;streams map[net.Conn]bool;wg sync.WaitGroup
	accepted,rejected,applicationBytes atomic.Int64
}
func newFixtureTLS(listen func(*net.TCPAddr)(net.Listener,error))(*fixtureTLS,error){
	cert,err:=ephemeralFixtureCertificate();if err!=nil{return nil,err}
	config:=&tls.Config{Certificates:[]tls.Certificate{cert},MinVersion:tls.VersionTLS12,MaxVersion:tls.VersionTLS13}
	s:=&fixtureTLS{streams:make(map[net.Conn]bool)}
	for _,host:=range []string{"10.77.0.1","fd77::1"}{for _,port:=range []int{1853,1443}{
		listener,err:=listen(&net.TCPAddr{IP:net.ParseIP(host),Port:port});if err!=nil{s.Close();return nil,err};s.listeners=append(s.listeners,listener);s.wg.Add(1)
		go func(l net.Listener){defer s.wg.Done();for{raw,err:=l.Accept();if err!=nil{return};s.mu.Lock();if s.closed||len(s.streams)>=16{s.mu.Unlock();raw.Close();continue};s.streams[raw]=true;s.wg.Add(1);s.mu.Unlock();go s.serve(raw,config)}}(listener)
	}}
	return s,nil
}
func(s *fixtureTLS)serve(raw net.Conn,config *tls.Config){
	defer s.wg.Done();defer func(){raw.Close();s.mu.Lock();delete(s.streams,raw);s.mu.Unlock()}()
	raw.SetDeadline(time.Now().Add(5*time.Second));socket:=tls.Server(raw,config)
	if socket.Handshake()!=nil{s.rejected.Add(1);return};s.accepted.Add(1)
	buffer:=make([]byte,4096);n,_:=socket.Read(buffer);s.applicationBytes.Add(int64(n))
}
func(s *fixtureTLS)Close(){
	s.mu.Lock();if !s.closed{s.closed=true;for _,listener:=range s.listeners{listener.Close()};for stream:=range s.streams{stream.Close()}};s.mu.Unlock();s.wg.Wait()
}
