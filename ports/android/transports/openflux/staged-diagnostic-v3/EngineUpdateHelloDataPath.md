# b094: accepted hello to HTTP data path

Actual main receipt: integration-b094-device/openflux-provider-stage-v3.log.
One approved unchanged 14s profile attempt, HTTPS false; final37Boolean stage,
normal exit/EOF/reader completion/cleanup/data guard accepted. Decrypt success and
hello acceptance were observed; establishment and IPv4/TCP/HTTP success were not
measured. Private10 removal is main-owned, ordinary7 unchanged.

## Proven source boundaries

1. `session.go:1295-1385` accepts an initial hello with Peer zero, stores sender,
   and increments cntHelloAccept. Only Peer equal to this Session's local
   challenge sets ready=true and negotiated remote parameters. Thus hello_ok is
   not ready/association evidence. The new source test explicitly proves both.
2. `session.go:777-816,961-965` IsConnected can return true from ClassicFallback
   raw-carrier readiness. PeerParameters ready is the narrower Session state.
   Mobile ProxyIsConnected and wrapper MCRF_READY retain that broader meaning.
3. `session.go:1032-1082` sends classic while not ready, otherwise Session IPv4
   with its local/peer association and sequence through a live link. On receipt,
   `1468-1510` requires ready/not stopped, correct local/peer association, permitted
   IPv4 capabilities/size and fresh sequence, then calls the data receiver if set.
4. `mobile/proxy.go:60-115` starts transport, creates TCPTunnel and binds authenticated
   SOCKS. `tunnel/tunnel.go:125-182` installs the Session receive callback into
   the gVisor link endpoint; its outgoing packets call Session.Send.
5. SOCKS validates auth/request and calls TCPTunnel.DialTCP. `tunnel.go:324-367`
   resolves IPv4 locally and establishes a virtual TCP connection over Session.
   `socks5/socks5.go:237-249` sends success after that DialTCP returns.
6. Exit gVisor receives Session IPv4, then `tunnel.go:261-298` creates a TCP
   forwarder endpoint and calls Complete(false) BEFORE asynchronously dialing
   the destination OS TCP socket. Only after that socket connects does io.Copy
   bridge data. Destination DNS/routing/connect and TLS/HTTP are still separate.

Correction to the earlier source proposal: SOCKS success after DialTCP is not
proof of actual destination TCP connect for this TCPTunnel backend. Virtual
stack handshake can succeed before exit net.DialTimeout completes. Preserve this
distinction; no received success would close the HTTPS gate.

## Actual-component host proof

`EngineUpdateHelloReady_test.go` proves initial hello acceptance with ready false,
data rejection before ready, echo-associated ready, wrong-association rejection,
valid data delivery and replay rejection using real Session methods/counters.

`EngineUpdateMobilePath_test.go` runs real startExitWith/startProxyWith, two real
classic-configured Sessions that establish Session mode, real AES/batch/envelopes,
client/exit gVisor TCP, local authenticated SOCKS and an HTTP request/response.
The carrier is asynchronous in-memory transport. Only the exit OS TCP boundary
is a clearly isolated HOST FIXTURE adapter returning net.Pipe for exactly one
synthetic 198.18.0.1:80 address, denying every other dial. No network request to
that address occurs. The HTTP responder is in-memory and the SOCKS listener is
loopback. TLS, external DNS, external destination dial and provider behavior are
not proved. This adapter is not a proposed runtime fix or part of any native patch.

The initial loopback-destination fixture failed because gVisor correctly drops
martian loopback packets arriving through a tunnel NIC. Its log is preserved;
no production allowance/routing change was made. The net.Pipe fixture then passed.
The fixture explicitly closes the client TCPTunnel: current mobile StopProxy
clears its tun pointer but does not call tun.Close. That is a separate source
lifecycle omission for in-process reuse, not a demonstrated HTTPS cause; native
one-shot process exit reclaims it. No lifecycle fix has been applied.

## Remaining uncertainty and bounded next step

No real defect causing the approved HTTPS failure is established. At least one
Session record decrypted and its hello passed normal acceptance; this does not
identify the participant or prove challenge completion/data plumbing. We cannot
infer incorrect keys, broken server, protocol mismatch, successful OS egress,
or successful HTTPS from this observation.

Before another native attempt, main may review whether source-only observation
of challenge-ready and accepted/rejected Session IPv4 plus mobile data callback
installation would resolve the next boundary. Actual destination OS dial and
TLS are further boundaries. V3's all-false line already consumes1016 bytes:
additional fields would require a new explicitly bounded schema selecting fewer
old fields, not silent expansion. No V4/native/provider retry or protocol/config/
timer change is proposed for activation here. The host tests demonstrate that
the unmodified protocol/data path works with compatible local components.
