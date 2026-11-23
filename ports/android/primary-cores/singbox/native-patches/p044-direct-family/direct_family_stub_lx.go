//go:build !with_mcrf_direct_family

package route

import (
	"github.com/sagernet/sing-box/adapter"
	N "github.com/sagernet/sing/common/network"
)

func (r *Router) mcrfDirectFamilyDialer(outbound adapter.Outbound, _ adapter.Rule, _ adapter.InboundContext) N.Dialer {
	return outbound
}
