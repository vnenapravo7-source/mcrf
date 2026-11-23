package libbox

import (
	"errors"
	"net/netip"
	"strings"
)

// INTERNAL source patch for the external dispatcher, not the compact WG
// module or base APK. No profile mutation, hostname lookup or address-family
// fallback. Preserve each assigned host address (never Prefix.Masked()).
type mcrfPipeSpec struct { mtu, ownerPID int; addresses []netip.Prefix }

func parseMcrfPipeSpec(mtu, ownerPID int, text string)(mcrfPipeSpec,error){
	var result mcrfPipeSpec
	if mtu<1280||mtu>9000||ownerPID<=0||len(text)<3||len(text)>512{return result,errors.New("MCRF_PIPE_INPUT")}
	parts:=strings.Split(text,",")
	if len(parts)<1||len(parts)>8{return result,errors.New("MCRF_PIPE_ADDRESSES")}
	seen:=make(map[netip.Addr]bool)
	for _,part:=range parts{
		if len(part)>64||part!=strings.TrimSpace(part){return result,errors.New("MCRF_PIPE_ADDRESSES")}
		prefix,err:=netip.ParsePrefix(part)
		if err!=nil{return result,errors.New("MCRF_PIPE_ADDRESSES")}
		address:=prefix.Addr()
		if address.Is4In6()||address.Zone()!=""||address.IsUnspecified()||address.IsMulticast()||seen[address]{return result,errors.New("MCRF_PIPE_ADDRESSES")}
		seen[address]=true;result.addresses=append(result.addresses,prefix)
	}
	result.mtu=mtu;result.ownerPID=ownerPID;return result,nil
}

func validMcrfPipePacket(packet []byte,mtu int)bool{
	if len(packet)<20||len(packet)>mtu{return false}
	switch packet[0]>>4{
	case 4:
		header:=int(packet[0]&15)*4
		return header>=20&&header<=len(packet)&&(int(packet[2])<<8|int(packet[3]))==len(packet)
	case 6:return len(packet)>=40&&40+(int(packet[4])<<8|int(packet[5]))==len(packet)
	default:return false
	}
}
