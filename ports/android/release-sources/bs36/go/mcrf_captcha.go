// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"
	"unicode"
)

// One registry per native child. A result is addressed to one exact waiter,
// never broadcast into QWDTT's legacy global result channel.
type mcrfCaptchaWaiter struct {
	ctx    context.Context
	result chan string
}
type mcrfCaptchaRegistry struct {
	mu      sync.Mutex
	pending map[string]*mcrfCaptchaWaiter
}

var mcrfCaptchaRequests = &mcrfCaptchaRegistry{}

// Background latency probes must not leave an unanswerable CAPTCHA waiter or
// walk the rest of a real account's call pool. No URL, token or proof is emitted.
func mcrfRejectCaptchaProbe(probe, interactive bool, cancel context.CancelFunc, output io.Writer) bool {
	if !probe || interactive {
		return false
	}
	fmt.Fprintln(output, "MCRF_CAPTCHA_REQUIRED")
	if cancel != nil {
		cancel()
	}
	return true
}

// Only the patched legacy Rust client understands this opt-in. The separate2.5
// factory constructs its own environment and must not inherit this capability.
func mcrfLegacyCaptchaEnv(environment []string) []string {
	result := make([]string, 0, len(environment)+1)
	for _, value := range environment {
		if !strings.EqualFold(strings.SplitN(value, "=", 2)[0], "MCRF_CAPTCHA_IDS") {
			result = append(result, value)
		}
	}
	return append(result, "MCRF_CAPTCHA_IDS=1")
}

func mcrfCaptchaActive(ctx context.Context) bool {
	if ctx == nil || ctx.Err() != nil {
		return false
	}
	deadline, bounded := ctx.Deadline()
	return !bounded || time.Now().Before(deadline)
}

func (r *mcrfCaptchaRegistry) register(ctx context.Context) (string, *mcrfCaptchaWaiter, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !mcrfCaptchaActive(ctx) || len(r.pending) >= 32 {
		return "", nil, errors.New("captcha waiter unavailable")
	}
	var entropy [16]byte
	if _, err := rand.Read(entropy[:]); err != nil {
		return "", nil, errors.New("captcha waiter unavailable")
	}
	if !mcrfCaptchaActive(ctx) {
		return "", nil, errors.New("captcha waiter unavailable")
	}
	id := hex.EncodeToString(entropy[:])
	if r.pending == nil {
		r.pending = make(map[string]*mcrfCaptchaWaiter)
	}
	if r.pending[id] != nil {
		return "", nil, errors.New("captcha waiter unavailable")
	}
	w := &mcrfCaptchaWaiter{ctx: ctx, result: make(chan string, 1)}
	r.pending[id] = w
	return id, w, nil
}
func (r *mcrfCaptchaRegistry) retire(id string, w *mcrfCaptchaWaiter) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.pending[id] == w {
		delete(r.pending, id)
	}
}
func (r *mcrfCaptchaRegistry) deliver(line string) bool {
	if len(line) > 16500 || !strings.HasPrefix(line, "CAPTCHA_RESULT|") {
		return false
	}
	fields := strings.Split(line, "|")
	if len(fields) != 3 || len(fields[1]) != 32 || len(fields[2]) < 1 || len(fields[2]) > 16384 || strings.IndexFunc(fields[2], unicode.IsControl) >= 0 {
		return false
	}
	if decoded, err := hex.DecodeString(fields[1]); err != nil || len(decoded) != 16 {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.pending[fields[1]]
	if w == nil {
		return false
	}
	delete(r.pending, fields[1]) // Single use even before the waiter resumes.
	if !mcrfCaptchaActive(w.ctx) {
		return false
	}
	w.result <- fields[2] // Capacity1, exact waiter, one send; cannot block stdin/STOP.
	return true
}

func mcrfRequestCaptcha(ctx context.Context, registry *mcrfCaptchaRegistry, mode, url, session string, timeout time.Duration, emit func(string) error) (string, error) {
	invalid := errors.New("invalid captcha request")
	if ctx == nil || registry == nil || emit == nil || timeout <= 0 || timeout > 120*time.Second ||
		(mode != "auto" && mode != "manual" && mode != "selected") || len(url) < 1 || len(url) > 8192 || len(session) < 1 || len(session) > 8192 ||
		strings.ContainsAny(url+session, "|") || strings.IndexFunc(url+session, unicode.IsControl) >= 0 {
		return "", invalid
	}
	waitCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	id, w, err := registry.register(waitCtx)
	if err != nil {
		return "", err
	}
	defer registry.retire(id, w)
	if err = emit("CAPTCHA_SOLVE|" + id + "|" + mode + "|" + url + "|" + session); err != nil {
		return "", errors.New("captcha request delivery failed")
	}
	select {
	case value := <-w.result:
		if !mcrfCaptchaActive(waitCtx) {
			return "", errors.New("captcha request expired")
		}
		if strings.HasPrefix(strings.ToLower(value), "error:") {
			return "", errors.New("webview captcha not completed")
		}
		return value, nil
	case <-waitCtx.Done():
		return "", errors.New("captcha request expired or cancelled")
	}
}
func mcrfRequestWebViewCaptcha(ctx context.Context, captcha *VkCaptchaError, mode string, timeout time.Duration) (string, error) {
	if captcha == nil {
		return "", errors.New("invalid captcha request")
	}
	owned, retire := mcrfCaptchaContext(ctx, bridgeContext)
	defer retire()
	return mcrfRequestCaptcha(owned, mcrfCaptchaRequests, mode, captcha.RedirectURI, captcha.SessionToken, timeout, func(line string) error {
		_, err := fmt.Println(line)
		return err // Private IPC, not an application log.
	})
}

// A stream replacement and the child-wide STOP both retire the same waiter.
// Neither a detached stream nor a root cancellation can keep it alive.
func mcrfCaptchaContext(caller, root context.Context) (context.Context, context.CancelFunc) {
	if caller == nil {
		caller = context.Background()
	}
	ctx, cancel := context.WithCancel(caller)
	if root == nil {
		return ctx, cancel
	}
	if root.Err() != nil {
		cancel()
	}
	stop := context.AfterFunc(root, cancel)
	return ctx, func() { stop(); cancel() }
}
