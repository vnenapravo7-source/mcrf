package libbox

import "testing"

// Pure policy only. Does NOT prove stack, private fd, WG crypto or Android.
func TestMcrfPipeSpecKeepsAssignedAddressesAndBothFamilies(t *testing.T){
	spec,err:=parseMcrfPipeSpec(1420,42,"10.89.0.2/24,fd89::2/64")
	if err!=nil||spec.addresses[0].String()!="10.89.0.2/24"||spec.addresses[1].String()!="fd89::2/64"{t.Fatal("assigned addresses changed")}
}
func TestMcrfPipeSpecRejectsImplicitDnsMappedAndDuplicateAddresses(t *testing.T){
	for _,text:=range []string{"owned.test/24","0.0.0.0/32","::/128","ff02::1/128","::ffff:192.0.2.1/128","10.0.0.2/24,10.0.0.2/32"," 10.0.0.2/32","10.0.0.2/32,"}{
		if _,err:=parseMcrfPipeSpec(1280,42,text);err==nil{t.Fatal("invalid assigned address accepted")}
	}
	if _,err:=parseMcrfPipeSpec(1279,42,"10.0.0.2/32");err==nil{t.Fatal("invalid MTU accepted")}
	if _,err:=parseMcrfPipeSpec(1280,0,"10.0.0.2/32");err==nil{t.Fatal("unowned channel accepted")}
}
func TestMcrfPipeFrameIsWholeIpNotAnImplicitStream(t *testing.T){
	for _,family:=range []byte{4,6}{
		packet:=make([]byte,60);packet[0]=family<<4;if family==4{packet[0]|=5;packet[3]=60}else{packet[5]=20}
		if !validMcrfPipePacket(packet,1280){t.Fatal("whole assigned family packet rejected")}
		if validMcrfPipePacket(packet[:59],1280)||validMcrfPipePacket(append(packet,0),1280){t.Fatal("partial or merged packet admitted")}
	}
}
