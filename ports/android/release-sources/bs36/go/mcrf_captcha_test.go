// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

// A timer callback may be delayed even though the actual deadline has passed.
// Keep Err/Done inert so these tests cannot pass merely because a timer ran.
type mcrfDeadlineFixture struct {
	context.Context
	deadline time.Time
}

func (c *mcrfDeadlineFixture) Deadline() (time.Time, bool) { return c.deadline, true }

func TestMcrfCaptchaDeadlineRejectsRegistrationBeforeTimerRuns(t *testing.T) {
	r := &mcrfCaptchaRegistry{}
	ctx := &mcrfDeadlineFixture{context.Background(), time.Now().Add(-time.Second)}
	if ctx.Err() != nil { t.Fatal("fixture must not be cancelled") }
	if _, _, err := r.register(ctx); err == nil || len(r.pending) != 0 {
		t.Fatal("elapsed deadline admitted a new waiter")
	}
}

func TestMcrfCaptchaDeadlineRejectsDeliveryBeforeTimerRuns(t *testing.T) {
	r := &mcrfCaptchaRegistry{}
	ctx := &mcrfDeadlineFixture{context.Background(), time.Now().Add(time.Hour)}
	id, waiter, err := r.register(ctx)
	if err != nil { t.Fatal(err) }
	ctx.deadline = time.Now().Add(-time.Second)
	if ctx.Err() != nil { t.Fatal("fixture must not be cancelled") }
	if r.deliver("CAPTCHA_RESULT|"+id+"|fixture-proof") { t.Fatal("elapsed waiter accepted proof") }
	if len(r.pending) != 0 { t.Fatal("expired waiter not retired") }
	select { case <-waiter.result: t.Fatal("expired proof delivered"); default: }
}

func TestMcrfCaptchaDeadlineFutureAndUnboundedRemainUsable(t *testing.T) {
	for _, ctx := range []context.Context{context.Background(), &mcrfDeadlineFixture{context.Background(), time.Now().Add(time.Hour)}} {
		r := &mcrfCaptchaRegistry{}
		id, waiter, err := r.register(ctx)
		if err != nil { t.Fatal(err) }
		if !r.deliver("CAPTCHA_RESULT|"+id+"|fixture-proof") { t.Fatal("active waiter rejected") }
		select { case value := <-waiter.result: if value != "fixture-proof" { t.Fatal("wrong proof") }; default: t.Fatal("proof missing") }
	}
}

func TestMcrfCaptchaExactWaiterNeverBroadcastsToAnotherAttempt(t *testing.T) {
	r := &mcrfCaptchaRegistry{}
	first, a, e := r.register(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	second, b, e := r.register(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if first == second {
		t.Fatal("request identity reused")
	}
	for _, line := range []string{"CAPTCHA_RESULT|fixture-proof", "CAPTCHA_RESULT|bad|fixture-proof", "CAPTCHA_RESULT|" + first + "|bad|proof", "CAPTCHA_RESULT|" + first + "|bad\nproof"} {
		if r.deliver(line) {
			t.Fatal("ambiguous or malformed result accepted")
		}
	}
	if !r.deliver("CAPTCHA_RESULT|" + second + "|fixture-second") {
		t.Fatal("addressed result rejected")
	}
	if got := <-b.result; got != "fixture-second" {
		t.Fatal("proof changed")
	}
	select {
	case <-a.result:
		t.Fatal("result broadcast into other waiter")
	default:
	}
	if r.deliver("CAPTCHA_RESULT|" + second + "|duplicate") {
		t.Fatal("result replay accepted")
	}
	r.retire(first, a)
	third, c, e := r.register(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	defer r.retire(third, c)
	if r.deliver("CAPTCHA_RESULT|" + first + "|late-proof") {
		t.Fatal("retired result accepted by new waiter")
	}
}
func TestMcrfCaptchaCancellationAndCapacityCannotBlockStop(t *testing.T) {
	r := &mcrfCaptchaRegistry{}
	ctx, cancel := context.WithCancel(context.Background())
	id, w, e := r.register(ctx)
	if e != nil {
		t.Fatal(e)
	}
	cancel()
	if r.deliver("CAPTCHA_RESULT|" + id + "|late") {
		t.Fatal("cancelled waiter accepted proof")
	}
	r.retire(id, w)
	for i := 0; i < 32; i++ {
		if _, _, e := r.register(context.Background()); e != nil {
			t.Fatal(e)
		}
	}
	if _, _, e := r.register(context.Background()); e == nil {
		t.Fatal("unbounded waiter pool")
	}
}
func TestMcrfCaptchaSameEnvelopeHasFreshIdentityAndOriginalTimeouts(t *testing.T) {
	if captchaAutoWebViewTimeout != 10*time.Second || captchaManualWebViewTimeout != 60*time.Second || captchaSelectedWebViewTimeout != 120*time.Second {
		t.Fatal("upstream budgets changed")
	}
	r := &mcrfCaptchaRegistry{}
	seen := map[string]bool{}
	for i := 0; i < 2; i++ {
		value, e := mcrfRequestCaptcha(context.Background(), r, "auto", "https://vk.com/captcha", "owned-session", time.Second, func(line string) error {
			fields := strings.Split(line, "|")
			if len(fields) != 5 || seen[fields[1]] {
				t.Fatal("fresh attempt reused retired envelope identity")
			}
			seen[fields[1]] = true
			if !r.deliver("CAPTCHA_RESULT|" + fields[1] + "|fixture-proof") {
				t.Fatal("result delivery failed")
			}
			return nil
		})
		if e != nil || value != "fixture-proof" {
			t.Fatal("owned request did not complete")
		}
	}
	if len(r.pending) != 0 {
		t.Fatal("finished waiters retained")
	}
}
func TestMcrfCaptchaCancelEmitFailureAndInvalidInputsRetireWithoutProof(t *testing.T) {
	r := &mcrfCaptchaRegistry{}
	ctx, cancel := context.WithCancel(context.Background())
	_, e := mcrfRequestCaptcha(ctx, r, "manual", "https://vk.com/captcha", "owned-session", time.Second, func(string) error { cancel(); return nil })
	if e == nil || len(r.pending) != 0 {
		t.Fatal("STOP/cancel retained waiter")
	}
	_, e = mcrfRequestCaptcha(context.Background(), r, "auto", "https://vk.com/captcha", "owned-session", time.Second, func(string) error { return errors.New("private-data") })
	if e == nil || strings.Contains(e.Error(), "private-data") || len(r.pending) != 0 {
		t.Fatal("emit failure leaked data or retained waiter")
	}
	if _, e = mcrfRequestCaptcha(context.Background(), r, "auto", "https://vk.com/captcha", "bad|session", time.Second, func(string) error { t.Fatal("malformed envelope emitted"); return nil }); e == nil {
		t.Fatal("bad session accepted")
	}
}
func TestMcrfCaptchaConcurrentSingleUseReplies(t *testing.T) {
	r := &mcrfCaptchaRegistry{}
	var completed sync.WaitGroup
	for i := 0; i < 32; i++ {
		id, w, e := r.register(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		completed.Add(1)
		go func(id string, w *mcrfCaptchaWaiter) {
			defer completed.Done()
			defer r.retire(id, w)
			if !r.deliver("CAPTCHA_RESULT|" + id + "|fixture-proof") {
				t.Error("exact result lost")
				return
			}
			select {
			case <-w.result:
			case <-time.After(time.Second):
				t.Error("result send blocked")
			}
		}(id, w)
	}
	completed.Wait()
	if len(r.pending) != 0 {
		t.Fatal("concurrent waiters leaked")
	}
}

func TestMcrfCaptchaStreamReplacementAndRootStopBothCancelWaiter(t *testing.T) {
	for _, cancelRoot := range []bool{false, true} {
		caller, stopStream := context.WithCancel(context.Background())
		root, stopChild := context.WithCancel(context.Background())
		owned, retire := mcrfCaptchaContext(caller, root)
		if cancelRoot {
			stopChild()
		} else {
			stopStream()
		}
		select {
		case <-owned.Done():
		case <-time.After(time.Second):
			t.Error("waiter outlived stream or child")
		}
		retire()
		stopStream()
		stopChild()
	}
	root, stop := context.WithCancel(context.Background())
	stop()
	owned, retire := mcrfCaptchaContext(context.Background(), root)
	defer retire()
	if owned.Err() == nil {
		t.Fatal("already stopped child accepted new waiter")
	}
}
