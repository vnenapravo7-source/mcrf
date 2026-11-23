//go:build with_lx_carrier_stack && with_lx_raw_packet && with_gvisor && (linux || android)

package libbox

import (
	"errors"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

func McrfOriginalCarrierPacketSchema() int32 { return 1 }

// Synchronous proof for the module's pending owned FD, before its ACK. The
// pending Java duplicate retains ownership until CommandServer registration.
func CheckMcrfOriginalCarrierPacketEndpoint(fd int32, nonce string, generation int64, peerPID int32, local string, mtu int32) error {
	endpoint, err := duplicateAndValidateMcrfRawEndpoint(int(fd), nonce, generation, peerPID, local, mtu)
	if err != nil {
		return err
	}
	return endpoint.Close()
}

func (s *CommandServer) RegisterMcrfOriginalCarrierPacketEndpoint(tag string, fd int32, nonce string, generation int64, peerPID int32, local string, mtu int32) error {
	registry, ok := service.FromContext[adapter.OriginalPacketEndpointProvider](s.ctx).(*adapter.OriginalPacketEndpointRegistry)
	if !ok || s.handler == nil {
		return errors.New("MCRF_ORIGINAL_CARRIER_CONTEXT")
	}
	endpoint, err := duplicateAndValidateMcrfRawEndpoint(int(fd), nonce, generation, peerPID, local, mtu)
	if err != nil {
		return err
	}
	endpoint.carrierStack = true
	registry.SetOriginalCarrierFailureHandler(func() {
		// A fixed internal signal, never remote text or a profile value. The Java
		// adapter turns this into a failed generation rather than a healthy stop.
		s.handler.WriteDebugMessage("MCRF_ORIGINAL_CARRIER_FAILED")
		_ = s.handler.ServiceStop()
	})
	if err = registry.Register(tag, endpoint); err != nil {
		_ = endpoint.Close()
		return err
	}
	return nil
}

func (s *CommandServer) UnregisterMcrfOriginalCarrierPacketEndpoint(tag string) error {
	return s.UnregisterMcrfRawPacketEndpoint(tag)
}
