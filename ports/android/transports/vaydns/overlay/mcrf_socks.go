package client

import (
	"crypto/subtle"
	"errors"
	"io"
	"net"
)

// McrfSOCKS enforces RFC1929 on the Android side even when the remote SOCKS
// endpoint uses no authentication. Credentials stay in memory, not log/argv.
func McrfSOCKS(user, password, remoteUser, remotePassword string) (McrfBridge, error) {
	valid := func(s string) bool { return len(s) > 0 && len(s) <= 255 }
	if !valid(user) || !valid(password) || (remoteUser == "") != (remotePassword == "") || len(remoteUser) > 255 || len(remotePassword) > 255 {
		return McrfBridge{}, errors.New("invalid SOCKS credentials")
	}
	local := func(conn net.Conn) error {
		header := make([]byte, 2)
		if _, err := io.ReadFull(conn, header); err != nil {
			return err
		}
		if header[0] != 5 || header[1] == 0 {
			return errors.New("invalid local SOCKS greeting")
		}
		methods := make([]byte, int(header[1]))
		if _, err := io.ReadFull(conn, methods); err != nil {
			return err
		}
		found := false
		for _, method := range methods {
			if method == 2 {
				found = true
			}
		}
		if !found {
			mcrfWrite(conn, []byte{5, 255})
			return errors.New("local authentication required")
		}
		if err := mcrfWrite(conn, []byte{5, 2}); err != nil {
			return err
		}
		if _, err := io.ReadFull(conn, header); err != nil {
			return err
		}
		if header[0] != 1 || header[1] == 0 {
			return errors.New("invalid local SOCKS authentication")
		}
		givenUser := make([]byte, int(header[1]))
		if _, err := io.ReadFull(conn, givenUser); err != nil {
			return err
		}
		if _, err := io.ReadFull(conn, header[:1]); err != nil {
			return err
		}
		givenPassword := make([]byte, int(header[0]))
		if _, err := io.ReadFull(conn, givenPassword); err != nil {
			return err
		}
		matched := subtle.ConstantTimeCompare(givenUser, []byte(user)) & subtle.ConstantTimeCompare(givenPassword, []byte(password))
		if matched != 1 {
			mcrfWrite(conn, []byte{1, 1})
			return errors.New("local authentication rejected")
		}
		return mcrfWrite(conn, []byte{1, 0})
	}
	remote := func(conn net.Conn) error {
		method := byte(0)
		if remoteUser != "" {
			method = 2
		}
		if err := mcrfWrite(conn, []byte{5, 1, method}); err != nil {
			return err
		}
		reply := make([]byte, 2)
		if _, err := io.ReadFull(conn, reply); err != nil {
			return err
		}
		if reply[0] != 5 {
			return ErrMcrfRemoteProtocol
		}
		if reply[1] != method {
			return ErrMcrfRemoteAuth
		}
		if method == 2 {
			request := append([]byte{1, byte(len(remoteUser))}, []byte(remoteUser)...)
			request = append(request, byte(len(remotePassword)))
			request = append(request, []byte(remotePassword)...)
			if err := mcrfWrite(conn, request); err != nil {
				return err
			}
			if _, err := io.ReadFull(conn, reply); err != nil {
				return err
			}
			if reply[0] != 1 {
				return ErrMcrfRemoteProtocol
			}
			if reply[1] != 0 {
				return ErrMcrfRemoteAuth
			}
		}
		return nil
	}
	return McrfBridge{LocalAuth: local, RemoteAuth: remote}, nil
}

func mcrfWrite(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n <= 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}
