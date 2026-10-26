// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "strings"
    "sync"
    mobile "openflux-mobile"
)

// One bounded asynchronous stream, not one worker/command per chunk. The stdin
// STOP path never waits on document IO. Already admitted output cannot be revoked.
type mcrfDocumentRun struct {
    mu sync.Mutex
    closed, busy bool
    last, failed string
    meta func(string)*mobile.McrfOwnDocument
    read func(string,int)[]byte
    emit func(string)error
}
func (*mcrfDocumentRun) String() string {return "OpenFluxDocumentRun(redacted)"}
func (state *mcrfDocumentRun) metadata(id string)*mobile.McrfOwnDocument {
    if state.meta!=nil {return state.meta(id)}
    return mobile.McrfOwnVerificationDocument(id)
}
func (state *mcrfDocumentRun) stop() {state.mu.Lock();state.closed=true;state.failed="";state.mu.Unlock()}
func (state *mcrfDocumentRun) failureCurrent() bool {
    state.mu.Lock();id:=state.failed;closed:=state.closed;state.mu.Unlock()
    if closed || id=="" {return false}
    meta:=state.metadata(id)
    return meta!=nil && meta.ID==id
}
func (state *mcrfDocumentRun) input(line string) bool {
    if len(line)!=len("OPENFLUX_DOCUMENT_READ|")+32 || !strings.HasPrefix(line,"OPENFLUX_DOCUMENT_READ|") {return false}
    id:=strings.TrimPrefix(line,"OPENFLUX_DOCUMENT_READ|")
    raw,err:=hex.DecodeString(id)
    if err!=nil || len(raw)!=16 || id!=strings.ToLower(id) {return false}
    state.mu.Lock()
    if state.closed || state.busy || state.last==id {state.mu.Unlock();return false}
    state.busy=true;state.last=id;state.mu.Unlock()
    go state.run(id)
    return true
}
func validDocument(meta *mobile.McrfOwnDocument,id string)bool {
    if meta==nil || meta.ID!=id || meta.Bytes<1 || meta.Bytes>512<<10 || len(meta.SHA256)!=64 {return false}
    raw,err:=hex.DecodeString(meta.SHA256)
    return err==nil && len(raw)==32 && meta.SHA256==strings.ToLower(meta.SHA256)
}
func (state *mcrfDocumentRun) current(id string,want *mobile.McrfOwnDocument)bool {
    state.mu.Lock();closed:=state.closed;state.mu.Unlock()
    if closed {return false}
    meta:=state.metadata(id)
    return validDocument(meta,id) && meta.Bytes==want.Bytes && meta.SHA256==want.SHA256
}
func (state *mcrfDocumentRun) failedIfCurrent(id string,want *mobile.McrfOwnDocument) {
    if !state.current(id,want) {return}
    state.mu.Lock();if !state.closed {state.failed=id};state.mu.Unlock()
}
func (state *mcrfDocumentRun) output(id string,want *mobile.McrfOwnDocument,prefix string,value any)bool {
    data,err:=json.Marshal(value)
    if err!=nil {state.failedIfCurrent(id,want);return false}
    if !state.current(id,want) {return false}
    line:=prefix+string(data)
    // No state mutex held across potentially blocked stdout. STOP can seal the
    // state and main process exits independently; exact child death is host proof.
    if state.emit!=nil {err=state.emit(line)} else {_,err=fmt.Println(line)}
    if err!=nil {state.failedIfCurrent(id,want);return false}
    return true
}
func (state *mcrfDocumentRun) run(id string) {
    defer func(){state.mu.Lock();state.busy=false;state.mu.Unlock()}()
    meta:=state.metadata(id)
    if !validDocument(meta,id) {return} // Stale/missing ticket never poisons its replacement.
    if !state.output(id,meta,"MCRF_OPENFLUX_DOCUMENT_META ",meta) {return}
    read:=state.read;if read==nil {read=mobile.McrfReadOwnVerificationDocument}
    for offset:=0;offset<meta.Bytes;offset+=8192 {
        if !state.current(id,meta) {return}
        chunk:=read(id,offset)
        size:=meta.Bytes-offset;if size>8192 {size=8192}
        if len(chunk)!=size {for i:=range chunk {chunk[i]=0};state.failedIfCurrent(id,meta);return}
        encoded:=base64.StdEncoding.EncodeToString(chunk)
        for i:=range chunk {chunk[i]=0}
        value:=struct{ID string `json:"id"`;Offset int `json:"offset"`;Data string `json:"data"`}{id,offset,encoded}
        if !state.output(id,meta,"MCRF_OPENFLUX_DOCUMENT_CHUNK ",value) {return}
    }
    state.output(id,meta,"MCRF_OPENFLUX_DOCUMENT_END ",map[string]string{"id":id})
}
