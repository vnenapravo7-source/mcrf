# Original packet port — staged candidate

Not production enabled. This directory records the tested source overlay against
sing-box-lx `3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d`.

The `with_lx_raw_packet_port,with_lx_raw_packet` tags wrap only a SOCKS outbound
whose tag already has a validated same-session packet endpoint. Ordinary SOCKS
Dial/Listen remain on that same carrier. The native sing-tun ForwardDispatcher
owns ordered routing decisions, FakeIP/NAT rewriting, selectors and flow expiry.
The earlier custom pre-stack ingress is disabled in native port mode.

Raw selector slots are disjoint from the fallback gVisor stack (16000+); slots
are never reused during a session. Each backend rejects replies outside its
slot, including backends assigned identical internal IPv4. Congested writes are
bounded so dispatcher shutdown can reach endpoint retirement.

Executed on emulator-5556: native dispatcher UDP NAT/reverse FakeIP tuple,
payload preservation, ordinary fallback, ICMP/PMTU quoted tuple reverse NAT, bounded congestion/close, wrong-slot
replies and registry no-reuse; endpoint FD ownership tests also passed.

Still required before advertising or shipping: production Android SPI/JNI and
Go/Rust single-child dual-I/O adoption/ACK wiring; TCP sniff-policy admission;
fragment ownership; full actual TCP/UDP transport and teardown acceptance; all
ABI AAR/module rebuild and provenance. No speed claim follows from these tests.

`overlay.json` pins byte-for-byte source files under `source/`, their baseline
Git blobs where applicable, and actual test log hashes. Its state deliberately
remains `STAGED_NOT_PRODUCTION_INTEGRATED`.
