package main

import (
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// Test-only, explicit opt-in to ONE fixed public DNS endpoint. Not a generic
// proxy/relay: no caller-supplied IP, port or hostname, no platform DNS, no key
// export. TLS stays end-to-end between Android's normal system trust and CF.
// Official endpoints: developers.cloudflare.com/1.1.1.1/encryption/dns-over-tls/
type fixturePublicDNS struct {
	mu sync.Mutex;closed bool;listeners []net.Listener;streams map[net.Conn]bool;wg sync.WaitGroup
	kind string;port int;opened,upBytes,downBytes,refused atomic.Int64
}
func newFixturePublicDNS(kind string,listen func(*net.TCPAddr)(net.Listener,error))(*fixturePublicDNS,error){
	s:=&fixturePublicDNS{kind:kind,streams:make(map[net.Conn]bool)}
	if kind=="none"{return s,nil}
	virtual:=0;switch kind{case "dot":s.port=853;virtual=2853;case "doh":s.port=443;virtual=2443;default:return nil,fmt.Errorf("fixed DNS relay mode required")}
	for _,host:=range []string{"10.77.0.1","fd77::1"}{
		l,err:=listen(&net.TCPAddr{IP:net.ParseIP(host),Port:virtual});if err!=nil{s.Close();return nil,err};s.listeners=append(s.listeners,l);s.wg.Add(1)
		go func(l net.Listener){defer s.wg.Done();for{raw,err:=l.Accept();if err!=nil{return};s.mu.Lock();if s.closed||len(s.streams)>=32{s.mu.Unlock();raw.Close();s.refused.Add(1);continue};s.streams[raw]=true;s.wg.Add(1);s.mu.Unlock();go s.serve(raw)}}(l)
	}
	return s,nil
}
func(s *fixturePublicDNS)serve(raw net.Conn){
	defer s.wg.Done();var remote net.Conn
	defer func(){raw.Close();if remote!=nil{remote.Close()};s.mu.Lock();delete(s.streams,raw);if remote!=nil{delete(s.streams,remote)};s.mu.Unlock()}()
	until:=time.Now().Add(12*time.Second);raw.SetDeadline(until)
	// Only a bounded TLS handshake record may open the fixed upstream.
	first:=make([]byte,5);if _,err:=io.ReadFull(raw,first);err!=nil||first[0]!=22||first[1]!=3||first[2]>4||int(first[3])*256+int(first[4])>18432{s.refused.Add(1);return}
	var err error;remote,err=(&net.Dialer{Timeout:5*time.Second}).Dial("tcp",fmt.Sprintf("1.1.1.1:%d",s.port))
	if err!=nil{s.refused.Add(1);return};remote.SetDeadline(until)
	s.mu.Lock();if s.closed{s.mu.Unlock();return};s.streams[remote]=true;s.mu.Unlock()
	if n,err:=remote.Write(first);err!=nil||n!=len(first){s.refused.Add(1);return};s.upBytes.Add(5);s.opened.Add(1)
	done:=make(chan struct{});go func(){defer close(done);n,_:=io.Copy(remote,io.LimitReader(raw,65536));s.upBytes.Add(n);if tcp,ok:=remote.(*net.TCPConn);ok{tcp.CloseWrite()}}()
	n,_:=io.Copy(raw,io.LimitReader(remote,65536));s.downBytes.Add(n);raw.Close();remote.Close();<-done
}
func(s *fixturePublicDNS)Close(){
	s.mu.Lock();if !s.closed{s.closed=true;for _,l:=range s.listeners{l.Close()};for stream:=range s.streams{stream.Close()}};s.mu.Unlock();s.wg.Wait()
}
