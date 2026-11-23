//go:build android
package warpscout
/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"
import("fmt";"net";"os";"strconv";"syscall")
var mcrfHandle uint64
func McrfBindNetwork()error{
    value,err:=strconv.ParseUint(os.Getenv("MCRF_NETWORK_HANDLE"),10,64);if err!=nil||value==0{return fmt.Errorf("missing physical network")}
    if C.android_setprocnetwork(C.net_handle_t(value))!=0{return fmt.Errorf("physical DNS binding failed")};mcrfHandle=value
    net.DefaultResolver=&net.Resolver{PreferGo:false}
    return nil
}
func mcrfControl(_, _ string,raw syscall.RawConn)error {
    if mcrfHandle==0{return fmt.Errorf("missing physical network")};var bindErr error
    err:=raw.Control(func(fd uintptr){if C.android_setsocknetwork(C.net_handle_t(mcrfHandle),C.int(fd))!=0{bindErr=fmt.Errorf("physical socket binding failed")}});if err!=nil{return err};return bindErr
}
