// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "bytes"
    "context"
    "crypto/sha256"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "net/http"
    "sort"
    "strings"
    "sync"
    "unicode/utf8"
    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

// Foundation only: no stdin/CLI opt-in or Android dispatcher is enabled yet.
// A future caller must bind exact child + ticket + permit and explicit Open.
const mcrfHTTPCommandLimit=131072
type mcrfHTTPRequest struct {id,permit,request,method,target string;headers http.Header;body []byte}
func (*mcrfHTTPRequest) String()string{return "OpenFluxHTTPRequest(redacted)"}
func (request *mcrfHTTPRequest) close(){if request!=nil{clear(request.body);request.body=nil;request.headers=nil;request.target=""}}
func mcrfHTTPID(value string)bool {
    if len(value)!=32 || value!=strings.ToLower(value){return false}
    decoded,err:=hex.DecodeString(value);return err==nil && len(decoded)==16
}
func mcrfHTTPHeaders(headers http.Header)bool {
    names:=make(map[string]int);size:=0
    for name,values:=range headers {
        if name=="" || len(values)==0{return false};key:=strings.ToLower(name)
        for _,ch:=range name {if !(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'||ch>='0'&&ch<='9'||strings.ContainsRune("!#$%&'*+-.^_`|~",ch)){return false}}
        switch key {case "connection","proxy-authorization","proxy-authenticate","keep-alive","te","trailer","transfer-encoding","upgrade","host","content-length":return false}
        if _,exists:=names[key];!exists{size+=len(name)+2}
        names[key]+=len(values);if len(names)>32 || names[key]>16{return false}
        for _,value:=range values {
            if !utf8.ValidString(value){return false}
            for _,ch:=range value {if ch<32&&ch!='\t'||ch==127{return false}}
            size+=len(value)+2;if size>8192{return false}
        }
    }
    return size<=8192
}
// encoding/json replaces lone UTF16 surrogate escapes. Reject them before
// decoding so the target/header actually admitted is the one on the wire.
func mcrfHTTPJSONUnicode(raw []byte)bool {
    if !utf8.Valid(raw){return false}
    quoted:=false;depth:=0
    for i:=0;i<len(raw);i++ {
        if raw[i]=='"'{quoted=!quoted;continue}
        if !quoted {
            switch raw[i]{case '[','{':depth++;if depth>16{return false};case ']','}':depth--;if depth<0{return false}}
            continue
        }
        if raw[i]!='\\'{continue};i++;if i>=len(raw){return false}
        if raw[i]!='u'{continue};if i+4>=len(raw){return false}
        first,err:=hex.DecodeString(string(raw[i+1:i+5]));if err!=nil{return false};i+=4
        value:=int(first[0])<<8|int(first[1])
        if value>=0xdc00&&value<=0xdfff{return false}
        if value<0xd800||value>0xdbff{continue}
        if i+6>=len(raw)||raw[i+1]!='\\'||raw[i+2]!='u'{return false}
        second,err:=hex.DecodeString(string(raw[i+3:i+7]));if err!=nil{return false}
        low:=int(second[0])<<8|int(second[1]);if low<0xdc00||low>0xdfff{return false};i+=6
    }
    return !quoted&&depth==0
}
// REQUEST|ticket|permit|request|canonical-base64(JSON [method,target,headerpairs,bodybase64]).
// Arrays avoid duplicate JSON object keys. Limits apply to the entire command
// as well as decoded fields. Malformed input returns no private error text.
func mcrfReadHTTPRequest(line string)*mcrfHTTPRequest {
    if len(line)>mcrfHTTPCommandLimit{return nil}
    parts:=strings.Split(line,"|")
    if len(parts)!=5||parts[0]!="OPENFLUX_HTTP_REQUEST"||!mcrfHTTPID(parts[1])||!mcrfHTTPID(parts[2])||!mcrfHTTPID(parts[3]){return nil}
    raw,err:=base64.StdEncoding.Strict().DecodeString(parts[4]);if err!=nil{clear(raw);return nil};defer clear(raw)
    if base64.StdEncoding.EncodeToString(raw)!=parts[4]||!mcrfHTTPJSONUnicode(raw){return nil}
    var fields []json.RawMessage
    if json.Unmarshal(raw,&fields)!=nil||len(fields)!=4{return nil}
    var method,target,bodyText string;var pairs []json.RawMessage
    if json.Unmarshal(fields[0],&method)!=nil||json.Unmarshal(fields[1],&target)!=nil||
        json.Unmarshal(fields[2],&pairs)!=nil||json.Unmarshal(fields[3],&bodyText)!=nil{return nil}
    // null is not a typed empty string/header list.
    if bytes.Equal(bytes.TrimSpace(fields[0]),[]byte("null"))||bytes.Equal(bytes.TrimSpace(fields[1]),[]byte("null"))||
        bytes.Equal(bytes.TrimSpace(fields[2]),[]byte("null"))||bytes.Equal(bytes.TrimSpace(fields[3]),[]byte("null")){return nil}
    switch method{case "GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS":default:return nil}
    if len(target)==0||len(target)>8192||len(pairs)>512||len(bodyText)>87384{return nil}
    headers:=make(http.Header)
    for _,rawPair:=range pairs {
        var pair []json.RawMessage
        if json.Unmarshal(rawPair,&pair)!=nil||len(pair)!=2{return nil}
        var name,value string
        if bytes.Equal(bytes.TrimSpace(pair[0]),[]byte("null"))||bytes.Equal(bytes.TrimSpace(pair[1]),[]byte("null"))||
            json.Unmarshal(pair[0],&name)!=nil||json.Unmarshal(pair[1],&value)!=nil{return nil}
        headers[name]=append(headers[name],value)
    }
    if !mcrfHTTPHeaders(headers){return nil}
    body,err:=base64.StdEncoding.Strict().DecodeString(bodyText)
    if err!=nil{clear(body);return nil}
    if len(body)>65536||base64.StdEncoding.EncodeToString(body)!=bodyText{clear(body);return nil}
    return &mcrfHTTPRequest{id:parts[1],permit:parts[2],request:parts[3],method:method,target:target,headers:headers,body:body}
}

func mcrfHTTPOutput(current func()bool,emit func(string)error,prefix string,value any)bool {
    var buffer bytes.Buffer
    defer func(){clear(buffer.Bytes())}()
    encoder:=json.NewEncoder(&buffer);encoder.SetEscapeHTML(false)
    if encoder.Encode(value)!=nil{return false}
    raw:=buffer.Bytes()
    raw=bytes.TrimSuffix(raw,[]byte{'\n'})
    if len(raw)>24500||!current(){return false}
    // No ownership mutex across stdout. Already admitted output is irrevocable;
    // STOP seals future frames, exact child termination remains host evidence.
    return emit(prefix+string(raw))==nil
}
func mcrfHTTPResponseFrames(request *mcrfHTTPRequest,response *script.McrfOwnHTTPResponse,current func()bool,emit func(string)error)bool {
    if request==nil||response==nil||current==nil||emit==nil||!mcrfHTTPID(request.id)||!mcrfHTTPID(request.request)||
        response.Status<200||response.Status>599||len(response.Body)>524288||!mcrfHTTPHeaders(response.Headers){return false}
    if (response.Status==204||response.Status==304||request.method=="HEAD")&&len(response.Body)!=0{return false}
    pairs:=make([][]string,0);names:=make([]string,0,len(response.Headers))
    for name:=range response.Headers{names=append(names,name)};sort.Strings(names)
    for _,name:=range names {for _,value:=range response.Headers[name]{pairs=append(pairs,[]string{name,value})}}
    digest:=sha256.Sum256(response.Body)
    meta:=struct{ID string `json:"id"`;Request string `json:"request"`;Status int `json:"status"`;Headers [][]string `json:"headers"`;Bytes int `json:"bytes"`;SHA string `json:"sha256"`}{
        request.id,request.request,response.Status,pairs,len(response.Body),hex.EncodeToString(digest[:])}
    if !mcrfHTTPOutput(current,emit,"MCRF_OPENFLUX_HTTP_RESPONSE ",meta){return false}
    for offset:=0;offset<len(response.Body);offset+=8192 {
        end:=offset+8192;if end>len(response.Body){end=len(response.Body)}
        chunk:=struct{ID string `json:"id"`;Request string `json:"request"`;Offset int `json:"offset"`;Data string `json:"data"`}{
            request.id,request.request,offset,base64.StdEncoding.EncodeToString(response.Body[offset:end])}
        if !mcrfHTTPOutput(current,emit,"MCRF_OPENFLUX_HTTP_CHUNK ",chunk){return false}
    }
    return mcrfHTTPOutput(current,emit,"MCRF_OPENFLUX_HTTP_END ",map[string]string{"id":request.id,"request":request.request})
}

// One worker slot for the entire child, across all tickets and permits. Gateway
// may retain ONE bounded next task, never spawn a replacement for blocked work.
// start(true) transfers request ownership; start(false) leaves cleanup to caller.
type mcrfHTTPWork struct {
    scope string
    run func(context.Context,func()bool)
    discard func() // Pure bounded ownership cleanup, never IO.
}
type mcrfHTTPChildWorker struct {
    mu sync.Mutex
    closed,busy bool
    cancel context.CancelFunc
    activeScope string
    queued *mcrfHTTPWork
}
func (*mcrfHTTPChildWorker) String()string{return "OpenFluxHTTPChildWorker(redacted)"}
func (worker *mcrfHTTPChildWorker) stop(){
    worker.mu.Lock();worker.closed=true;cancel:=worker.cancel;queued:=worker.queued;worker.queued=nil;worker.mu.Unlock()
    if cancel!=nil{cancel()} // cancellation signalling, never waits for handler.
    if queued!=nil&&queued.discard!=nil{queued.discard()}
}
func (worker *mcrfHTTPChildWorker) cancelScope(scope string){
    worker.mu.Lock()
    var cancel context.CancelFunc
    if worker.activeScope==scope{cancel=worker.cancel}
    var queued *mcrfHTTPWork
    if worker.queued!=nil&&worker.queued.scope==scope{queued=worker.queued;worker.queued=nil}
    worker.mu.Unlock()
    if cancel!=nil{cancel()};if queued!=nil&&queued.discard!=nil{queued.discard()}
}
func (worker *mcrfHTTPChildWorker) task(work func(context.Context,func()bool))bool {
    return worker.submit(&mcrfHTTPWork{run:work},false)
}
func (worker *mcrfHTTPChildWorker) queuedTask(scope string,work func(context.Context,func()bool),discard func())bool{
    return worker.submit(&mcrfHTTPWork{scope:scope,run:work,discard:discard},true)
}
func (worker *mcrfHTTPChildWorker) submit(job *mcrfHTTPWork,allowQueue bool)bool {
    if job==nil||job.run==nil{return false}
    worker.mu.Lock()
    if worker.closed{worker.mu.Unlock();return false}
    if worker.busy{
        if !allowQueue||worker.queued!=nil{worker.mu.Unlock();return false}
        worker.queued=job;worker.mu.Unlock();return true
    }
    ctx,cancel:=context.WithCancel(context.Background())
    worker.busy=true;worker.cancel=cancel;worker.activeScope=job.scope;worker.mu.Unlock()
    go worker.run(job,ctx,cancel)
    return true
}
func (worker *mcrfHTTPChildWorker) run(job *mcrfHTTPWork,ctx context.Context,cancel context.CancelFunc){
    for job!=nil{
        taskCtx:=ctx // A retained old liveness closure must never observe the next task's context.
        live:=func()bool{worker.mu.Lock();closed:=worker.closed;worker.mu.Unlock();return !closed&&taskCtx.Err()==nil}
        if live(){job.run(taskCtx,live)}
        if job.discard!=nil{job.discard()};cancel()
        worker.mu.Lock()
        job=worker.queued;worker.queued=nil
        if job==nil{
            worker.busy=false;worker.cancel=nil;worker.activeScope="";worker.mu.Unlock();return
        }
        ctx,cancel=context.WithCancel(context.Background())
        worker.cancel=cancel;worker.activeScope=job.scope;worker.mu.Unlock()
    }
}
func (worker *mcrfHTTPChildWorker) start(request *mcrfHTTPRequest,current func()bool,
    exchange func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string),emit func(string)error,result ...func(string))bool {
    return worker.startMode(request,current,exchange,emit,false,result...)
}
func (worker *mcrfHTTPChildWorker) startQueued(request *mcrfHTTPRequest,current func()bool,
    exchange func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string),emit func(string)error,result ...func(string))bool {
    return worker.startMode(request,current,exchange,emit,true,result...)
}
func (worker *mcrfHTTPChildWorker) startMode(request *mcrfHTTPRequest,current func()bool,
    exchange func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string),emit func(string)error,allowQueue bool,result ...func(string))bool {
    if request==nil||current==nil||exchange==nil||emit==nil{return false}
    return worker.submit(&mcrfHTTPWork{scope:request.id,discard:request.close,run:func(ctx context.Context,workerLive func()bool){
        live:=func()bool{return workerLive()&&current()}
        report:=func(reason string){if len(result)>0&&result[0]!=nil&&live(){result[0](reason)}}
        if !live(){return}
        response,reason:=exchange(ctx,request)
        if response!=nil{defer response.Close()}
        if !live(){return}
        if reason!="ok"||response==nil{report("request_failed");return}
        if !mcrfHTTPResponseFrames(request,response,live,emit){report("response_rejected")}
    }},allowQueue) // accepted ownership; not request/handler/output completion.
}
