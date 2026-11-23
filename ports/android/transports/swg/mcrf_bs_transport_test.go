// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "bufio"
    "context"
    "encoding/json"
    "fmt"
    "io"
    "os"
    "os/exec"
    "path/filepath"
    "strings"
    "testing"
    "time"
)

func TestBSTransportDefaultPreserved(t *testing.T) {
    t.Setenv("MCRF_BS_TRANSPORT", "")
    t.Setenv("MCRF_BS_MODE", "")
    got, err := mcrfBSTransport("libcsqtt_transport.so")
    if err != nil || got != "libcsqtt_transport.so" { t.Fatal("legacy default changed") }
}

func TestBSTransportRejectsExternalPath(t *testing.T) {
    t.Setenv("MCRF_BS_MODE", "")
    for _, name := range []string{"../lib.so", "/data/local/tmp/client", "libqwdtt.so", "libcsqtt252_transport.so"} {
        t.Setenv("MCRF_BS_TRANSPORT", name)
        if _, err := mcrfBSTransport("libcsqtt_transport.so"); err == nil { t.Fatal("untrusted path accepted") }
    }
}

func TestBSModeRejectsMismatch(t *testing.T) {
    for _, mode := range []string{"csqtt", "csqtt20", "csqtt215", "csqtt25", "qwdtt"} {
        flag, err := mcrfBSModeFlag(mode)
        if err != nil || mcrfBSValidateMode(mode, "", []string{"exe", flag, "private.json"}) != nil { t.Fatal(mode) }
        for _, args := range [][]string{{"exe", "--mcrf-config", "private.json"}, {"exe", flag, ""}, {"exe", flag, "private.json", "-turn", "other"}} {
            if mcrfBSValidateMode(mode, "", args) == nil { t.Fatal("CLI escaped host mode", mode) }
        }
        if mcrfBSValidateMode(mode, "libcsqtt_transport.so", []string{"exe", flag, "private.json"}) == nil { t.Fatal("ambiguous selectors") }
    }
    for _, mode := range []string{"wdtt", "../csqtt", "CSQTT", "csqtt25 ", "/data/app/client"} {
        if mcrfBSValidateMode(mode, "", []string{"exe", "-config", "private.json"}) == nil { t.Fatal("unknown host mode") }
    }
    if mcrfBSValidateMode("", "", []string{"standalone", "-help"}) != nil { t.Fatal("standalone CLI changed") }
    for mode, want := range map[string]string{"csqtt":"libcsqtt_transport.so", "csqtt20":"libcsqtt20_transport.so", "csqtt215":"libcsqtt215_transport.so"} {
        got, err := mcrfBSLegacyName(mode, "", "unused")
        if err != nil || got != want { t.Fatal("generation lost", mode) }
    }
    if _, err := mcrfBSLegacyName("csqtt25", "", "libcsqtt_transport.so"); err == nil { t.Fatal("modern fell back to legacy") }
}

func TestBSRustModeIsolation(t *testing.T) {
    for mode,want:=range map[string]string{"csqtt":"2.1.9","csqtt20":"2.0","csqtt215":"2.1.5","qwdtt":"","csqtt25":"2.5.3"} {
        if got,err:=mcrfBSRustMode("rust-multicall-v1",mode,want);err!=nil||got!=want{t.Fatal(mode,got,err)}
        if _,err:=mcrfBSRustMode("rust-multicall-v1",mode,"from-import");err==nil{t.Fatal("untrusted generation")}
    }
    if _,err:=mcrfBSRustMode("unknown","csqtt","2.1.9");err==nil{t.Fatal("unknown layout")}
}

func TestBSRustChildEnv(t *testing.T) {
    t.Setenv("MCRF_BS_LAYOUT","rust-multicall-v1");t.Setenv("MCRF_BS_MODE","csqtt215");t.Setenv("MCRF_BS_RUST_MODE","2.1.5")
    inherited:=[]string{"MCRF_BS_RUST_MODE=2.0","MCRF_BS_RUST_MODE=untrusted","MCRF_BS_MODE=csqtt20","MCRF_BS_LAYOUT=old","XDG_CACHE_HOME=private"}
    child,err:=mcrfBSChildEnv(inherited);if err!=nil{t.Fatal(err)}
    if strings.Join(child,"|")!="XDG_CACHE_HOME=private|MCRF_BS_RUST_MODE=2.1.5"{t.Fatal("selector leak",child)}
    if inherited[0]!="MCRF_BS_RUST_MODE=2.0"{t.Fatal("mutated parent env")}
    if os.Getenv("MCRF_BS_RUST_MODE")!="2.1.5"{t.Fatal("process env changed")}
}

func TestBSLegacyUnderlayKeepsOnlyTheTrustedChildNetwork(t *testing.T) {
    t.Setenv("MCRF_BS_LAYOUT","rust-multicall-v1")
    t.Setenv("MCRF_BS_MODE","csqtt215")
    t.Setenv("MCRF_BS_RUST_MODE","2.1.5")
    t.Setenv(mcrfLegacyUnderlayEnv,"4295037282")
    child,err:=mcrfBSChildEnv([]string{mcrfLegacyUnderlayEnv+"=1"})
    if err!=nil||strings.Join(child,"|")!="MCRF_BS_RUST_MODE=2.1.5|"+mcrfLegacyUnderlayEnv+"=4295037282" {
        t.Fatal("wrong private child network")
    }
    for _,value:=range []string{"","+1","01","0","9223372036854775808"} {
        t.Setenv(mcrfLegacyUnderlayEnv,value)
        if err:=mcrfBSValidateLaunch([]string{"wrapper","-csqtt-config","private.json"});err==nil {
            t.Fatal("admitted a malformed physical network")
        }
    }
    t.Setenv(mcrfLegacyUnderlayEnv,"4295037282")
    t.Setenv("MCRF_BS_MODE","csqtt25")
    t.Setenv("MCRF_BS_RUST_MODE","2.5.3")
    if _,err:=mcrfBSChildEnv(nil);err==nil {t.Fatal("allowed a modern child to use the legacy network selector")}
}

func TestBSModernMismatch(t *testing.T) {
    t.Setenv("MCRF_BS_LAYOUT","rust-multicall-v1");t.Setenv("MCRF_BS_MODE","csqtt25");t.Setenv("MCRF_BS_RUST_MODE","2.5.3")
    if err:=mcrfBSModernImplementation("swg-panel-2.5.3");err!=nil{t.Fatal(err)}
    for _,other:=range []string{"","swg-panel-2.5.1","swg-panel-2.5.2","../../2.5.3"}{if mcrfBSModernImplementation(other)==nil{t.Fatal("wrong generation accepted")}}
}

func bsModeFixture(t *testing.T, mode string) string {
    t.Helper()
    c := map[string]any{"peer":"127.0.0.1:46000", "password":"fixture", "hashes":[]string{"fixture-call-0123456789abcdef"},
        "workers":18, "device_id":"fixture-"+mode, "listen":"127.0.0.1:22001", "socks":"127.0.0.1:22002",
        "socks_user":"fixture-user", "socks_pass":"fixture-pass", "captcha_mode":"wv", "obfs":"audio", "vk_anon_path":"vkcalls",
        "mode":"raw", "fingerprint":"chrome"}
    if mode == "csqtt25" {
        delete(c, "mode"); delete(c, "vk_anon_path")
        c["csqtt_generation"]="2.5"; c["workers"]=7; c["exact_workers"]=true
        c["vk_hash_mode"]="manual"; c["vk_auth_mode"]="vkcalls"; c["turn_transport"]="tcp"; c["obfs"]="unified"
    }
    data, err := json.Marshal(c); if err != nil { t.Fatal(err) }
    path := filepath.Join(t.TempDir(), "config.json")
    if err = os.WriteFile(path, data, 0600); err != nil { t.Fatal(err) }
    return path
}

func bsModeRead(mode, path string) (int, error) {
    switch mode {
    case "qwdtt": c,e:=readBridgeConfig(path); if e!=nil{return 0,e}; return c.Workers,nil
    case "csqtt25":
        f,e:=os.Open(path);if e!=nil{return 0,e};defer f.Close()
        _,c,e:=readCSQTT25Bridge(f);if e!=nil{return 0,e};return c.Workers,nil
    default: c,e:=readCSQTTConfig(path);if e!=nil{return 0,e};return c.Workers,nil
    }
}

func TestBSModeSupersetProfiles(t *testing.T) {
    for _, mode := range []string{"csqtt", "csqtt20", "csqtt215", "csqtt25", "qwdtt"} {
        path:=bsModeFixture(t,mode);got,err:=bsModeRead(mode,path)
        want:=18;if mode=="csqtt25"{want=7}
        if err!=nil||got!=want {t.Fatalf("%s settings changed: %d %v",mode,got,err)}
        if mode=="csqtt25" {if _,e:=readCSQTTConfig(path);e==nil{t.Fatal("modern profile accepted as legacy")}}
        if mode=="csqtt" {f,e:=os.Open(path);if e!=nil{t.Fatal(e)};_,_,e=readCSQTT25Bridge(f);f.Close();if e==nil{t.Fatal("legacy reinterpreted as modern")}}
    }
}

// Real OS child processes keep independent environment, private config/cache and
// parser state. They overlap until the parent explicitly releases all of them.
// No sockets, provider requests or external network are used by these checks.
func TestBSModeChild(t *testing.T) {
    path:=os.Getenv("MCRF_BS_TEST_CONFIG");if path==""{return}
    mode:=os.Getenv("MCRF_BS_MODE");flag,err:=mcrfBSModeFlag(mode);if err!=nil{t.Fatal(err)}
    if err=mcrfBSValidateLaunch([]string{os.Args[0],flag,path});err!=nil{t.Fatal(err)}
    workers,err:=bsModeRead(mode,path);if err!=nil{t.Fatal(err)}
    selected:=""
    if os.Getenv("MCRF_BS_LAYOUT")=="rust-multicall-v1" {
        selected,err=mcrfBSRustMode("rust-multicall-v1",mode,os.Getenv("MCRF_BS_RUST_MODE"));if err!=nil{t.Fatal(err)}
        childEnv,err:=mcrfBSChildEnv([]string{"MCRF_BS_RUST_MODE=poison","MCRF_BS_MODE=poison"});if err!=nil{t.Fatal(err)}
        want:="";if selected!=""{want="MCRF_BS_RUST_MODE="+selected};if strings.Join(childEnv,"")!=want{t.Fatal("child selector isolation failed")}
        if mode=="csqtt25" {
            data,err:=os.ReadFile(path);if err!=nil{t.Fatal(err)};var fields map[string]any
            if err=json.Unmarshal(data,&fields);err!=nil{t.Fatal(err)}
            implementation,_:=fields["csqtt_implementation"].(string)
            if err=mcrfBSModernImplementation(implementation);err!=nil{t.Fatal(err)}
        }
    }
    fmt.Printf("READY|%s|%d|%s\n",mode,workers,os.Getenv("XDG_CACHE_HOME"))
    if os.Getenv("MCRF_BS_LAYOUT")=="rust-multicall-v1"{fmt.Printf("RUST|%s\n",selected)}
    input:=bufio.NewScanner(os.Stdin)
    if !input.Scan()||input.Text()!="STOP"{t.Fatal("missing owned stop")}
}

func TestBSModeParallelProcesses(t *testing.T) {
    ctx,cancel:=context.WithTimeout(context.Background(),20*time.Second);defer cancel()
    type child struct{cmd *exec.Cmd;read *bufio.Reader;stop func()}
    children:=[]child{}
    for _,mode:=range []string{"csqtt", "csqtt20", "csqtt215", "csqtt25", "qwdtt"} {
        path:=bsModeFixture(t,mode);cmd:=exec.CommandContext(ctx,os.Args[0],"-test.run=^TestBSModeChild$")
        for _,row:=range os.Environ(){if !strings.HasPrefix(row,"MCRF_BS_")&&!strings.HasPrefix(row,"XDG_CACHE_HOME="){cmd.Env=append(cmd.Env,row)}}
        cmd.Env=append(cmd.Env,"MCRF_BS_MODE="+mode,"MCRF_BS_TEST_CONFIG="+path,"XDG_CACHE_HOME="+filepath.Dir(path))
        output,err:=cmd.StdoutPipe();if err!=nil{t.Fatal(err)};input,err:=cmd.StdinPipe();if err!=nil{t.Fatal(err)}
        cmd.Stderr=os.Stderr
        if err=cmd.Start();err!=nil{t.Fatal(err)}
        t.Cleanup(func(){if cmd.ProcessState==nil{_ = cmd.Process.Kill();_ = cmd.Wait()}})
        workers:=18;if mode=="csqtt25"{workers=7}
        expected:=fmt.Sprintf("READY|%s|%d|%s\n",mode,workers,filepath.Dir(path))
        children=append(children,child{cmd,bufio.NewReader(output),func(){fmt.Fprintln(input,"STOP");input.Close()}})
        line,err:=children[len(children)-1].read.ReadString('\n');if err!=nil||line!=expected{t.Fatalf("cross-process mode leak: %q %v",line,err)}
    }
    for _,c:=range children{c.stop()}
    for _,c:=range children{if err:=c.cmd.Wait();err!=nil{t.Fatal(err)}}
}

func TestBSRustParallelProcesses(t *testing.T) {
    ctx,cancel:=context.WithTimeout(context.Background(),20*time.Second);defer cancel()
    type child struct{cmd *exec.Cmd;input io.WriteCloser}
    children:=[]child{}
    for _,pair:=range [][2]string{{"csqtt","2.1.9"},{"csqtt20","2.0"},{"csqtt215","2.1.5"},{"csqtt25","2.5.1"},{"csqtt25","2.5.2"},{"csqtt25","2.5.3"},{"qwdtt",""}} {
        mode,generation:=pair[0],pair[1];path:=bsModeFixture(t,mode)
        if mode=="csqtt25"{data,_:=os.ReadFile(path);var fields map[string]any;_ = json.Unmarshal(data,&fields);fields["csqtt_implementation"]="swg-panel-"+generation;data,_=json.Marshal(fields);if err:=os.WriteFile(path,data,0600);err!=nil{t.Fatal(err)}}
        cmd:=exec.CommandContext(ctx,os.Args[0],"-test.run=^TestBSModeChild$")
        for _,row:=range os.Environ(){if !strings.HasPrefix(row,"MCRF_BS_")&&!strings.HasPrefix(row,"XDG_CACHE_HOME="){cmd.Env=append(cmd.Env,row)}}
        cmd.Env=append(cmd.Env,"MCRF_BS_LAYOUT=rust-multicall-v1","MCRF_BS_MODE="+mode,"MCRF_BS_RUST_MODE="+generation,"MCRF_BS_TEST_CONFIG="+path,"XDG_CACHE_HOME="+filepath.Dir(path))
        output,err:=cmd.StdoutPipe();if err!=nil{t.Fatal(err)};input,err:=cmd.StdinPipe();if err!=nil{t.Fatal(err)}
        cmd.Stderr=os.Stderr;if err=cmd.Start();err!=nil{t.Fatal(err)}
        t.Cleanup(func(){if cmd.ProcessState==nil{_ = cmd.Process.Kill();_ = cmd.Wait()}})
        workers:=18;if mode=="csqtt25"{workers=7};reader:=bufio.NewReader(output)
        ready,err:=reader.ReadString('\n');if err!=nil||ready!=fmt.Sprintf("READY|%s|%d|%s\n",mode,workers,filepath.Dir(path)){t.Fatal("profile/cache isolation failed",ready,err)}
        selected,err:=reader.ReadString('\n');if err!=nil||selected!="RUST|"+generation+"\n"{t.Fatal("generation isolation failed",selected,err)}
        children=append(children,child{cmd,input})
    }
    for _,child:=range children{fmt.Fprintln(child.input,"STOP");child.input.Close()}
    for _,child:=range children{if err:=child.cmd.Wait();err!=nil{t.Fatal(err)}}
}
