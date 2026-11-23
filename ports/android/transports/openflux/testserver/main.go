// SPDX-License-Identifier: GPL-3.0-or-later
// Separate debug-only executable; never packaged in release APKs.
package main

import (
    "bufio"
    "encoding/json"
    "fmt"
    "io"
    "net"
    "os"
    "strconv"
    mobile "openflux-mobile"
)

func main() {
    if len(os.Args) != 3 || os.Args[1] != "--config" { os.Exit(2) }
    f, err := os.Open(os.Args[2]); if err != nil { os.Exit(2) }
    data, err := io.ReadAll(io.LimitReader(f, 65537)); f.Close()
    var c struct { Listen, Secret, Context string }
    if err != nil || len(data)>65536 || json.Unmarshal(data,&c)!=nil { os.Exit(2) }
    h,p,e:=net.SplitHostPort(c.Listen);number,_:=strconv.Atoi(p)
    if e!=nil || h!="127.0.0.1" || number<1 || number>65535 || len(c.Secret)<16 { os.Exit(2) }
    specs,_:=json.Marshal(map[string]any{"context":c.Context,"transports":[]any{map[string]any{"name":"direct","type":"direct","priority":100,"params":map[string]any{"listen":c.Listen}}}})
    mobile.SetDebugLevel(0);mobile.SetLowMemory(true)
    if mobile.StartSessionExit(string(specs),c.Secret)!="" { os.Exit(1) }
    defer mobile.StopExit()
    fmt.Println("MCRF_EXIT_READY")
    scanner:=bufio.NewScanner(io.LimitReader(os.Stdin,8192))
    for scanner.Scan(){if scanner.Text()=="STOP" {return}}
}
