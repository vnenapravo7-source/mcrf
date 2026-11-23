package mailru

import (
	"bytes"
	"encoding/json"
)

// Staged-only correction: authenticate the actual message type, never a substring in data.
func mcrfMailruAuthChanges(session *DocSession, frame []byte) bool {
	if !bytes.HasPrefix(frame, []byte("42[")) {
		return false
	}
	var envelope []json.RawMessage
	if json.Unmarshal(frame[2:], &envelope) != nil || len(envelope) != 2 {
		return false
	}
	var event string
	var message struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(envelope[0], &event) != nil || event != "message" || json.Unmarshal(envelope[1], &message) != nil || message.Type != "authChanges" {
		return false
	}
	if session != nil && session.Conn != nil && session.ready != nil {
		if session.safeWrite(1, []byte(authChangesAckMsg)) == nil {
			session.markReady()
		}
	}
	return true
}

func mcrfMailruWriteReady(session *DocSession) bool {
	if session == nil || session.ready == nil {
		return false
	}
	select {
	case <-session.ready:
		return true
	default:
		return false
	}
}
