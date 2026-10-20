module org.mcrf/wgbackend

go 1.26.0

require (
	github.com/sagernet/gomobile v0.1.13
	github.com/sagernet/wireguard-go v0.0.0
	golang.org/x/sys v0.35.0
)

require (
	github.com/sagernet/sing v0.7.10 // indirect
	golang.org/x/crypto v0.41.0 // indirect
	golang.org/x/mod v0.27.0 // indirect
	golang.org/x/net v0.43.0 // indirect
	golang.org/x/sync v0.16.0 // indirect
	golang.org/x/tools v0.36.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
)

// Build entry point verifies the exact source commit before using this local dependency.
replace github.com/sagernet/wireguard-go => D:\MCRF\Projects\MCRF\work\android-upstream\sing-box-lx-mcrf-clash-20261014\submodules\wireguard-go
