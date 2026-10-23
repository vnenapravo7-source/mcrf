# Windows core host

Build the pinned source with `./native/singbox-host/build.ps1`. It exports the
existing immutable upstream revision and required Go submodules into a separate
Windows-owned stage. Android/Linux sources are read-only inputs and are not edited.

Pass the produced `sing-box.exe` through `native/build.ps1 -CoreExecutable PATH`.
There is no fallback to the old console-based host. The Go binary uses the Windows
GUI subsystem and receives a private inherited stdin pipe. `STOP` or EOF follows
the normal `Box.Close()` path; no public control socket or console signal is used.

Build options preserve the original Windows feature tags and native dependencies.
Upstream core updates without private STOP support cannot replace this runtime.

The Windows stage also includes external Fake-IP recovery. A TUN connection to
198.18.0.0/15, or its IPv4-encoded fdfe:dcba:9876::/96 counterpart, with a sniffed
public domain is restored after the route decision, preserving explicit CIDR
conditions. The private-address matcher excludes only these synthetic TUN
destinations, so synthetic IPv6 no longer falls into the LAN bypass. All outputs
receive the recovered domain; explicit route/direct/block decisions, real IPs, genuine private IPv6
and explicit destination overrides remain intact. UDP replies use the existing
fake-IP NAT wrapper. No other client's DNS cache is read or changed.
`mcrf_fakeip_test.go` covers the guarded recovery and non-matching cases.

SOCKS outputs can explicitly declare `mcrf_ipv4_only`. Windows marks only CSQTT
local IP adapters. After explicit route selection, a public IPv6 destination with
a sniffed domain can use the adapter's IPv4 DNS resolution; the original IPv6
UDP reply address is restored. Generic SOCKS, other protocols, direct outputs,
private IPv6 and explicit destination overrides are unchanged. Selector/urltest
capabilities follow the actual selected member. `mcrf_ipv4_test.go` covers guards
and selected-group propagation. TUN DNS capture and reverse mapping let external
synthetic addresses retain their authoritative DNS name rather than an ECH cover
name, without rewriting the destination before CIDR routing.
