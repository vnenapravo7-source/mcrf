package adapter

import "net/netip"

var mcrfExternalFakeIPv4 = netip.MustParsePrefix("198.18.0.0/15")
var mcrfExternalFakeIPv6 = netip.MustParsePrefix("fdfe:dcba:9876::/96")

// The observed external resolver encodes benchmark IPv4 in its IPv6 range.
// Genuine private IPv6 and the TUN's own interface addresses are not synthetic.
func McrfIsExternalFakeIP(addr netip.Addr) bool {
 if mcrfExternalFakeIPv4.Contains(addr) { return true }
 if !mcrfExternalFakeIPv6.Contains(addr) { return false }
 bytes := addr.As16()
 return mcrfExternalFakeIPv4.Contains(netip.AddrFrom4([4]byte{bytes[12],bytes[13],bytes[14],bytes[15]}))
}
