//go:build !with_lx_raw_packet || (!linux && !android)

package tun

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	singtun "github.com/sagernet/sing-tun"
)

func wrapMcrfRawPacketTun(_ context.Context, _ adapter.Router, _, _ string, underlying singtun.Tun) (singtun.Tun, func(), error) {
	return underlying, nil, nil
}
