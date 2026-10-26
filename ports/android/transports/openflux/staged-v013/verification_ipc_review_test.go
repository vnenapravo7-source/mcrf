// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/base64"
    "strings"
    "testing"
)

func TestMcrfFreshReplyCommandKeepsExactTicketAndCookieValue(t *testing.T) {
    id:=strings.Repeat("a",32)
    cookies:="cookie=synthetic|value; other=two"
    command,ok:=mcrfReadVerificationCommand("OPENFLUX_CAPTCHA_REPLY|"+id+"|"+base64.StdEncoding.EncodeToString([]byte(cookies)))
    if !ok || command.id!=id || command.cookies!=cookies || command.cancel {t.Fatal("reply changed")}
    if strings.Contains(command.String(),"synthetic") || strings.Contains(command.String(),id) {t.Fatal("reply printable")}
}

func TestMcrfFreshReplyCommandRejectsInvalidIdsAndUnknownMessages(t *testing.T) {
    for _,line:=range []string{"STOP","OPENFLUX_CAPTCHA_CANCEL|","OPENFLUX_CAPTCHA_CANCEL|"+strings.Repeat("A",32),"OPENFLUX_CAPTCHA_CANCEL|"+strings.Repeat("a",30),"UNKNOWN|"+strings.Repeat("a",32),"OPENFLUX_CAPTCHA_REPLY|"+strings.Repeat("a",32)+"|bad-base64!"} {
        if _,ok:=mcrfReadVerificationCommand(line);ok {t.Fatal("malformed message admitted")}
    }
}

func TestMcrfFreshReplyCommandBoundsCookieBytesAndControls(t *testing.T) {
    for _,header:=range []string{"","cookie=one\r\nInjected: true","cookie=😀",strings.Repeat("x",8193)} {
        line:="OPENFLUX_CAPTCHA_REPLY|"+strings.Repeat("a",32)+"|"+base64.StdEncoding.EncodeToString([]byte(header))
        if _,ok:=mcrfReadVerificationCommand(line);ok {t.Fatal("unsafe cookie header admitted")}
    }
    if _,ok:=mcrfReadVerificationCommand(strings.Repeat("x",16385));ok {t.Fatal("oversize line admitted")}
}

func TestMcrfFreshCancelCommandIsExactAndContainsNoCookie(t *testing.T) {
    id:=strings.Repeat("b",32)
    command,ok:=mcrfReadVerificationCommand("OPENFLUX_CAPTCHA_CANCEL|"+id)
    if !ok || !command.cancel || command.id!=id || command.cookies!="" {t.Fatal("cancel changed")}
    if _,ok:=mcrfReadVerificationCommand("OPENFLUX_CAPTCHA_CANCEL|"+id+"|extra");ok {t.Fatal("cancel payload admitted")}
}

func TestMcrfFreshUiCommandsAreDisabledWithoutHostOptIn(t *testing.T) {
    state:=&mcrfVerificationRun{}
    if state.input("OPENFLUX_CAPTCHA_CANCEL|"+strings.Repeat("a",32)) {t.Fatal("host opt-in bypassed")}
    if state.waiting.Load() || state.busy.Load() || state.failed.Load()!=nil {t.Fatal("disabled input changed state")}
}
