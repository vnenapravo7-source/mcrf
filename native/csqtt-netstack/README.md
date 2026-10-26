# CSQTT local IP adapter

Separate optional `csqtt-netstack.exe`: raw IPv4 over loopback UDP to a
loopback SOCKS5 TCP/UDP endpoint. No VK authentication, TURN/DTLS transport,
WireGuard protocol, OS VPN adapter, route, DNS or proxy modification.
Uses WireGuard's independently usable gVisor netstack package, not its VPN.
Parent stdin STOP/EOF cancels connections and releases sockets.
The raw loopback UDP socket requests4MiB receive and send buffers, shared by
all sessions of one adapter. Setup failures stop startup rather than silently
using a smaller buffer. This avoids dropping bursty TCP windows on Windows.
CSQTT explicitly passes `-mtu 1400 -csqtt-transport`. Its IP packets above
1280 use the existing CSQTT wire framing and reassembly, not IP fragments.
Legacy/QWDTT callers retain their previous 1280 ceiling and preserve smaller
server-provided MTUs. No OS interface or external TUN MTU changes.
Up to 512 concurrent SOCKS sessions are served; overload waits in the accept
queue rather than forcibly resetting clients. A capacity event is reported
without logging credentials or payloads.

`socks5_server.go` retains the WDTT-Plus GPL-3.0 SOCKS implementation.
The executable links only that file and this entry point, not the WDTT client.
Retain LICENSE and the Go module dependency licenses with distribution.

The CSQTT1400 UDP test additionally requires the exact pinned Rust protocol.
Compile `csqtt_wire_roundtrip.rs` with `MCRF_CSQTT_PROTOCOL_ROOT` pointing to
the pristine CSQTT source root; set `MCRF_CSQTT_WIRE_FIXTURE` to that executable
when running `go test`. It checks 1253/1272/1300/1372-byte UDP payloads through
the real SOCKS/IP adapter, no IP fragments, then actual CSQTT wire fragmentation
and exact reassembly in both directions, including reordered/duplicate frames.
Without the fixture only this additional protocol test is skipped.
