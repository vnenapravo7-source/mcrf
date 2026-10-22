// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestMcrfMasqueTransportCannotEnrollAndExplicitEnrollmentNeedsConsent(t *testing.T) {
    root:=t.TempDir()
    flags:=mcrfWdttMasqueFlags(filepath.Join(root,"config.json"))
    if flags["rt-masque"]!="true"||flags["rt-masque-accept-tos"]!="false"||flags["rt-masque-config"]!=filepath.Join(root,"masque-account.json") {t.Fatal("transport may register or use an unowned account path")}
    path:=filepath.Join(root,"enroll.json")
    if err:=os.WriteFile(path,[]byte(`{"turn_sni":"example.test","accept_tos":false}`),0600);err!=nil {t.Fatal(err)}
    if err:=mcrfEnrollMasque(context.Background(),path);err==nil {t.Fatal("enrollment without explicit consent accepted")}
    if _,err:=os.Stat(filepath.Join(root,"masque-account.json"));!os.IsNotExist(err) {t.Fatal("declined request produced account data")}
    if mcrfWdttProviderOptionsValid(&mcrfProfile{RtMasque:true,TurnSNI:"example.test"}) {t.Fatal("transport accepted missing cached account")}
}

type mcrfWdttDeadlineFixture struct {
	context.Context
	future  time.Time
	expired atomic.Bool
}

func TestMcrfWdttProviderFlagsKeepDefaultsAndExplicitTransport(t *testing.T) {
	c:=&mcrfProfile{}
	if !mcrfWdttProviderOptionsValid(c) || mcrfWdttProviderFlags(c)["vkcalls-preflight"]!="true" {t.Fatal("legacy fast-call default changed")}
	off:=false;c.VKCallsPreflight=&off;c.TurnStreamFirst=true;c.TurnSNI="example.test";c.ClientIDs="123,456"
	flags:=mcrfWdttProviderFlags(c)
	if !mcrfWdttProviderOptionsValid(c)||flags["vkcalls-preflight"]!="false"||flags["turn-stream-first"]!="true"||flags["turn-sni"]!="example.test"||flags["client-ids"]!="123,456" {t.Fatal("provider flags lost")}
}
func TestMcrfWdttProviderOptionsRejectUnsafeNamesAndClientLists(t *testing.T) {
	for _,host:=range []string{"https://example.test","example.test:443","-bad.test","bad..test","bad\n.test"} {
		if mcrfWdttProviderOptionsValid(&mcrfProfile{TurnSNI:host}) {t.Fatal("unsafe outer SNI accepted")}
	}
	for _,ids:=range []string{"123,token","123,","https://example.test"} {
		if mcrfWdttProviderOptionsValid(&mcrfProfile{ClientIDs:ids}) {t.Fatal("unsafe client IDs accepted")}
	}
}

func TestMcrfWdttCaptchaReplyDoesNotEchoErrorPayload(t *testing.T) {
	for _, tc := range []struct{ input, token, message string }{
		{"  proof-value  ", "proof-value", ""},
		{"", "", "webview captcha returned empty result"},
		{" \n ", "", "webview captcha returned empty result"},
		{" ERROR:TIMEOUT ", "", "webview captcha timed out"},
		{"error:fixture-private-value", "", "webview captcha failed"},
		{"Error:https://fixture.invalid/?token=fixture-private-value\nsecret", "", "webview captcha failed"},
	} {
		token, err := mcrfWdttCaptchaReply(tc.input)
		if token != tc.token || (err == nil) != (tc.message == "") {
			t.Fatal("reply classification changed")
		}
		if err != nil && err.Error() != tc.message {
			t.Fatal("untrusted CAPTCHA error was echoed")
		}
	}
}

func (c *mcrfWdttDeadlineFixture) Deadline() (time.Time, bool) {
	if c.expired.Load() {
		return time.Unix(1, 0), true
	}
	return c.future, true
}

func TestMcrfWdttDeadlineActiveChecksRealDeadlineAndCancellation(t *testing.T) {
	ctx := &mcrfWdttDeadlineFixture{Context: context.Background(), future: time.Now().Add(time.Minute)}
	if !mcrfWdttCaptchaActive(ctx) || !mcrfWdttCaptchaActive(context.Background()) {
		t.Fatal("active context rejected")
	}
	ctx.expired.Store(true)
	if ctx.Err() != nil || mcrfWdttCaptchaActive(ctx) || mcrfWdttCaptchaActive(nil) {
		t.Fatal("expired deadline accepted")
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if mcrfWdttCaptchaActive(cancelled) {
		t.Fatal("cancelled context accepted")
	}
}

func TestMcrfWdttDeadlineExpiredRequestLeavesQueuedProofUntouched(t *testing.T) {
	previous := CaptchaResultChan
	CaptchaResultChan = make(chan CaptchaResult, 1)
	defer func() { CaptchaResultChan = previous }()
	CaptchaResultChan <- CaptchaResult{Value: "fixture-proof"}
	ctx := &mcrfWdttDeadlineFixture{Context: context.Background()}
	ctx.expired.Store(true)
	token, err := requestWebViewCaptcha(ctx, 3, &VkCaptchaError{RedirectURI: "https://fixture.invalid", SessionToken: "fixture"}, "manual", time.Minute)
	if err == nil || token != "" || len(CaptchaResultChan) != 1 {
		t.Fatal("expired request consumed proof")
	}
}

func TestMcrfWdttDeadlineQueuedExactReplyCannotBeatElapsedParent(t *testing.T) {
	previous := CaptchaResultChan
	CaptchaResultChan = make(chan CaptchaResult, 1)
	defer func() { CaptchaResultChan = previous }()
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	oldStdout := os.Stdout
	os.Stdout = writer
	base, cancel := context.WithCancel(context.Background())
	ctx := &mcrfWdttDeadlineFixture{Context: base, future: time.Now().Add(30 * time.Second)}
	type outcome struct {
		token string
		err   error
	}
	done := make(chan outcome, 1)
	finished := false
	defer func() {
		cancel()
		reader.Close()
		writer.Close()
		os.Stdout = oldStdout
		if !finished {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("owned waiter did not exit")
			}
		}
	}()
	go func() {
		token, err := requestWebViewCaptcha(ctx, 3, &VkCaptchaError{RedirectURI: "https://fixture.invalid", SessionToken: "fixture"}, "manual", time.Minute)
		done <- outcome{token, err}
	}()
	lines := make(chan string, 1)
	go func() { line, _ := bufio.NewReader(reader).ReadString('\n'); lines <- line }()
	var line string
	select {
	case line = <-lines:
	case <-time.After(3 * time.Second):
		t.Fatal("owned IPC request missing")
	}
	fields := strings.Split(strings.TrimSpace(line), "|")
	if len(fields) != 5 || fields[0] != "CAPTCHA_SOLVE" {
		t.Fatal("invalid owned IPC request")
	}
	ctx.expired.Store(true)
	if ctx.Err() != nil {
		t.Fatal("fixture timer must remain inert")
	}
	enqueueCaptchaResult(CaptchaResult{RequestID: fields[1], Value: "fixture-proof"})
	select {
	case result := <-done:
		finished = true
		if result.err == nil || result.token != "" {
			t.Fatal("elapsed waiter accepted queued proof")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("owned waiter did not reject reply")
	}
}

func TestMcrfWdttProbeCancelsOnlyOwnedAndroidProbe(t *testing.T) {
	for _, android := range []bool{false, true} {
		for _, probe := range []bool{false, true} {
			ctx, cancel := context.WithCancel(context.Background())
			var output bytes.Buffer
			got := mcrfWdttProbeGate(android, probe, cancel, &output)
			want := android && probe
			if got != want || (ctx.Err() != nil) != want {
				t.Fatal("probe cancelled the wrong context")
			}
			expected := ""
			if want {
				expected = "MCRF_CAPTCHA_REQUIRED\n"
			}
			if output.String() != expected {
				t.Fatal("probe exposed data or lost its fixed signal")
			}
			cancel()
		}
	}
}
func TestMcrfWdttPrivateAndroidNeverFallsBackToUntaggedProof(t *testing.T) {
	for _, android := range []bool{false, true} {
		for _, private := range []bool{false, true} {
			if mcrfWdttReplyAllowed(android, private, CaptchaResult{Value: "fixture"}) == (android && private) {
				t.Fatal("untagged reply policy mismatch")
			}
			if !mcrfWdttReplyAllowed(android, private, CaptchaResult{RequestID: "3-7", Value: "fixture"}) {
				t.Fatal("exact reply rejected")
			}
		}
	}
}
func TestMcrfPrivateWdtt(t *testing.T) {
	c := mcrfProfile{Peer: "127.0.0.1:56000", Password: "fixture secret", Hashes: []string{"fixture-call-1-0123456789abcdef"}, Workers: 18, Device: "fixture-device", Listen: "127.0.0.1:22001", SOCKS: "127.0.0.1:22002", User: "user", Pass: "pass", Captcha: "auto", Fingerprint: "android"}
	path := filepath.Join(t.TempDir(), "config.json")
	b, _ := json.Marshal(c)
	if e := os.WriteFile(path, b, 0600); e != nil {
		t.Fatal(e)
	}
	if _, e := mcrfRead(path); e != nil {
		t.Fatal(e)
	}
	for _, address := range []string{"0.0.0.0:22002", "127.0.0.1:0", "example.invalid:1234"} {
		c.SOCKS = address
		b, _ = json.Marshal(c)
		os.WriteFile(path, b, 0600)
		if _, e := mcrfRead(path); e == nil {
			t.Fatal("unsafe listener accepted")
		}
	}
}
