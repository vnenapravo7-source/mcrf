// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "crypto/sha256"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "errors"
    "strings"
    "sync"
    "sync/atomic"
    "testing"
    "time"
    mobile "openflux-mobile"
)

func mcrfWaitDocument(t *testing.T,state *mcrfDocumentRun) {
    t.Helper();deadline:=time.Now().Add(time.Second)
    for {
        state.mu.Lock();busy:=state.busy;state.mu.Unlock()
        if !busy {return}
        if time.Now().After(deadline) {t.Fatal("synthetic document worker did not drain")}
        time.Sleep(time.Millisecond)
    }
}
func mcrfDocumentFixture(raw []byte)(*mcrfDocumentRun,string,*[]string,*[][]byte) {
    id:=strings.Repeat("a",32);digest:=sha256.Sum256(raw)
    meta:=mobile.McrfOwnDocument{ID:id,Bytes:len(raw),SHA256:hex.EncodeToString(digest[:])}
    lines:=[]string{};copies:=[][]byte{}
    state:=&mcrfDocumentRun{}
    state.meta=func(expected string)*mobile.McrfOwnDocument {if expected!=id {return nil};copy:=meta;return &copy}
    state.read=func(expected string,offset int)[]byte {
        if expected!=id || offset<0 || offset>=len(raw) {return nil}
        end:=offset+8192;if end>len(raw) {end=len(raw)}
        chunk:=append([]byte(nil),raw[offset:end]...);copies=append(copies,chunk);return chunk
    }
    state.emit=func(line string)error {lines=append(lines,line);return nil}
    return state,id,&lines,&copies
}

func TestMcrfFreshDocumentSingleReadStreamsMaximumInBoundedFrames(t *testing.T) {
    raw:=[]byte(strings.Repeat("x",512<<10))
    state,id,lines,copies:=mcrfDocumentFixture(raw)
    if !state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("read denied")};mcrfWaitDocument(t,state)
    if len(*lines)!=66 || len(*copies)!=64 {t.Fatal("frame/read bound changed")}
    var result []byte
    for index,line:=range *lines {
        if len(line)>12000 {t.Fatal("oversize private frame")}
        if index==0 {if !strings.HasPrefix(line,"MCRF_OPENFLUX_DOCUMENT_META ") {t.Fatal("missing metadata")};continue}
        if index==65 {if !strings.HasPrefix(line,"MCRF_OPENFLUX_DOCUMENT_END ") {t.Fatal("missing end")};continue}
        var chunk struct{ID string;Offset int;Data string}
        if json.Unmarshal([]byte(strings.TrimPrefix(line,"MCRF_OPENFLUX_DOCUMENT_CHUNK ")),&chunk)!=nil || chunk.ID!=id || chunk.Offset!=(index-1)*8192 {t.Fatal("chunk identity/order")}
        decoded,err:=base64.StdEncoding.Strict().DecodeString(chunk.Data);if err!=nil || len(decoded)!=8192 {t.Fatal("chunk encoding")}
        result=append(result,decoded...)
    }
    if string(result)!=string(raw) {t.Fatal("document changed")}
    for _,copy:=range *copies {for _,b:=range copy {if b!=0 {t.Fatal("owned chunk not wiped")}}}
    if state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("same ticket read renewed")}
}

func TestMcrfFreshDocumentReadRequiresSeparateHostGateAndExactCommand(t *testing.T) {
    doc,id,_,_:=mcrfDocumentFixture([]byte("synthetic"))
    state:=&mcrfVerificationRun{enabled:true,documents:doc}
    if state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("own gate bypass")}
    state.ownEnabled=true
    for _,line:=range []string{"OPENFLUX_DOCUMENT_READ", "OPENFLUX_DOCUMENT_READ|"+strings.ToUpper(id),"OPENFLUX_DOCUMENT_READ|"+id+"|0", "OPENFLUX_DOCUMENT_READ|"+id+"\n", "OPENFLUX_DOCUMENT_READ|"+strings.Repeat("a",31)} {
        if state.input(line) {t.Fatal("malformed read admitted")}
    }
    state.stop();if state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("closed parent admitted read")}
}

func TestMcrfFreshDocumentBlockedOutputDoesNotBlockStopOrStartMoreWorkers(t *testing.T) {
    state,id,_,_:=mcrfDocumentFixture([]byte("synthetic"))
    entered:=make(chan struct{});release:=make(chan struct{});var once sync.Once
    defer once.Do(func(){close(release)})
    var lines atomic.Int32
    state.emit=func(string)error {if lines.Add(1)==1 {close(entered);<-release};return nil}
    if !state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("read denied")}
    select {case <-entered:case <-time.After(time.Second):t.Fatal("output not reached")}
    if state.input("OPENFLUX_DOCUMENT_READ|"+strings.Repeat("b",32)) {t.Fatal("second worker admitted")}
    stopped:=make(chan struct{});go func(){state.stop();close(stopped)}()
    select {case <-stopped:case <-time.After(time.Second):t.Fatal("STOP waits on output")}
    once.Do(func(){close(release)});mcrfWaitDocument(t,state)
    if lines.Load()!=1 || state.failureCurrent() {t.Fatal("stopped stream emitted late chunks/failure")}
}

func TestMcrfFreshDocumentReplacementRetiresStreamBeforeEndAndDoesNotPoisonNewTicket(t *testing.T) {
    state,id,_,_:=mcrfDocumentFixture([]byte(strings.Repeat("x",9000)))
    metadata:=state.meta;var replaced atomic.Bool;var count atomic.Int32
    state.meta=func(id string)*mobile.McrfOwnDocument {if replaced.Load() {return nil};return metadata(id)}
    state.emit=func(line string)error {count.Add(1);if strings.HasPrefix(line,"MCRF_OPENFLUX_DOCUMENT_CHUNK ") {replaced.Store(true)};return nil}
    if !state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("read denied")};mcrfWaitDocument(t,state)
    if count.Load()!=2 || state.failureCurrent() {t.Fatal("obsolete stream completed/poisoned replacement")}
}

func TestMcrfFreshDocumentShortChunkAndOutputFailureStayFiniteAndExact(t *testing.T) {
    for _,outputError:=range []bool{false,true} {
        state,id,lines,_:=mcrfDocumentFixture([]byte("synthetic"))
        var short []byte
        if outputError {state.emit=func(string)error{return errors.New("synthetic-private")}} else {state.read=func(string,int)[]byte{short=[]byte("short");return short}}
        if !state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("read denied")};mcrfWaitDocument(t,state)
        if !state.failureCurrent() {t.Fatal("current failure missing")}
        if !outputError && len(*lines)!=1 {t.Fatal("bad chunk/end emitted")}
        for _,b:=range short {if b!=0 {t.Fatal("rejected bytes retained")}}
        state.meta=func(string)*mobile.McrfOwnDocument{return nil}
        if state.failureCurrent() {t.Fatal("failure poisoned replacement")}
    }
}

func TestMcrfFreshDocumentMissingOrMalformedMetadataEmitsNothing(t *testing.T) {
    for mode:=0;mode<5;mode++ {
        state,id,lines,_:=mcrfDocumentFixture([]byte("synthetic"));original:=state.meta
        state.meta=func(id string)*mobile.McrfOwnDocument {
            if mode==0 {return nil};meta:=original(id)
            switch mode {case 1:meta.Bytes=0;case 2:meta.Bytes=(512<<10)+1;case 3:meta.SHA256="wrong";case 4:meta.ID=strings.Repeat("c",32)}
            return meta
        }
        if !state.input("OPENFLUX_DOCUMENT_READ|"+id) {t.Fatal("read denied")};mcrfWaitDocument(t,state)
        if len(*lines)!=0 {t.Fatal("untrusted metadata emitted")}
    }
}

func TestMcrfFreshOwnReplyUtf8UsesSeparateCapturedApplyWorker(t *testing.T) {
    id:=strings.Repeat("b",32);payload:="{\"client\":{\"answer\":\"синтетический\"}}"
    line:="OPENFLUX_DOCUMENT_REPLY|"+id+"|"+base64.StdEncoding.EncodeToString([]byte(payload))
    var own,external atomic.Int32
    state:=&mcrfVerificationRun{enabled:true,ownEnabled:true,emit:func(string){}}
    state.apply=func(string,string)(string,*mobile.McrfVerificationFailure){external.Add(1);return "accepted",nil}
    state.applyOwn=func(gotID,got string)(string,*mobile.McrfVerificationFailure){if gotID==id && got==payload {own.Add(1)};return "accepted",nil}
    if !state.input(line) {t.Fatal("own reply denied")};mcrfWaitWorker(t,state)
    if own.Load()!=1 || external.Load()!=0 {t.Fatal("reply channel mixed")}
    state.ownEnabled=false
    if state.input(line) {t.Fatal("own reply bypassed gate")}
}

func TestMcrfFreshOwnReplyCommandRejectsMalformedEncodingButDoesNotWidenCookies(t *testing.T) {
    prefix:="OPENFLUX_DOCUMENT_REPLY|"+strings.Repeat("b",32)+"|"
    for _,raw:=range [][]byte{{0xff},{},[]byte(strings.Repeat("x",8193))} {
        if _,ok:=mcrfReadVerificationCommand(prefix+base64.StdEncoding.EncodeToString(raw));ok {t.Fatal("unsafe own bytes admitted")}
    }
    if _,ok:=mcrfReadVerificationCommand(prefix+"eA");ok {t.Fatal("noncanonical base64 admitted")}
    if _,ok:=mcrfReadVerificationCommand("OPENFLUX_CAPTCHA_REPLY|"+strings.Repeat("b",32)+"|"+base64.StdEncoding.EncodeToString([]byte("cookie=я")));ok {t.Fatal("cookie contract widened")}
}
