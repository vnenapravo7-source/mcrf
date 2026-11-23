//go:build !with_lx_raw_packet_port || !with_lx_raw_packet || (!linux && !android)

package socks

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
)

func wrapMcrfOriginalPacketOutbound(_ context.Context, outbound adapter.Outbound) adapter.Outbound {
	return outbound
}
