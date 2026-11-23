package websocket

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"syscall"
	"testing"
	"time"
)

func TestMcrfRejectUntrustedTls(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(101) }))
	defer server.Close()
	ws, err := ConnectWithDialer("127.0.0.1", "localhost", "/apiws", time.Second, func(_, _ string) (net.Conn, error) { return net.Dial("tcp", server.Listener.Addr().String()) })
	if ws != nil {
		ws.Close()
		t.Fatal("Untrusted TLS certificate accepted")
	}
	if FailureStage(err) != "tls_trust" {
		t.Fatalf("expected TLS stage: %v", err)
	}
}
func TestMcrfHandshakeHasDeadline(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan struct{})
	defer close(done)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		<-done
	}()
	start := time.Now()
	ws, err := ConnectWithDialer("127.0.0.1", "localhost", "/apiws", 100*time.Millisecond, func(_, _ string) (net.Conn, error) { return net.Dial("tcp", listener.Addr().String()) })
	if ws != nil {
		ws.Close()
		t.Fatal("Silent TLS peer accepted")
	}
	if FailureStage(err) != "tls_timeout" || time.Since(start) > 2*time.Second {
		t.Fatal("TLS handshake exceeded deadline or stage differs")
	}
}

func TestMcrfClassifiesDialAndHTTPFailures(t *testing.T) {
	refused := errors.New("owned dial refusal")
	_, err := ConnectWithDialer("127.0.0.1", "localhost", "/apiws", time.Second, func(_, _ string) (net.Conn, error) { return nil, refused })
	if FailureStage(err) != "tcp" || !errors.Is(err, refused) {
		t.Fatalf("expected TCP stage with original cause: %v", err)
	}
	if FailureStage(&HandshakeError{StatusCode: 403}) != "http" {
		t.Fatal("HTTP response failure classified incorrectly")
	}
}
func TestMcrfTlsFailureClasses(t *testing.T) {
	for _, test := range []struct {
		err  error
		want string
	}{
		{&tls.CertificateVerificationError{Err: x509.HostnameError{}}, "tls_name"},
		{&net.OpError{Op: "remote error", Err: errors.New("opaque alert")}, "tls_remote"},
		{syscall.ECONNRESET, "tls_remote"},
		{io.ErrUnexpectedEOF, "tls_remote"},
		{tls.RecordHeaderError{}, "tls_record"},
		{errors.New("other TLS handshake error"), "tls_other"},
	} {
		if got := FailureStage(&StageError{Stage: "tls", Err: test.err}); got != test.want {
			t.Errorf("%T classified as %s, want %s", test.err, got, test.want)
		}
	}
}
func TestMcrfClassifiesRealNonTLSRecord(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			defer conn.Close()
			err = conn.SetDeadline(time.Now().Add(time.Second))
			if err == nil {
				_, err = io.ReadFull(conn, make([]byte, 5))
			}
			if err == nil {
				_, err = io.WriteString(conn, "HTTP/1.1 403 Forbidden\r\n\r\n")
			}
		}
		done <- err
	}()
	_, err = ConnectWithDialer("127.0.0.1", "localhost", "/apiws", time.Second, func(_, _ string) (net.Conn, error) {
		return net.Dial("tcp", listener.Addr().String())
	})
	if FailureStage(err) != "tls_record" {
		t.Fatalf("expected TLS record stage, got %v", err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
