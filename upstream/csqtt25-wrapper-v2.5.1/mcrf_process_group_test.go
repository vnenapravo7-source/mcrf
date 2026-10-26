// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"strings"
	"sync"
	"testing"
)

func TestMcrfProcessGroupPrivateAckIsExactAndOneShot(t *testing.T) {
	nonce:=strings.Repeat("a",32)
	owner:=&mcrfProcessGroupOwner{nonce:nonce,ready:make(chan struct{})}
	for _,line:=range []string{"STOP","MCRF_OWNER_ACK|", "MCRF_OWNER_ACK|"+strings.Repeat("b",32),"MCRF_OWNER_ACK|"+nonce+"|extra"} {
		if owner.accept(line) {t.Fatal("foreign input granted child startup")}
	}
	select {case <-owner.ready:t.Fatal("missing ownership accepted");default:}
	var all sync.WaitGroup
	for i:=0;i<32;i++ {all.Add(1);go func(){defer all.Done();if !owner.accept("MCRF_OWNER_ACK|"+nonce){t.Error("exact ACK rejected")}}()}
	all.Wait()
	if err:=owner.await(context.Background());err!=nil {t.Fatal("exact owned handshake not released")}
}

func TestMcrfProcessGroupCancelNeverStartsUnacknowledgedChild(t *testing.T) {
	owner:=&mcrfProcessGroupOwner{nonce:strings.Repeat("a",32),ready:make(chan struct{})}
	ctx,cancel:=context.WithCancel(context.Background());cancel()
	if owner.await(ctx)==nil {t.Fatal("cancelled startup accepted")}
	select {case <-owner.ready:t.Fatal("cancellation forged an ACK");default:}
}

func TestMcrfProcessGroupMalformedNonceRejectedBeforeOsOperation(t *testing.T) {
	for _,value:=range []string{"a","../owned",strings.Repeat("A",32),strings.Repeat("a",33)} {
		t.Setenv("MCRF_PROCESS_OWNER",value)
		if _,err:=mcrfPrepareProcessGroup();err==nil {t.Fatal("malformed private owner accepted")}
	}
}

func TestMcrfProcessGroupDesktopFixtureDoesNotChangeItsProcessGroup(t *testing.T) {
	t.Setenv("MCRF_PROCESS_OWNER","")
	owner,err:=mcrfPrepareProcessGroup()
	if err!=nil||owner.await(context.Background())!=nil {t.Fatal("explicit desktop fixture rejected")}
	if owner.accept("MCRF_OWNER_ACK|"){t.Fatal("empty owner is not an Android proof")}
}

func TestMcrfProcessGroupChildDoesNotInheritPrivateAckCapability(t *testing.T) {
	values:=[]string{"MCRF_PROCESS_OWNER=owned-private", "MCRF_OWNED_FIXTURE=retain", "PATH=retain"}
	actual:=mcrfProcessChildEnvironment(values)
	if strings.Join(actual,";")!="MCRF_OWNED_FIXTURE=retain;PATH=retain" {t.Fatal("child ownership environment mismatch")}
	if len(values)!=3||values[0]!="MCRF_PROCESS_OWNER=owned-private" {t.Fatal("caller environment changed")}
}
