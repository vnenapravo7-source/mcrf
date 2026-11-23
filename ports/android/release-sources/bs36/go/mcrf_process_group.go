// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Private host ownership handshake, not provider data. The wrapper cannot start
// ANY descendant until the host independently verifies root UID/birth/PGID/cwd.
type mcrfProcessGroupOwner struct {
	nonce string
	ready chan struct{}
	once sync.Once
}

// The transport inherits PGID, not the private host-to-wrapper ACK capability.
func mcrfProcessChildEnvironment(values []string) []string {
	if values==nil {values=os.Environ()}
	out:=make([]string,0,len(values))
	for _,value:=range values {
		key,_,_:=strings.Cut(value,"=")
		if !strings.EqualFold(key,"MCRF_PROCESS_OWNER") {out=append(out,value)}
	}
	return out
}

func mcrfPrepareProcessGroup() (*mcrfProcessGroupOwner, error) {
	nonce := os.Getenv("MCRF_PROCESS_OWNER")
	owner := &mcrfProcessGroupOwner{nonce: nonce, ready: make(chan struct{})}
	// Existing desktop/owned host fixtures remain explicit no-handshake runs.
	// Android contract1 main always supplies a fresh nonce and requires the ACK.
	if nonce == "" { close(owner.ready); return owner, nil }
	if !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(nonce) { return nil, errors.New("invalid private process owner") }
	pid, birth, err := mcrfCreateProcessGroup()
	if err != nil { return nil, err }
	if _, err = fmt.Fprintf(os.Stdout, "MCRF_PROCESS_GROUP_V1|%s|%d|%s\n", nonce, pid, birth); err != nil { return nil, errors.New("process ownership announcement failed") }
	return owner, nil
}

func (owner *mcrfProcessGroupOwner) accept(line string) bool {
	if owner.nonce == "" || line != "MCRF_OWNER_ACK|" + owner.nonce { return false }
	owner.once.Do(func(){close(owner.ready)})
	return true
}

func (owner *mcrfProcessGroupOwner) await(ctx context.Context) error {
	timer := time.NewTimer(5*time.Second); defer timer.Stop()
	select {
	case <-owner.ready: return ctx.Err()
	case <-ctx.Done(): return ctx.Err()
	case <-timer.C: return errors.New("private process ownership was not confirmed")
	}
}
