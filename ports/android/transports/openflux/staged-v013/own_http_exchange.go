// SPDX-License-Identifier: GPL-3.0-or-later
package script

import (
    "bytes"
    "context"
    "errors"
    "net/http"
    "strings"
    "sync"
    "time"
)

const mcrfHTTPBodyLimit=64<<10
const mcrfHTTPResponseLimit=512<<10
const mcrfHTTPHeadersLimit=8192
var mcrfHTTPBoundError=errors.New("verification response limit")

// One bounded exchange at a time. Caller must create this ONLY after exact
// ticket/user-open admission. This foundation is not wired to CLI/Android yet.
type McrfOwnHTTPRun struct {
    mu sync.Mutex
    closed,busy bool
    lease *McrfOwnHTTPLease
    current func()bool // Pure ownership check; never called while run.mu is held.
    ctx context.Context
    cancel context.CancelFunc
}
func (*McrfOwnHTTPRun) String()string{return "OpenFluxOwnHTTPRun(redacted)"}
func (lease *McrfOwnHTTPLease) McrfOpenHTTPAfterUserAction(current func()bool,deadline time.Time)*McrfOwnHTTPRun {
    remaining:=time.Until(deadline)
    if current==nil || !lease.Current() || remaining<=0 || remaining>150*time.Second {return nil}
    // Do not invoke current here: future native caller may hold captcha.mu.
    ctx,cancel:=context.WithDeadline(context.Background(),deadline)
    return &McrfOwnHTTPRun{lease:lease,current:current,ctx:ctx,cancel:cancel}
}
func (run *McrfOwnHTTPRun) live()bool {
    if run==nil {return false}
    run.mu.Lock();closed:=run.closed;run.mu.Unlock()
    return !closed && run.ctx.Err()==nil && mcrfHTTPOwnerCurrent(run.current) && run.lease.Current()
}
func mcrfHTTPOwnerCurrent(check func()bool)(valid bool){defer func(){if recover()!=nil{valid=false}}();return check!=nil && check()}
func (run *McrfOwnHTTPRun) Close(){if run==nil{return};run.mu.Lock();run.closed=true;run.mu.Unlock();run.cancel()}

type McrfOwnHTTPResponse struct {Status int;Headers http.Header;Body []byte}
func (*McrfOwnHTTPResponse) String()string{return "OpenFluxOwnHTTPResponse(redacted)"}
func (response *McrfOwnHTTPResponse) Close(){if response==nil{return};clear(response.Body);response.Body=nil;response.Headers=nil}

func mcrfHTTPHeadersAllowed(headers http.Header)bool {
    if len(headers)>32 {return false};size:=0
    for key,values:=range headers {
        if len(key)==0 || len(key)>mcrfHTTPHeadersLimit || len(values)>16 {return false}
        size+=len(key)+2;if size>mcrfHTTPHeadersLimit{return false}
        for _,ch:=range key {if !(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'||ch>='0'&&ch<='9'||strings.ContainsRune("!#$%&'*+-.^_`|~",ch)){return false}}
        switch strings.ToLower(key){case "connection","proxy-authorization","proxy-authenticate","keep-alive","te","trailer","transfer-encoding","upgrade","host","content-length":return false}
        for _,value:=range values {
            if len(value)>mcrfHTTPHeadersLimit{return false}
            for _,ch:=range value {if ch<0x20 && ch!='\t' || ch==0x7f {return false}}
            size+=len(value)+2;if size>mcrfHTTPHeadersLimit{return false}
        }
    }
    return true
}

// No network client/dialer/redirect follower. Busy is retained until the exact
// handler returns, even if it ignores cancellation; never spawn a replacement
// worker to pretend an unretired invocation completed.
func (run *McrfOwnHTTPRun) Exchange(parent context.Context,method,target string,headers http.Header,body []byte)(*McrfOwnHTTPResponse,string) {
    if parent==nil || parent.Err()!=nil || !run.live() {return nil,"retired"}
    if len(body)>mcrfHTTPBodyLimit || len(target)==0 || len(target)>8192 || !mcrfHTTPHeadersAllowed(headers){return nil,"invalid_request"}
    switch method {case "GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS":default:return nil,"invalid_request"}
    run.mu.Lock()
    if run.closed || run.busy {closed:=run.closed;run.mu.Unlock();if closed{return nil,"retired"};return nil,"busy"}
    run.busy=true;run.mu.Unlock()
    defer func(){run.mu.Lock();run.busy=false;run.mu.Unlock()}()
    ctx,cancel:=context.WithCancel(run.ctx);defer cancel()
    stopParent:=context.AfterFunc(parent,cancel);defer stopParent()
    copied:=bytes.Clone(body);defer clear(copied)
    request,err:=http.NewRequestWithContext(ctx,method,target,bytes.NewReader(copied))
    if err!=nil {return nil,"invalid_request"};request.Header=headers.Clone()
    writer:=&mcrfHTTPBoundWriter{headers:make(http.Header),body:make([]byte,0,mcrfHTTPResponseLimit),head:method=="HEAD",cancel:cancel}
    defer writer.close()
    if !run.live() || parent.Err()!=nil {return nil,"retired"}
    if mcrfServeOwnHTTP(run.lease,writer,request){return nil,"handler_failed"}
    if writer.failed {return nil,"response_rejected"}
    if !run.live() || ctx.Err()!=nil || parent.Err()!=nil {return nil,"retired"}
    if !mcrfHTTPHeadersAllowed(writer.headers) {return nil,"response_rejected"}
    status:=writer.status;if status==0{status=200}
    result:=&McrfOwnHTTPResponse{Status:status,Headers:writer.headers.Clone(),Body:writer.body}
    writer.body=nil;return result,"ok"
}

func mcrfServeOwnHTTP(lease *McrfOwnHTTPLease,writer http.ResponseWriter,request *http.Request)(panicked bool){
    defer func(){if recover()!=nil{panicked=true}}()
    lease.ServeHTTP(writer,request);return false
}

type mcrfHTTPBoundWriter struct {headers http.Header;body []byte;status,written int;head,failed,closed bool;cancel context.CancelFunc}
func (writer *mcrfHTTPBoundWriter) Header()http.Header{return writer.headers}
func (writer *mcrfHTTPBoundWriter) WriteHeader(status int){
    if writer.closed || writer.failed || writer.status!=0{return}
    if status<200 || status>599 || !mcrfHTTPHeadersAllowed(writer.headers){writer.failed=true;writer.cancel();return}
    writer.status=status
}
func (writer *mcrfHTTPBoundWriter) Write(value []byte)(int,error){
    if writer.status==0{writer.WriteHeader(200)}
    if writer.closed || writer.failed {return 0,mcrfHTTPBoundError}
    if writer.status==204 || writer.status==304 {return 0,http.ErrBodyNotAllowed}
    if len(value)>mcrfHTTPResponseLimit-writer.written{writer.failed=true;writer.cancel();return 0,mcrfHTTPBoundError}
    writer.written+=len(value)
    if writer.head{return len(value),nil}
    writer.body=append(writer.body,value...);return len(value),nil
}
func (writer *mcrfHTTPBoundWriter) close(){writer.closed=true;clear(writer.body);writer.body=nil;writer.headers=nil}
