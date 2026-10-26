// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "bufio"
    "context"
    "encoding/json"
    "errors"
    "fmt"
    "io"
    "net"
    "os"
    "os/signal"
    "strconv"
    "syscall"
    "time"

    mobile "openflux-mobile"
    "github.com/p1neappleXpress/OpenFlux/share"
)

type privateConfig struct {
    share.Config
    SOCKS string `json:"socks"`
    User string `json:"socks_user"`
    Password string `json:"socks_pass"`
    VerificationUI bool `json:"mcrf_verification_ui"`
    OwnInlineUI bool `json:"mcrf_own_inline_ui"`
    OwnHTTPUI bool `json:"mcrf_own_http_ui"`
}

func readConfig(path string) (*privateConfig, error) {
    f, err := os.Open(path)
    if err != nil { return nil, errors.New("cannot read private profile") }
    defer f.Close()
    data, err := io.ReadAll(io.LimitReader(f, 65537))
    if err != nil || len(data) > 65536 { return nil, errors.New("invalid private profile size") }
    var c privateConfig
    if json.Unmarshal(data, &c) != nil || c.Validate() != nil { return nil, errors.New("invalid OpenFlux profile") }
    host, port, err := net.SplitHostPort(c.SOCKS)
    number, _ := strconv.Atoi(port)
    if err != nil || host != "127.0.0.1" || number < 1 || number > 65535 ||
        len([]byte(c.User)) < 1 || len([]byte(c.User)) > 255 || len([]byte(c.Password)) < 1 || len([]byte(c.Password)) > 255 {
        return nil, errors.New("authenticated loopback listener required")
    }
    return &c, nil
}

func run(path string) error {
    c, err := readConfig(path)
    if err != nil { return err }
    payload, err := json.Marshal(c.Config)
    if err != nil { return errors.New("invalid profile") }
    mobile.SetDebugLevel(0)
    mobile.SetLowMemory(true)
    ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
    defer cancel()
    if stagedProviderStart != nil { stagedProviderStart(c.VerificationUI) }
    if stagedOwnInlineStart != nil { stagedOwnInlineStart(c.VerificationUI && c.OwnInlineUI) }
    if stagedOwnHTTPStart != nil { stagedOwnHTTPStart(c.VerificationUI && c.OwnHTTPUI) }
    go func() {
        defer func() { if stagedProviderStop != nil { stagedProviderStop() }; cancel() }()
        scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 8192))
        if stagedProviderInput != nil { scanner = bufio.NewScanner(os.Stdin); scanner.Buffer(make([]byte,4096),16385) }
        if stagedProviderInput != nil && stagedOwnHTTPStart != nil && c.VerificationUI && c.OwnHTTPUI {
            scanner.Buffer(make([]byte,4096),mcrfHTTPStdinLimit)
        }
        commands := 0
        for scanner.Scan() {
            line := scanner.Text()
            if line == "STOP" { return }
            commands++
            if stagedProviderInput != nil && (commands > 64 || !stagedProviderInput(line)) { return }
        }
    }()
    connected := false
    defer func(){
        if stagedProviderStop!=nil {stagedProviderStop()}
        if stagedObservationFinish!=nil {stagedObservationFinish(mobile.StopProxy,connected)} else {mobile.StopProxy()}
    }()
    message, cancelled := awaitProxyStartup(ctx, func() string {
        return mobile.McrfStartProxy(string(payload), c.SOCKS, c.User, c.Password)
    })
    if cancelled { return nil }
    if message != "" {
        return errors.New("OpenFlux transport could not start")
    }
    // Listener-ready is not carrier-ready. Classic providers can report a
    // joined document even without an exit peer; an HTTP probe is still
    // required before accepting them into automatic selection.
    deadline := time.NewTimer(60*time.Second)
    defer deadline.Stop()
    tick := time.NewTicker(200*time.Millisecond)
    defer tick.Stop()
    verificationExtended := false
    for {
        select {
        case <-ctx.Done(): return nil
        case <-deadline.C:
            if !connected { return errors.New("OpenFlux connection timed out") }
        case <-tick.C:
            if stagedProviderCheck != nil {
                if code := stagedProviderCheck(); code != "" {
                    fmt.Println("MCRF_OPENFLUX_FAILURE " + code)
                    return errors.New("OpenFlux provider verification required")
                }
            }
            if c.VerificationUI && !verificationExtended && stagedProviderWaiting != nil && stagedProviderWaiting() {
                if !deadline.Stop() { select { case <-deadline.C: default: } }
                deadline.Reset(150*time.Second)
                verificationExtended = true // One bounded extension, never per-page renewal.
            }
            _ = mobile.ReadLogs()
            if !mobile.ProxyIsRunning() { return errors.New("OpenFlux proxy stopped") }
            if !connected && mobile.ProxyIsConnected() {
                connected = true
                tick.Reset(2*time.Second) // no permanent 5 Hz wakeups after setup
                fmt.Println("MCRF_READY")
            }
        }
    }
}

// The bridge is a dedicated executable, NOT an embeddable shared runtime.
// Upstream Start may synchronously wait for a peer handshake before publishing
// its transport into mobile.StopProxy's state. STOP/EOF must still return from
// main: normal process exit retires that not-yet-published startup and its
// sockets/goroutines. A buffered result cannot orphan a blocked result sender.
// Never infer a drained startup from this return; Android must observe exit+EOF.
func awaitProxyStartup(ctx context.Context, start func() string) (string, bool) {
    if ctx.Err() != nil { return "", true }
    result := make(chan string, 1)
    go func() { result <- start() }()
    select {
    case <-ctx.Done(): return "", true
    case message := <-result:
        if ctx.Err() != nil { return "", true }
        return message, false
    }
}

// Assigned only by the separately staged newer-core source overlay.
var stagedScriptUpdate func(string) error

// A fresh source-bound module may inspect its own provider state. Never pass
// provider URLs, inline HTML or cookies to stdout or to the unrelated VK UI.
var stagedProviderCheck func() string
var stagedProviderStart func(bool)
var stagedOwnInlineStart func(bool)
var stagedOwnHTTPStart func(bool)
// Literal keeps legacy non-fresh producers independent of the optional overlay.
const mcrfHTTPStdinLimit=131074
var stagedProviderStop func()
var stagedProviderInput func(string) bool
var stagedProviderWaiting func() bool
var stagedObservationFinish func(func(), bool)

func main() {
    if len(os.Args) == 3 && os.Args[1] == "--mcrf-script-config" && stagedScriptUpdate != nil {
        if err := stagedScriptUpdate(os.Args[2]); err != nil { fmt.Fprintln(os.Stderr, "script update request rejected"); os.Exit(1) }
        return
    }
    if len(os.Args) != 3 || os.Args[1] != "--mcrf-config" { fmt.Fprintln(os.Stderr, "private configuration required"); os.Exit(2) }
    if err := run(os.Args[2]); err != nil { fmt.Fprintln(os.Stderr, err.Error()); os.Exit(1) }
}
