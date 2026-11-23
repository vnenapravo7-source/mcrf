//go:build !cgo

package main

import utls "github.com/refraction-networking/utls"

// TEST ONLY for the Windows pure-Go TLS/registry/policy subset. Compressed
// certificates are deliberately refused here. Production requires CGO and
// bounded native codecs; its compressed traffic is a separate common gate.
var decoderInstalled49 = utls.MCRFInstallDecoder49(func(uint16, uint32, []byte) ([]byte, error) { return nil, errLimit })
