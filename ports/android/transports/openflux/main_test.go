// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "context"
    "encoding/json"
    "os"
    "path/filepath"
    "strings"
    "testing"
    "time"
)

func TestMcrfStartupCancellationDoesNotWaitForHandshake(t *testing.T) {
    ctx, cancel := context.WithCancel(context.Background())
    entered, release, exited := make(chan struct{}), make(chan struct{}), make(chan struct{})
    result := make(chan bool, 1)
    go func() { _, cancelled := awaitProxyStartup(ctx, func() string {
        close(entered); <-release; close(exited); return ""
    }); result <- cancelled }()
    <-entered; cancel()
    select {case cancelled := <-result: if !cancelled {t.Fatal("cancelled startup accepted")}
    case <-time.After(time.Second): close(release); t.Fatal("STOP waited for handshake")}
    close(release)
    select {case <-exited: case <-time.After(time.Second): t.Fatal("synthetic start did not finish")}
}

func TestMcrfStartupResultAndPreCancelledAdmission(t *testing.T) {
    for _, message := range []string{"", "synthetic failure"} {
        got, cancelled := awaitProxyStartup(context.Background(), func() string {return message})
        if cancelled || got != message {t.Fatal("startup result lost")}
    }
    ctx, cancel := context.WithCancel(context.Background()); cancel()
    called := false
    _, cancelled := awaitProxyStartup(ctx, func() string {called=true;return ""})
    if !cancelled || called {t.Fatal("pre-cancelled startup was launched")}
}

func TestPrivateConfiguration(t *testing.T) {
    base := map[string]any{"secret":"fixture encryption secret", "negotiate":true,
        "transports":[]any{map[string]any{"type":"direct", "dial":"127.0.0.1:25444"}},
        "socks":"127.0.0.1:24444", "socks_user":"fixture-user", "socks_pass":"fixture-password"}
    path := filepath.Join(t.TempDir(), "profile.json")
    cases := []struct{name,field string;value any;valid bool}{
        {"authenticated", "socks", "127.0.0.1:24444", true},
        {"public", "socks", "0.0.0.0:24444", false},
        {"missing password", "socks_pass", "", false},
        {"oversized user", "socks_user", strings.Repeat("x",256), false},
        {"bad key", "secret", "short", false},
        {"bad port", "socks", "127.0.0.1:0", false},
    }
    for _, test := range cases { t.Run(test.name,func(t *testing.T){
        value:=map[string]any{};for key,data:=range base{value[key]=data};value[test.field]=test.value
        data,_:=json.Marshal(value);if err:=os.WriteFile(path,data,0600);err!=nil{t.Fatal(err)}
        parsed,err:=readConfig(path);if (err==nil)!=test.valid{t.Fatalf("configuration acceptance=%v, want %v",err==nil,test.valid)}
        if parsed!=nil && (parsed.User!="fixture-user"||parsed.Secret!="fixture encryption secret"){t.Fatal("private values not preserved")}
        if err!=nil&&(strings.Contains(err.Error(),"fixture-password")||strings.Contains(err.Error(),"encryption secret")){t.Fatal("secret in error")}
    })}
    if err:=os.WriteFile(path,[]byte(strings.Repeat("x",65537)),0600);err!=nil{t.Fatal(err)}
    if _,err:=readConfig(path);err==nil{t.Fatal("unbounded profile accepted")}
}
