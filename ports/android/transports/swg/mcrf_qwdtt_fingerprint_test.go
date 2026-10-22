package main

import (
    "strings"
    "testing"
)

func TestMcrfQwdttFingerprintHasMatchingBrowserIdentity(t *testing.T) {
    oldKind,oldCalls:=mcrfQwdttFingerprint,vkCallsProfile
    defer func(){mcrfQwdttFingerprint=oldKind;vkCallsProfile=oldCalls}()
    for _,kind:=range []string{"firefox","safari"} {
        if err:=mcrfQwdttSetFingerprint(kind);err!=nil {t.Fatal(err)}
        value:=getRandomProfile()
        if !strings.Contains(strings.ToLower(value.UserAgent),kind)||value.SecChUa!=""||value!=vkCallsProfile {t.Fatal("browser identity does not match selected family")}
    }
    if err:=mcrfQwdttSetFingerprint("unsupported");err==nil {t.Fatal("unknown fingerprint accepted")}
}
