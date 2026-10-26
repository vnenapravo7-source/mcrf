// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import "time"

// Keep the SSTP Disconnect attempt, but unblock a stalled writer within one
// second rather than waiting for the general 30-second packet write deadline.
func (s *Session) McrfClose() error {
	timer := time.AfterFunc(time.Second, func() {
		if s.tlsConn != nil {
			s.tlsConn.Close()
		}
	})
	defer timer.Stop()
	return s.Close()
}
