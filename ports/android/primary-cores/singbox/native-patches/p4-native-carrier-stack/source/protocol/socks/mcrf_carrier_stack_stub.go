//go:build !with_lx_carrier_stack || !with_lx_raw_packet || !with_gvisor || (!linux && !android)

package socks

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
)

func wrapMcrfCarrierStackOutbound(context.Context, *Outbound) (adapter.Outbound, error, bool) {
	return nil, nil, false
}
