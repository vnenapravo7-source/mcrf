//go:build android

package mcrfnet

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"
import("fmt";"net";"os";"strconv";"syscall";"time")

var handle uint64
func Bind()error {
    value,err:=strconv.ParseUint(os.Getenv("MCRF_NETWORK_HANDLE"),10,64)
    if err!=nil||value==0{return fmt.Errorf("physical network missing")}
    if C.android_setprocnetwork(C.net_handle_t(value))!=0{return fmt.Errorf("physical DNS binding failed")}
    handle=value
    return nil
}
func DialTimeout(network,address string,timeout time.Duration)(net.Conn,error) {
    if handle==0{return nil,fmt.Errorf("physical network missing")}
    dialer:=net.Dialer{Timeout:timeout,Control:func(_, _ string,raw syscall.RawConn)error {
        var bindErr error
        err:=raw.Control(func(fd uintptr){if C.android_setsocknetwork(C.net_handle_t(handle),C.int(fd))!=0{bindErr=fmt.Errorf("physical socket binding failed")}})
        if err!=nil{return err};return bindErr
    }}
    return dialer.Dial(network,address)
}
