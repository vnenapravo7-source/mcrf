//go:build with_lx_raw_packet && (linux || android)

package libbox

import "errors"

// RegisterMcrfRawPacketEndpoint duplicates and validates an already-connected
// packet endpoint. The caller retains ownership of fd and may close it after
// this method returns. Registration never starts or restarts the service.
func (s *CommandServer) RegisterMcrfRawPacketEndpoint(outboundTag string, fd int32, endpointID string, generation int64, peerPID int32, localAddress string, mtu int32) error {
	if s == nil || s.rawPacketRegister == nil {
		return errors.New("MCRF_RAW_PACKET_UNAVAILABLE")
	}
	return s.rawPacketRegister(outboundTag, fd, endpointID, generation, peerPID, localAddress, mtu)
}

// UnregisterMcrfRawPacketEndpoint closes only the Go-owned duplicate for the
// named outbound. It does not alter routing or any unrelated endpoint.
func (s *CommandServer) UnregisterMcrfRawPacketEndpoint(outboundTag string) error {
	if s == nil || s.rawPacketUnregister == nil {
		return errors.New("MCRF_RAW_PACKET_UNAVAILABLE")
	}
	return s.rawPacketUnregister(outboundTag)
}
