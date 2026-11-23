//go:build !android

package mcrfnet
import("net";"time")
func Bind()error{return nil}
func DialTimeout(network,address string,timeout time.Duration)(net.Conn,error){return net.DialTimeout(network,address,timeout)}
