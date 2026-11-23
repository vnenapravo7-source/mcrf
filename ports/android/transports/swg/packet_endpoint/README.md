# Native packet endpoint: standalone boundary, NOT production integration

This new crate is not copied by the current CSQTT producer, linked by CSQTT,
or advertised by BS. Existing real TUN-FD v1 guard/CLI/behavior are untouched.
Do not rebuild BS merely because this standalone library compiles.

Plan/owners:

1. BS7 source/receipt snapshot preserved before new work (native author).
2. Typed raw packet contract and local FD boundary (native author, this crate).
3. Actual control/FD platform bridge and router policy/session integration
   (agreed with Anscombe; remains pending, no FD encoded in profile text).
4. Independent review, standalone host contract tests and Android compile.
5. Only once actual router integration is source-ready: CSQTT entry/I/O adapter,
   capability/native packaging proof and separately authorized end-to-end gates.

The intended owner creates one connected AF_UNIX/SOCK_SEQPACKET pair per child,
retains the parent endpoint for the chosen raw outbound, and sends the other via
authenticated Unix SCM_RIGHTS. Distinct capability/key is
`org.mcrf.engines.csqtt_packet_endpoint_fd` v1, input kind `unix-seqpacket-ip-v1`.
These constants do NOT imply the installed BS supports this input kind.

Contract has an exact32-lowerhex endpoint ID, positive signed63-bit generation,
expected parent UID/PID and negotiated IPv4 packet MTU20..1984. Endpoint registry
must bind ID+generation to the kernel device/inode returned after real FD adoption;
strings alone do not prove independent endpoints. Control receive is nonblocking,
authenticated same-owner Unix stream, exactly one FD and proposed control byte
0x50 (not real-TUN byte1). Receipt is NOT I/O adoption; this crate sends no early
ACK/READY. Final CLI/markers and adoption ACK byte0x50 are pending owner agreement.

Data channel is one complete raw IPv4 packet/message, no envelope or TCP/SOCKS
termination. MSG_TRUNC/CTRUNC and invalid lengths/fragments are rejected. Frames
are not rewritten; native real TUN IO and original CSQTT dispatch/crypto/fragment
processing remain unchanged. Caller must treat framing/peer death as endpoint
failure, preserve a packet on WouldBlock, never fallback/migrate a pinned flow.
No active60s timer exists in this crate; lifecycle/one-shot handoff remains an
integration responsibility. Retained original native internal routing must stay
disabled for this input kind so existing maincore policy remains authoritative.

Maincore may divert only if backend source address/family and MTU accept the
original packet. No SNAT is added here. Return flow ownership is backend+endpoint
ID+generation+reverse tuple; the router stores original UID and rejects unbound
or stale replies. All unsupported/incomplete policy decisions stay in maincore
BEFORE first diversion; already-owned flows never switch midstream.

Standalone checks (existing offline toolchain/cache, no BS/Gradle/phone):

```powershell
& 'C:/Users/dieonfire/AppData/Local/Programs/Python/Python314/python.exe' -X utf8 -B 'D:/MCRF/Builds/Android/csqtt-packet-endpoint-staged-20261103-01/run-standalone.py' --target host --output 'D:/MCRF/Builds/Android/csqtt-packet-endpoint-host-20261103-01'
& 'C:/Users/dieonfire/AppData/Local/Programs/Python/Python314/python.exe' -X utf8 -B 'D:/MCRF/Builds/Android/csqtt-packet-endpoint-staged-20261103-01/run-standalone.py' --target android --output 'D:/MCRF/Builds/Android/csqtt-packet-endpoint-android-20261103-01'
```

Host executes six platform-neutral contract tests, NOT Unix FD tests. Android
command only crosscompiles18 cases with --no-run, no ADB/execution. Outputs must
be fresh, logs/source/hash receipt retained, Cargo frozen+offline. Actual Unix
execution needs a separately approved Main/device or available Linux runner.
The twelve Unix cases cover actual FD/SCM ownership, extra/truncated/incorrect FD,
peer/type/listener rejection, raw message preservation, bounded atomic
backpressure and independent backend endpoints. This is not provider throughput.
