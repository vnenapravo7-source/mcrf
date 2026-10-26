// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"strings"
	"testing"
)

func TestMcrfChildFailurePreservesReasonWithoutSecrets(t *testing.T) {
	c:=&bridgeConfig{Password:"test-password",Hashes:[]string{"test-hash"},DeviceID:"device-secret",VKRuntimeToken:"vk-secret",SOCKSUser:"user-secret",SOCKSPass:"socks-secret"}
	message:=mcrfPublicChildFailure(c,"ERROR permission denied test-password test-hash device-secret vk-secret user-secret socks-secret https://fixture.invalid/?token=unknown-token")
	if !strings.Contains(message,"permission denied"){t.Fatal("actual reason missing")}
	for _,secret:=range []string{"test-password","test-hash","device-secret","vk-secret","user-secret","socks-secret","unknown-token"}{if strings.Contains(message,secret){t.Fatal("secret leaked")}}
}
func TestMcrfChildFailureRejectsPrivateControlAndOversizedOutput(t *testing.T) {
	for _,line:=range []string{"CONFIG|error|private", "CAPTCHA_SOLVE|error|private", "MCRF_PROCESS_OWNER=error-secret", `{"error":"private"}`,"__CSQTT_EVENT__|CONFIG|error-private",strings.Repeat("error",1000),"transport ready"}{
		if mcrfPublicChildFailure(nil,line)!=""{t.Fatal("non-public output forwarded")}
	}
}
func TestMcrfChildFailureStructuredErrorAndTerminalControls(t *testing.T) {
	message:=mcrfPublicChildFailure(nil,`__CSQTT_EVENT__|ERROR|{"message":"fatal: server refused protocol","token":"not-forwarded"}`)
	if message!="fatal: server refused protocol"{t.Fatal("structured reason lost")}
	message=mcrfPublicChildFailure(nil,"\x1b[31merror: denied\x1b[0m\u202e password=unknown-secret")
	if strings.Contains(message,"\x1b")||strings.Contains(message,"\u202e")||strings.Contains(message,"unknown-secret"){t.Fatal("control or credential survived")}
}
