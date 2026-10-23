# CSQTT local IP adapter

Separate optional `csqtt-netstack.exe`: raw IPv4 over loopback UDP to a
loopback SOCKS5 TCP/UDP endpoint. No VK authentication, TURN/DTLS transport,
WireGuard protocol, OS VPN adapter, route, DNS or proxy modification.
Uses WireGuard's independently usable gVisor netstack package, not its VPN.
Parent stdin STOP/EOF cancels connections and releases sockets.
Up to 512 concurrent SOCKS sessions are served; overload waits in the accept
queue rather than forcibly resetting clients. A capacity event is reported
without logging credentials or payloads.

`socks5_server.go` retains the WDTT-Plus GPL-3.0 SOCKS implementation.
The executable links only that file and this entry point, not the WDTT client.
Retain LICENSE and the Go module dependency licenses with distribution.
