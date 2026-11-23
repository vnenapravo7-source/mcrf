package mailru

import (
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestMcrfAuthChangesAckUnlocksWrites(t *testing.T) {
	ack := make(chan bool, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}).Upgrade(w, r, nil)
		if err != nil {
			ack <- false
			return
		}
		defer conn.Close()
		conn.SetReadDeadline(time.Now().Add(time.Second))
		_, data, err := conn.ReadMessage()
		ack <- err == nil && string(data) == `42["message",{"type":"authChangesAck"}]`
	}))
	defer server.Close()
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	session := &DocSession{Conn: conn, ready: make(chan struct{})}
	transport := &MailruDocsTransport{}
	transport.handleMessage(session, []byte(`42["message", {"type": "authChanges"}]`))
	select {
	case ok := <-ack:
		if !ok {
			t.Fatal("authChanges was not acknowledged")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("bounded ACK timeout")
	}
	select {
	case <-session.ready:
	default:
		t.Fatal("successful ACK did not release coediting readiness")
	}
}
func TestMcrfUnrelatedFrameDoesNotReleaseReadiness(t *testing.T) {
	session := &DocSession{ready: make(chan struct{})}
	(&MailruDocsTransport{}).handleMessage(session, []byte(`42["message",{"type":"other","text":"authChanges"}]`))
	select {
	case <-session.ready:
		t.Fatal("unrelated text unlocked writes")
	default:
	}
}
func TestMcrfReadinessFallbackRemainsIdempotent(t *testing.T) {
	session := &DocSession{ready: make(chan struct{})}
	session.markReady()
	session.markReady()
	select {
	case <-session.ready:
	default:
		t.Fatal("fallback not ready")
	}
}
