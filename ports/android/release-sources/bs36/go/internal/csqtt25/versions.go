package csqtt25

// Source and APK identities live in swg-versions.json. The two releases contain
// byte-identical clients on all three ABIs; neither may use 2.5.1 bytes.
func validImplementation(value string) bool {
	return value == "" || value == "swg-panel-2.5.1" || value == "swg-panel-2.5.2" || value == "swg-panel-2.5.3"
}

func NativeFilename(p Profile) (string, error) {
	if err := p.Validate(); err != nil { return "", err }
	if p.Implementation == "swg-panel-2.5.2" || p.Implementation == "swg-panel-2.5.3" { return "libcsqtt252_transport.so", nil }
	return "libcsqtt25_transport.so", nil
}

func newNativeForABI(abi string) (nativeDescriptor, bool) {
	switch abi {
	case "arm64-v8a": return nativeDescriptor{abi,"arm64","7299e9c16bc47a0d1238a8f48b54e0a489533a358c2a5384f44ff14f84825e8d",9306656,2,183},true
	case "armeabi-v7a": return nativeDescriptor{abi,"arm","133dc70f2ce0c02aef496bbb4c104f12d84d0937bc5ba24040ac2ad08a898d22",6864016,1,40},true
	case "x86_64": return nativeDescriptor{abi,"amd64","d8a8022c725194a44403ea6b21ec31b991146494a79c83906a39f72dd215285d",11049968,2,62},true
	default: return nativeDescriptor{},false
	}
}
