// Source test copied into the exact mobile bridge snapshot by build.ps1.
package main

import (
    "encoding/json"
    "os"
    "path/filepath"
    "testing"
    "fmt"
    "strings"
    "wg-turn-client/internal/csqtt"
)

func TestCSQTTExtendedHashPoolSurvivesConfigAndLink(t *testing.T) {
    base:=bridgeConfig{Peer:"192.0.2.1:46000",Password:"fixture",DeviceID:"fixture-device",VKAnonPath:"vkcalls",Workers:18,
        Listen:"127.0.0.1:22001",SOCKS:"127.0.0.1:22002",SOCKSUser:"user",SOCKSPass:"pass",Obfs:"audio",CaptchaMode:"auto",Fingerprint:"chrome"}
    for i:=0;i<12;i++{base.Hashes=append(base.Hashes,fmt.Sprintf("fixture-call-%d-0123456789abcdef",i))}
    file:=filepath.Join(t.TempDir(),"private.json");data,_:=json.Marshal(base)
    if err:=os.WriteFile(file,data,0600);err!=nil{t.Fatal(err)}
    got,err:=readCSQTTConfig(file)
    if err!=nil||len(got.Hashes)!=12{t.Fatalf("private pool lost: %v",err)}
    link:="csqtt://connect?v=2&host=192.0.2.1&peer=46000&password=fixture&hashes="+strings.Join(base.Hashes,"+")
    parsed,err:=csqtt.ParseLink(link)
    if err!=nil||len(parsed.Hashes)!=12{t.Fatalf("link pool lost: %v",err)}
}

func TestCSQTTAccountOptionsRequirePrivateMatchingSession(t *testing.T) {
    base := bridgeConfig{Peer:"192.0.2.1:46000", Password:"fixture", DeviceID:"fixture-device",
        VKAnonPath:"auto_js", VKHashMode:"auto_js", VKRuntimeToken:"owned-fixture-token",
        Workers:126, Listen:"127.0.0.1:22001", SOCKS:"127.0.0.1:22002", SOCKSUser:"user", SOCKSPass:"pass",
        Obfs:"audio", CaptchaMode:"auto", Fingerprint:"firefox"}
    read := func(c bridgeConfig) (*bridgeConfig,error) {
        t.Helper()
        file:=filepath.Join(t.TempDir(),"private.json")
        data,err:=json.Marshal(c);if err!=nil {t.Fatal(err)}
        if err=os.WriteFile(file,data,0600);err!=nil {t.Fatal(err)}
        return readCSQTTConfig(file)
    }
    got,err:=read(base)
    if err!=nil || got.Workers!=126 {t.Fatalf("account session refused: %v",err)}
    for _,mutate:=range []func(*bridgeConfig){
        func(c *bridgeConfig){c.ProbeOnly=true},
        func(c *bridgeConfig){c.VKRuntimeToken=""},
        func(c *bridgeConfig){c.VKRuntimeToken="bad\ntoken"},
        func(c *bridgeConfig){c.VKHashMode="manual"},
        func(c *bridgeConfig){c.Hashes=[]string{"manual-call-0123456789"}},
        func(c *bridgeConfig){c.Workers=125},
        func(c *bridgeConfig){c.Fingerprint="unsupported"},
        func(c *bridgeConfig){c.VKAnonPath="vkcalls"},
    } {
        candidate:=base;mutate(&candidate)
        if _,err:=read(candidate);err==nil {t.Fatal("invalid private account session accepted")}
    }
}
