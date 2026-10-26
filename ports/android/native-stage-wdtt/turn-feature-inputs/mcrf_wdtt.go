// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

type mcrfProfile struct {
	Peer        string   `json:"peer"`
	Password    string   `json:"password"`
	Hashes      []string `json:"hashes"`
	Workers     int      `json:"workers"`
	Device      string   `json:"device_id"`
	Listen      string   `json:"listen"`
	SOCKS       string   `json:"socks"`
	User        string   `json:"socks_user"`
	Pass        string   `json:"socks_pass"`
	Captcha     string   `json:"captcha_mode"`
	Fingerprint string   `json:"fingerprint"`
	ProbeOnly   bool     `json:"probe_only"`
	VKCallsPreflight *bool `json:"vkcalls_preflight"`
	TurnStreamFirst bool `json:"turn_stream_first"`
	TurnSNI string `json:"turn_sni"`
	ClientIDs string `json:"client_ids"`
	CustomVKClientID string `json:"custom_vk_client_id"`
	CustomVKClientSecret string `json:"custom_vk_client_secret"`
	RtMasque bool `json:"rt_masque"`
	MasqueAccount *warpMasqueConfig `json:"masque_account"`
}

var mcrfWdttProbeOnly atomic.Bool
var mcrfWdttPrivateBridge atomic.Bool
var mcrfWdttCancel context.CancelFunc

func mcrfWdttProbeGate(android, probe bool, cancel context.CancelFunc, output io.Writer) bool {
	if !android || !probe {
		return false
	}
	fmt.Fprintln(output, "MCRF_CAPTCHA_REQUIRED")
	if cancel != nil {
		cancel()
	}
	return true
}
func mcrfWdttCaptchaProbe() bool {
	return mcrfWdttProbeGate(runtime.GOOS == "android", mcrfWdttProbeOnly.Load(), mcrfWdttCancel, os.Stdout)
}
func mcrfWdttReplyAllowed(android, private bool, result CaptchaResult) bool {
	return !android || !private || result.RequestID != ""
}
func mcrfWdttAcceptReply(result CaptchaResult) bool {
	return mcrfWdttReplyAllowed(runtime.GOOS == "android", mcrfWdttPrivateBridge.Load(), result)
}
func mcrfWdttCaptchaActive(ctx context.Context) bool {
	if ctx == nil || ctx.Err() != nil {
		return false
	}
	deadline, bounded := ctx.Deadline()
	return !bounded || time.Now().Before(deadline)
}

// Replies are untrusted IPC data. Preserve success/timeout classification but
// never interpolate an arbitrary error payload into logs or exported errors.
func mcrfWdttCaptchaReply(value string) (string, error) {
	result := strings.TrimSpace(value)
	if result == "" {
		return "", errors.New("webview captcha returned empty result")
	}
	lower := strings.ToLower(result)
	if lower == "error:timeout" {
		return "", errors.New("webview captcha timed out")
	}
	if strings.HasPrefix(lower, "error:") {
		return "", errors.New("webview captcha failed")
	}
	return result, nil
}
func mcrfRead(path string) (*mcrfProfile, error) {
	f, e := os.Open(path)
	if e != nil {
		return nil, errors.New("private profile unavailable")
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 65537))
	var c mcrfProfile
	if e != nil || len(b) > 65536 || json.Unmarshal(b, &c) != nil {
		return nil, errors.New("invalid profile")
	}
	h, p, e := net.SplitHostPort(c.Peer)
	n, _ := strconv.Atoi(p)
	if e != nil || h == "" || n < 1 || n > 65535 || c.Password == "" || c.Device == "" || strings.ContainsAny(c.Password+c.Device, "|\r\n") {
		return nil, errors.New("invalid peer credentials")
	}
	if len(c.Hashes) < 1 || c.Workers < 9 || c.Workers > 108 || c.Workers%9 != 0 {
		return nil, errors.New("invalid call pool")
	}
	for _, hash := range c.Hashes {
		if len(hash) < 16 || strings.ContainsAny(hash, " ,|\r\n") {
			return nil, errors.New("invalid call")
		}
	}
	for _, address := range []string{c.Listen, c.SOCKS} {
		h, p, e := net.SplitHostPort(address)
		n, _ := strconv.Atoi(p)
		if e != nil || h != "127.0.0.1" || n < 1 || n > 65535 {
			return nil, errors.New("loopback required")
		}
	}
	if len(c.User) < 1 || len(c.User) > 255 || len(c.Pass) < 1 || len(c.Pass) > 255 {
		return nil, errors.New("authentication required")
	}
	if c.Captcha != "auto" && c.Captcha != "wv" && c.Captcha != "rjs" {
		return nil, errors.New("invalid captcha mode")
	}
	if c.Fingerprint != "android" && c.Fingerprint != "chrome" && c.Fingerprint != "firefox" && c.Fingerprint != "safari" && c.Fingerprint != "ios" {
		return nil, errors.New("invalid fingerprint")
	}
	if !mcrfWdttProviderOptionsValid(&c) {
		return nil, errors.New("invalid TURN provider options")
	}
	return &c, nil
}
func mcrfWdttProviderOptionsValid(c *mcrfProfile) bool {
	if c == nil || len(c.TurnSNI)>253 || len(c.ClientIDs)>1024 { return false }
	if c.RtMasque && (c.TurnSNI=="" || c.MasqueAccount==nil || c.MasqueAccount.validate()!=nil) {return false}
	if !c.RtMasque && c.MasqueAccount!=nil {return false}
	if c.CustomVKClientID!="" || c.CustomVKClientSecret!="" {
		if len(c.CustomVKClientID)==0 || len(c.CustomVKClientID)>20 || strings.TrimSpace(c.CustomVKClientSecret)=="" || len(c.CustomVKClientSecret)>4096 { return false }
		for _,ch:=range c.CustomVKClientID {if ch<'0'||ch>'9' {return false}}
		for _,ch:=range c.CustomVKClientSecret {if ch<32||ch==127 {return false}}
	}
	if c.TurnSNI!="" {
		for _, label := range strings.Split(c.TurnSNI,".") {
			if len(label)==0 || len(label)>63 || label[0]=='-' || label[len(label)-1]=='-' { return false }
			for _, ch := range label { if !(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'||ch>='0'&&ch<='9'||ch=='-') { return false } }
		}
	}
	if c.ClientIDs!="" {
		ids:=strings.Split(c.ClientIDs,",");if len(ids)>32 { return false }
		for _, id:=range ids { id=strings.TrimSpace(id);if len(id)==0||len(id)>20 { return false };for _,ch:=range id { if ch<'0'||ch>'9' { return false } } }
	}
	return true
}
func mcrfWdttProviderFlags(c *mcrfProfile) map[string]string {
	fastCalls:=true
	if c.VKCallsPreflight!=nil {fastCalls=*c.VKCallsPreflight}
	return map[string]string{"vkcalls-preflight":strconv.FormatBool(fastCalls),"turn-stream-first":strconv.FormatBool(c.TurnStreamFirst),"turn-sni":c.TurnSNI,"client-ids":c.ClientIDs}
}
func mcrfWdttMasqueFlags(path string) map[string]string {
    return map[string]string{"rt-masque":"true","rt-masque-config":filepath.Join(filepath.Dir(path),"masque-account.json"),"rt-masque-accept-tos":"false"}
}
func mcrfApply(path string) (nativeStartupSecrets, error) {
	c, e := mcrfRead(path)
	if e != nil {
		return nativeStartupSecrets{}, e
	}
	mcrfWdttProbeOnly.Store(c.ProbeOnly)
	mcrfWdttPrivateBridge.Store(true)
	for key, value := range map[string]string{"peer": c.Peer, "device-id": c.Device, "listen": c.Listen, "socks-listen": c.SOCKS, "mode": "socks5", "socks-auth": "true", "n": strconv.Itoa(c.Workers), "captcha-mode": c.Captcha, "fingerprint": c.Fingerprint, "hash-fallback": "true"} {
		if e := flag.Set(key, value); e != nil {
			return nativeStartupSecrets{}, errors.New("invalid bridge options")
		}
	}
	for key, value := range mcrfWdttProviderFlags(c) {
		if e := flag.Set(key,value); e != nil { return nativeStartupSecrets{}, errors.New("unsupported TURN provider option") }
	}
	if c.RtMasque {
		accountPath:=filepath.Join(filepath.Dir(path),"masque-account.json")
		if e:=saveWarpMasqueConfig(accountPath,c.MasqueAccount);e!=nil {return nativeStartupSecrets{},errors.New("private MASQUE account unavailable")}
		for key,value:=range mcrfWdttMasqueFlags(path) {
			if e:=flag.Set(key,value);e!=nil {return nativeStartupSecrets{},errors.New("unsupported MASQUE bridge option")}
		}
	}
	return nativeStartupSecrets{VKHashes: strings.Join(c.Hashes, ","), ConnectionPassword: c.Password, SocksUsername: c.User, SocksPassword: c.Pass, CustomVKClientID:c.CustomVKClientID, CustomVKClientSecret:c.CustomVKClientSecret}, nil
}

// Separate explicit-connect preflight. Ordinary/probe processes can only reuse
// the account supplied in their private config and never enroll a replacement.
func mcrfEnrollMasque(ctx context.Context,path string) error {
    file,err:=os.Open(path);if err!=nil {return errors.New("private MASQUE request unavailable")};defer file.Close()
    info,err:=file.Stat();if err!=nil||info.Size()<1||info.Size()>4096 {return errors.New("invalid MASQUE request size")}
    var request struct {SNI string `json:"turn_sni"`;Accept bool `json:"accept_tos"`}
    decoder:=json.NewDecoder(io.LimitReader(file,4097));decoder.DisallowUnknownFields()
    if decoder.Decode(&request)!=nil||!request.Accept||len(request.SNI)>253 {return errors.New("invalid MASQUE request")}
    var extra any;if decoder.Decode(&extra)!=io.EOF {return errors.New("invalid trailing MASQUE request")}
    bounded,cancel:=context.WithTimeout(ctx,40*time.Second);defer cancel()
    manager,err:=newWarpMasqueManager(bounded,filepath.Join(filepath.Dir(path),"masque-account.json"),request.SNI,true)
    if err!=nil {return errors.New("invalid MASQUE enrollment settings")}
    manager.singleEnrollment=true
    if _,err=manager.ensureConfigLocked();err!=nil {return errors.New("MASQUE registration unavailable")}
    return nil
}
