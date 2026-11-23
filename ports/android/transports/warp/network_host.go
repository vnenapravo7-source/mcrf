//go:build !android
package warpscout
import "syscall"
func McrfBindNetwork()error{return nil}
func mcrfControl(_, _ string,raw syscall.RawConn)error{return nil}
