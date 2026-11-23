// SPDX-License-Identifier: GPL-3.0-or-later
package main
import("encoding/json";"os";"path/filepath";"testing";"fmt")
func TestMcrfSixCallBridge(t *testing.T){
 hashes:=[]string{"fixture-call-1-0123456789abcdef","fixture-call-2-0123456789abcdef","fixture-call-3-0123456789abcdef","fixture-call-4-0123456789abcdef","fixture-call-5-0123456789abcdef","fixture-call-6-0123456789abcdef"}
 c:=bridgeConfig{Peer:"127.0.0.1:56003",Password:"fixture password",Hashes:hashes,Mode:"raw",Workers:162,DeviceID:"fixture-device",Listen:"127.0.0.1:22001",SOCKS:"127.0.0.1:22002",SOCKSUser:"user",SOCKSPass:"pass"}
 path:=filepath.Join(t.TempDir(),"config.json");b,_:=json.Marshal(c);if e:=os.WriteFile(path,b,0600);e!=nil{t.Fatal(e)}
 parsed,e:=readBridgeConfig(path);if e!=nil||len(parsed.Hashes)!=6||parsed.Workers!=162{t.Fatalf("six-call profile: %v",e)}
 c.Workers=18;c.Hashes=nil;for i:=0;i<64;i++{c.Hashes=append(c.Hashes,fmt.Sprintf("fixture-call-%d-0123456789abcdef",i))};b,_=json.Marshal(c);os.WriteFile(path,b,0600)
 parsed,e=readBridgeConfig(path);if e!=nil||len(parsed.Hashes)!=64||parsed.Workers!=18{t.Fatalf("full reserve pool lost: %v",e)}
 c.Hashes=append(c.Hashes,"fixture-call-65-0123456789abcdef");b,_=json.Marshal(c);os.WriteFile(path,b,0600)
 if parsed,e=readBridgeConfig(path);e!=nil||len(parsed.Hashes)!=65{t.Fatal("reserve pool truncated")}
}
func TestMcrfQwdttExtendedHashPool(t *testing.T){
 c:=bridgeConfig{Peer:"127.0.0.1:56003",Password:"fixture",Mode:"raw",Workers:18,DeviceID:"fixture-device",Listen:"127.0.0.1:22001",SOCKS:"127.0.0.1:22002",SOCKSUser:"user",SOCKSPass:"pass"}
 for i:=0;i<80;i++{c.Hashes=append(c.Hashes,fmt.Sprintf("fixture-call-%d-0123456789abcdef",i))}
 path:=filepath.Join(t.TempDir(),"config.json");data,_:=json.Marshal(c)
 if e:=os.WriteFile(path,data,0600);e!=nil{t.Fatal(e)}
 got,e:=readBridgeConfig(path);if e!=nil||len(got.Hashes)!=80||got.Hashes[79]!=c.Hashes[79]{t.Fatalf("extended pool lost: %v",e)}
}
