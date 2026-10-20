package wgbackend

import (
	"errors"
	"github.com/sagernet/wireguard-go/tun"
	"golang.org/x/sys/unix"
	"os"
	"sync"
)

// Takes fd ownership only on success. On every rejection no os.File finalizer
// exists, so the caller can close the duplicate once without a later double-close.
func ownedTun(fd int, mtu int) (*fdTun, error) {
	request, err := unix.NewIfreq("")
	if err != nil {
		return nil, err
	}
	if unix.IoctlIfreq(fd, unix.TUNGETIFF, request) != nil {
		return nil, errors.New("WG_TUN_TYPE")
	}
	flags := request.Uint16()
	if flags&unix.IFF_TUN == 0 || flags&unix.IFF_NO_PI == 0 || flags&unix.IFF_VNET_HDR != 0 {
		return nil, errors.New("WG_TUN_FLAGS")
	}
	status, err := unix.FcntlInt(uintptr(fd), unix.F_GETFL, 0)
	// Never change shared file-description flags on the borrowed host TUN.
	if err != nil || status&unix.O_NONBLOCK == 0 {
		return nil, errors.New("WG_TUN_BLOCKING")
	}
	file := os.NewFile(uintptr(fd), "mcrf-wg-owned-tun")
	if file == nil {
		return nil, errors.New("WG_TUN_FILE")
	}
	return &fdTun{file: file, mtu: mtu, name: request.Name(), events: make(chan tun.Event)}, nil
}

type fdTun struct {
	file     *os.File
	mtu      int
	name     string
	events   chan tun.Event
	once     sync.Once
	closeErr error
}

func (t *fdTun) File() *os.File           { return t.file }
func (t *fdTun) Name() (string, error)    { return t.name, nil }
func (t *fdTun) MTU() (int, error)        { return t.mtu, nil }
func (t *fdTun) Events() <-chan tun.Event { return t.events }
func (t *fdTun) BatchSize() int           { return 1 }
func (t *fdTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 || len(sizes) == 0 || offset < 0 || offset >= len(bufs[0]) {
		return 0, errors.New("WG_TUN_BUFFER")
	}
	n, err := t.file.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}
func (t *fdTun) Write(bufs [][]byte, offset int) (int, error) {
	for i, buf := range bufs {
		if offset < 0 || offset >= len(buf) {
			return i, errors.New("WG_TUN_BUFFER")
		}
		n, err := t.file.Write(buf[offset:])
		if err != nil {
			return i, err
		}
		if n != len(buf)-offset {
			return i, errors.New("WG_TUN_SHORT_WRITE")
		}
	}
	return len(bufs), nil
}
func (t *fdTun) Close() error {
	t.once.Do(func() { t.closeErr = t.file.Close(); close(t.events) })
	return t.closeErr
}
