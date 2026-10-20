package main

import (
	"encoding/binary"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// Independent peer: exact nonce DNS only, never recursion/connect-out. New TCP
// transport shares bounded lifetime/closure with UDP; no old test result reused.
type managedFixtureDNS struct {
	mu sync.Mutex
	closed bool
	streams map[net.Conn]bool
	listeners []net.Listener
	packets []net.PacketConn
	wg sync.WaitGroup
	udp4,udp6,tcp4,tcp6,refused atomic.Int64
	nonce string
}
func newManagedFixtureDNS(nonce string,udp func(*net.UDPAddr)(net.PacketConn,error),tcp func(*net.TCPAddr)(net.Listener,error))(*managedFixtureDNS,error){
	d:=&managedFixtureDNS{nonce:nonce,streams:make(map[net.Conn]bool)}
	for _,host:=range []string{"10.77.0.1","fd77::1"}{
		ip:=net.ParseIP(host);v6:=ip.To4()==nil
		packet,err:=udp(&net.UDPAddr{IP:ip,Port:53});if err!=nil{d.Close();return nil,err};d.packets=append(d.packets,packet)
		listener,err:=tcp(&net.TCPAddr{IP:ip,Port:53});if err!=nil{d.Close();return nil,err};d.listeners=append(d.listeners,listener)
		d.wg.Add(2)
		go func(socket net.PacketConn,family6 bool){defer d.wg.Done();buffer:=make([]byte,513)
			for{n,remote,err:=socket.ReadFrom(buffer);if err!=nil{return};reply:=fixtureDNS(buffer[:n],nonce);if reply==nil{d.refused.Add(1);continue}
				if _,err=socket.WriteTo(reply,remote);err==nil{if family6{d.udp6.Add(1)}else{d.udp4.Add(1)}}}
		}(packet,v6)
		go func(listener net.Listener,family6 bool){defer d.wg.Done()
			for{stream,err:=listener.Accept();if err!=nil{return}
				d.mu.Lock();if d.closed||len(d.streams)>=8{d.mu.Unlock();stream.Close();continue};d.streams[stream]=true;d.wg.Add(1);d.mu.Unlock()
				go d.serve(stream,family6)
			}
		}(listener,v6)
	}
	return d,nil
}
func(d *managedFixtureDNS)serve(stream net.Conn,v6 bool){
	defer d.wg.Done();defer func(){stream.Close();d.mu.Lock();delete(d.streams,stream);d.mu.Unlock()}()
	stream.SetDeadline(time.Now().Add(5*time.Second))
	for count:=0;count<16;count++{
		var prefix [2]byte;if _,err:=io.ReadFull(stream,prefix[:]);err!=nil{return};size:=binary.BigEndian.Uint16(prefix[:]);if size!=58{d.refused.Add(1);return}
		query:=make([]byte,size);if _,err:=io.ReadFull(stream,query);err!=nil{return};reply:=fixtureDNS(query,d.nonce);if reply==nil{d.refused.Add(1);return}
		binary.BigEndian.PutUint16(prefix[:],uint16(len(reply)));frame:=append(prefix[:],reply...);if count,err:=stream.Write(frame);err!=nil||count!=len(frame){return}
		if v6{d.tcp6.Add(1)}else{d.tcp4.Add(1)}
	}
}
func(d *managedFixtureDNS)Close(){
	d.mu.Lock();if d.closed{d.mu.Unlock();return};d.closed=true;streams:=make([]net.Conn,0,len(d.streams));for stream:=range d.streams{streams=append(streams,stream)};d.mu.Unlock()
	for _,listener:=range d.listeners{listener.Close()};for _,packet:=range d.packets{packet.Close()};for _,stream:=range streams{stream.Close()};d.wg.Wait()
}
func(d *managedFixtureDNS)total()int64{return d.udp4.Load()+d.udp6.Load()+d.tcp4.Load()+d.tcp6.Load()}
