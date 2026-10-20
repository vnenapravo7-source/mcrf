package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strings"

	legacyconn "github.com/amnezia-vpn/amneziawg-go/conn"
	legacydevice "github.com/amnezia-vpn/amneziawg-go/device"
	legacytun "github.com/amnezia-vpn/amneziawg-go/tun"
	nativeconn "github.com/sagernet/wireguard-go/conn"
	nativedevice "github.com/sagernet/wireguard-go/device"
	nativetun "github.com/sagernet/wireguard-go/tun"
)

// Keep the existing independent, in-memory IP stack. Only AWG3/3.1 need the
// pinned newer wire implementation; no OS TUN, routes, root or Internet relay.
type fixturePeerDevice interface { IpcSet(string) error; IpcGet()(string,error); Up()error; Close() }
type nativeTunBridge struct { legacytun.Device; events chan nativetun.Event }
func bridgeNativeTun(t legacytun.Device)*nativeTunBridge {
	b:=&nativeTunBridge{Device:t,events:make(chan nativetun.Event,16)}
	go func(){defer close(b.events);for event:=range t.Events(){b.events<-nativetun.Event(event)}}()
	return b
}
func(b *nativeTunBridge)Events()<-chan nativetun.Event{return b.events}
func newFixturePeerDevice(version string,t *auditedTun)fixturePeerDevice {
	if version=="awg3"||version=="awg3_1" {
		return nativedevice.NewDevice(context.Background(),bridgeNativeTun(t),nativeconn.NewStdNetBind(nil),nativedevice.NewLogger(nativedevice.LogLevelSilent,""),1)
	}
	return legacydevice.NewDevice(t,legacyconn.NewDefaultBind(),legacydevice.NewLogger(legacydevice.LogLevelSilent,""))
}
func fixtureParameters(version string)(params,conf string,err error){
	switch version {
	case "wg":return "","",nil
	case "awg1","awg2":
		conf="Jc = 3\nJmin = 10\nJmax = 30\nS1 = 16\nS2 = 20\nH1 = 12341\nH2 = 12441\nH3 = 12541\nH4 = 12641\n"
		if version=="awg2"{conf+="S3 = 12\nS4 = 12\nI1 = <b 0x01020304><r 8>\n"}
	case "awg3","awg3_1":
		conf="Jc = 0\nJmin = 40\nJmax = 80\nS1 = 16\nS2 = 20\nS3 = 16\nS4 = 16\nH1 = 123456-123460\nH2 = 234567-234570\nH3 = 345678-345680\nH4 = 456789-456790\nI1 = <r 8>\n"
		// Synthetic public fixture header key, not a user/private WireGuard key.
		key,_:=hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
		conf+="HeaderProtectionKey = "+base64.StdEncoding.EncodeToString(key)+"\nContentPaddingAddition = 0-8\n"
		if version=="awg3_1"{conf+="RandomTrailers = true\nDisableCookies = true\nRekeyAfterTime = 120\nRekeyTimeout = 5\nRejectAfterTime = 180\nKeepaliveTimeout = 10\nMaxHandshakeAttempts = 5\n"}
	default:return "","",fmt.Errorf("unsupported fixture version")
	}
	names:=map[string]string{"headerprotectionkey":"header_protection_key","contentpaddingaddition":"content_padding_addition","randomtrailers":"random_trailers","disablecookies":"disable_cookies","rekeyaftertime":"rekey_after_time","rekeytimeout":"rekey_timeout","rejectaftertime":"reject_after_time","keepalivetimeout":"keepalive_timeout","maxhandshakeattempts":"max_handshake_attempts"}
	for _,line:=range strings.Split(strings.TrimSpace(conf),"\n"){
		pair:=strings.SplitN(line,"=",2);name:=strings.ToLower(strings.TrimSpace(pair[0]));value:=strings.TrimSpace(pair[1])
		if name=="headerprotectionkey"{raw,decodeErr:=base64.StdEncoding.DecodeString(value);if decodeErr!=nil{return "","",decodeErr};value=hex.EncodeToString(raw)}
		if mapped,ok:=names[name];ok{name=mapped};params+=name+"="+value+"\n"
	}
	return params,conf,nil
}
