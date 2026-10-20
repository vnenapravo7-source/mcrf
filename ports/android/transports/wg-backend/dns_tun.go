package wgbackend

import (
	"errors"
	"github.com/sagernet/wireguard-go/tun"
)

// Underlying TUN is only the module-owned duplicate. There is no second TUN,
// root route/socket, local53 bind, public DNS fallback or packet stack here.
// The existing kernel owns both sides of DNS TCP, including retransmission.
type managedDnsTun struct { tun.Device; nat *dnsNat }

func (t *managedDnsTun) Read(bufs [][]byte,sizes []int,offset int)(int,error){
	// The caller sizes its batch for BOTH bind and TUN. StdNetBind may
	// advertise a larger batch than our one-packet fdTun; extra capacity is
	// valid. The underlying TUN still reports exactly one consumed packet.
	if len(bufs)<1||len(sizes)<1||offset<0{return 0,errors.New("WG_DNS_TUN_BATCH")}
	for {
		n,err:=t.Device.Read(bufs,sizes,offset);if err!=nil{return n,err};if n!=1||sizes[0]<0||offset+sizes[0]>len(bufs[0]){return 0,errors.New("WG_DNS_TUN_READ")}
		handled,reflections:=t.nat.reflect(bufs[0][offset:offset+sizes[0]],t.nat.clock())
		if !handled{return n,nil}
		for _,packet:=range reflections {if count,err:=t.Device.Write([][]byte{packet},0);err!=nil{return 0,err}else if count!=1{return 0,errors.New("WG_DNS_TUN_WRITE")}}
	}
}
func (t *managedDnsTun) Write(bufs [][]byte,offset int)(int,error){
	for index,packet:=range bufs {
		if offset<0||offset>=len(packet){return index,errors.New("WG_DNS_TUN_BUFFER")}
		// A remote peer MUST NOT impersonate an internal alias, even when its
		// ordinary AllowedIPs include every destination. Only our local Read
		// reflection writes these addresses through the underlying TUN directly.
		if internalDns(packet[offset:]){continue}
		if count,err:=t.Device.Write([][]byte{packet},offset);err!=nil{return index,err}else if count!=1{return index,errors.New("WG_DNS_TUN_WRITE")}
	}
	return len(bufs),nil
}
func(t *managedDnsTun) Close()error{t.nat.close();return t.Device.Close()}
