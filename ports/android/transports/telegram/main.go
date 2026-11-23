// MCRF's loopback-only entry point. Parent stdin ownership stops orphaned bridges.
package main

import (
    "bufio"
    "context"
    "flag"
    "fmt"
    "log"
    "os"
    "os/signal"
    "syscall"
    "strings"
    "github.com/y0sy4/tg-ws-proxy-go/internal/config"
    "github.com/y0sy4/tg-ws-proxy-go/internal/proxy"
    "github.com/y0sy4/tg-ws-proxy-go/internal/mcrfnet"
)

func main() {
    port := flag.Int("port",19071,"Local Telegram SOCKS port")
    flag.Parse()
    if *port<1024 || *port>65535 || flag.NArg()!=0 {os.Exit(2)}
    if err:=mcrfnet.Bind();err!=nil {fmt.Fprintln(os.Stdout,"MCRF_TGWS_NETWORK_ERROR");os.Exit(1)}
    cfg:=config.DefaultConfig()
    cfg.Host="127.0.0.1"; cfg.Port=*port; cfg.PoolSize=0
    server,err:=proxy.NewServer(cfg,log.New(transportLog{},"",0),"")
    if err!=nil {os.Exit(1)}
    ctx,cancel:=signal.NotifyContext(context.Background(),syscall.SIGINT,syscall.SIGTERM)
    defer cancel()
    go func(){scanner:=bufio.NewScanner(os.Stdin);scanner.Buffer(make([]byte,128),1024);for scanner.Scan(){if scanner.Text()=="STOP" {break}};cancel()}()
    // No private account details, destination URLs or auth headers go to logcat.
    if err=server.Start(ctx);err!=nil {fmt.Fprintln(os.Stdout,"MCRF_TGWS_ERROR");os.Exit(1)}
}

// Only transport enums leave the process, never raw logs with destinations.
type transportLog struct{}
func(transportLog)Write(data []byte)(int,error){
    line:=string(data)
    switch strings.TrimSpace(line) {
    case "MCRF_TGWS_WS_CONNECTED":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WS")
    case "MCRF_TGWS_TCP_CONNECTED":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_TCP")
    case "MCRF_TGWS_WSS_FAILED_tcp":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TCP_ERROR")
    case "MCRF_TGWS_WSS_FAILED_tls_name":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TLS_NAME_ERROR")
    case "MCRF_TGWS_WSS_FAILED_tls_trust":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TLS_TRUST_ERROR")
    case "MCRF_TGWS_WSS_FAILED_tls_timeout":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TLS_TIMEOUT_ERROR")
    case "MCRF_TGWS_WSS_FAILED_tls_remote":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TLS_REMOTE_ERROR")
    case "MCRF_TGWS_WSS_FAILED_tls_record":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TLS_RECORD_ERROR")
    case "MCRF_TGWS_WSS_FAILED_tls_other":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_TLS_ERROR")
    case "MCRF_TGWS_WSS_FAILED_http":
        fmt.Fprintln(os.Stdout,"MCRF_TGWS_WSS_HTTP_ERROR")
    }
    return len(data),nil
}
