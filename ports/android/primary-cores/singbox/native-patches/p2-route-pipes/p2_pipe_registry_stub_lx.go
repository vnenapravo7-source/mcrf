//go:build !with_gvisor || (!linux && !android)

package libbox

import (
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/include"
)

// Other platform/build families never acquire the Android private-pipe API or
// outbound type through a shared config seam. No fallback transport is added.
func mcrfOutboundRegistry()*outbound.Registry{return include.OutboundRegistry()}
