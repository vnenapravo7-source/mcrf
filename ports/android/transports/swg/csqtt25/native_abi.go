package csqtt25

import "encoding/binary"

// Artifact identities verified against GitHub release418086617 and its asset API.
// The release body's checksum table predates the replacement assets of22Oct.
type nativeDescriptor struct {
	abi, goarch, sha256 string
	bytes               int64
	class               byte
	machine             uint16
}

func nativeForABI(abi string) (nativeDescriptor, bool) {
	switch abi {
	case "arm64-v8a":
		return nativeDescriptor{abi, "arm64", NativeSHA256, NativeBytes, 2, 183}, true
	case "armeabi-v7a":
		return nativeDescriptor{abi, "arm", "ef3c6ef1bc4937e416cb53c1ef4e29de388c3bc6dc9f06643f89ec68a2715751", 6823384, 1, 40}, true
	case "x86_64":
		return nativeDescriptor{abi, "amd64", "d3b1edf30235a879e745be8c60c3b155309f69fd3bf97e2426415c1de67a45ff", 11049008, 2, 62}, true
	default:
		return nativeDescriptor{}, false
	}
}

func nativeForHeader(header []byte, goos, goarch string) (nativeDescriptor, bool) {
	if len(header) < 20 || string(header[:4]) != "\x7fELF" || header[5] != 1 || binary.LittleEndian.Uint16(header[16:]) != 3 {
		return nativeDescriptor{}, false
	}
	for _, abi := range []string{"arm64-v8a", "armeabi-v7a", "x86_64"} {
		spec, _ := nativeForABI(abi)
		if spec.class == header[4] && spec.machine == binary.LittleEndian.Uint16(header[18:]) {
			// Non-Android host checks inspect supplied files only; Android execution
			// must match this wrapper's own ABI, never an available foreign one.
			if goos == "android" && goarch != spec.goarch {
				return nativeDescriptor{}, false
			}
			return spec, true
		}
	}
	return nativeDescriptor{}, false
}
