//go:build tools

package wgbackend

// Keep the exact binding generator available to gomobile; not linked into runtime.
import _ "github.com/sagernet/gomobile/bind"
