package main
import("bytes";"context";"encoding/binary";"io";"net";"os";"os/exec";"testing";"time")
func wireRoundtrip(t *testing.T,packet []byte)[]byte{t.Helper();binary:=os.Getenv("MCRF_CSQTT_WIRE_FIXTURE");if binary==""{t.Fatal("compile pinned Rust wire fixture and set MCRF_CSQTT_WIRE_FIXTURE")};cmd:=exec.Command(binary);cmd.Stdin=bytes.NewReader(packet);var stderr bytes.Buffer;cmd.Stderr=&stderr;result,err:=cmd.Output();if err!=nil{t.Fatalf("pinned fragment/reassembly: %v %s",err,stderr.String())};if !bytes.Equal(result,packet){t.Fatal("wire roundtrip changed packet")};return result}
func TestCSQTT1400LargeUDPRoundtrip(t *testing.T){
 if os.Getenv("MCRF_CSQTT_WIRE_FIXTURE")=="" {t.Skip("requires the pinned Rust protocol harness; see README")}
 raw,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)});if err!=nil{t.Fatal(err)};defer raw.Close()
 ctx,cancel:=context.WithCancel(context.Background());defer cancel();address:=freeTCP(t);done:=make(chan error,1);go func(){done<-runWithTransportMTU(ctx,raw.LocalAddr().String(),"10.66.66.2","1.1.1.1",address,1400,true)}()
 control:=connect(t,address);defer control.Close();control.Write([]byte{5,1,0});reply:=make([]byte,2);if _,err=io.ReadFull(control,reply);err!=nil||reply[1]!=0{t.Fatal("auth",err)};control.Write([]byte{5,3,0,1,0,0,0,0,0,0});reply=make([]byte,10);if _,err=io.ReadFull(control,reply);err!=nil||reply[1]!=0{t.Fatal("associate",err)}
 client,err:=net.DialUDP("udp4",nil,&net.UDPAddr{IP:net.IP(reply[4:8]),Port:int(binary.BigEndian.Uint16(reply[8:10]))});if err!=nil{t.Fatal(err)};defer client.Close()
 for _,size:=range []int{1253,1272,1300,1372}{
  payload:=make([]byte,size);for i:=range payload{payload[i]=byte((i*31+size)%251)};client.SetDeadline(time.Now().Add(5*time.Second));raw.SetDeadline(time.Now().Add(5*time.Second))
  if _,err=client.Write(append([]byte{0,0,0,1,203,0,113,40,0x69,0x87},payload...));err!=nil{t.Fatal(err)}
  packet:=make([]byte,65536);n,peer,err:=raw.ReadFromUDP(packet);if err!=nil{t.Fatal(err)};packet=packet[:n];h:=int(packet[0]&15)*4
  if n!=size+28||binary.BigEndian.Uint16(packet[6:8])&0x3fff!=0||packet[9]!=17||!bytes.Equal(packet[h+8:],payload){t.Fatalf("payload %d was IP fragmented or changed: packet %d",size,n)}
  packet=wireRoundtrip(t,packet)
  for i:=0;i<4;i++{packet[12+i],packet[16+i]=packet[16+i],packet[12+i]};packet[h],packet[h+2]=packet[h+2],packet[h];packet[h+1],packet[h+3]=packet[h+3],packet[h+1];packet[h+6],packet[h+7]=0,0;packet[10],packet[11]=0,0
  var sum uint32;for i:=0;i<h;i+=2{sum+=uint32(binary.BigEndian.Uint16(packet[i:i+2]))};for sum>65535{sum=(sum&65535)+(sum>>16)};binary.BigEndian.PutUint16(packet[10:12],^uint16(sum));packet=wireRoundtrip(t,packet)
  if _,err=raw.WriteToUDP(packet,peer);err!=nil{t.Fatal(err)};answer:=make([]byte,65536);n,err=client.Read(answer);if err!=nil||n!=size+10||!bytes.Equal(answer[10:n],payload){t.Fatalf("return payload %d: bytes %d err %v",size,n,err)};t.Logf("UDP payload %d / IP %d: no IP fragments, pinned CSQTT reassembly exact both directions",size,size+28)
 }
 cancel();select{case err=<-done:if err!=nil{t.Fatal(err)};case<-time.After(3*time.Second):t.Fatal("shutdown timeout")}
}
