// HOST FIXTURE ONLY. Never part of any reviewed runtime overlay/native package.
package tunnel
import("errors";"net";"sync")
var mcrfFixtureMu sync.RWMutex
var mcrfFixtureDial func(string)(net.Conn,error)
func McrfFixtureSetExitDial(fn func(string)(net.Conn,error))func(){mcrfFixtureMu.Lock();old:=mcrfFixtureDial;mcrfFixtureDial=fn;mcrfFixtureMu.Unlock();return func(){mcrfFixtureMu.Lock();mcrfFixtureDial=old;mcrfFixtureMu.Unlock()}}
func mcrfFixtureExitDial(addr string)(net.Conn,error){mcrfFixtureMu.RLock();fn:=mcrfFixtureDial;mcrfFixtureMu.RUnlock();if fn==nil{return nil,errors.New("host fixture denies network dial")};return fn(addr)}
