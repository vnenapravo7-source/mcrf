//go:build !with_lx_raw_packet || (!linux && !android)

package libbox

import (
	"context"
	"errors"
)

func prepareMcrfRawPacketEndpointContext(ctx context.Context) (context.Context, func(string, int32, string, int64, int32, string, int32) error, func(string) error, func(), func()) {
	return ctx, nil, nil, nil, nil
}

func (s *CommandServer) RegisterMcrfRawPacketEndpoint(string, int32, string, int64, int32, string, int32) error {
	return errors.New("MCRF_RAW_PACKET_UNAVAILABLE")
}

func (s *CommandServer) UnregisterMcrfRawPacketEndpoint(string) error {
	return errors.New("MCRF_RAW_PACKET_UNAVAILABLE")
}
