module mcrf.local/wireguard-fixtures

go 1.26.3

require github.com/amnezia-vpn/amneziawg-go v0.2.20-0.20260709022233-c1e9bb3758e7

require (
	github.com/sagernet/wireguard-go v0.0.0
	github.com/sagernet/sing v0.7.10 // indirect
)

// Host fixture only. The producer verifies this exact local upstream commit.
replace github.com/sagernet/wireguard-go => ../../../work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go

require (
	github.com/google/btree v1.1.3 // indirect
	golang.org/x/crypto v0.42.0 // indirect
	golang.org/x/net v0.44.0 // indirect
	golang.org/x/sys v0.36.0 // indirect
	golang.org/x/time v0.9.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	gvisor.dev/gvisor v0.0.0-20231202080848-1f7806d17489 // indirect
)
