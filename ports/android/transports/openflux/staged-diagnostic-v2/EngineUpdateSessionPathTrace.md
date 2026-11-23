# 9786: Session dispatch with a classic profile

Actual main receipt: integration-9786-device/openflux-provider-stage-v2.log.
One approved unchanged stored profile, 14s, HTTPS false. Final26Boolean stage,
normal exit/EOF/thread completion/cleanup ACK and unchanged data accepted.
Private9 removed after idle/hash guard; ordinary7 unchanged.

`demux_session=true` proves a nonempty decoded payload beginning with the single
byte `O` reached the existing Session classifier. It does not prove a full OFX
record, authenticated peer, successful decryption, valid Session envelope,
accepted hello or established Session. Classic flags false are unobserved, not
negations; aggregate events may concern different packets/reconnects.

`negotiate=false` does not disable Session in this implementation:

- Owned `mcrf_proxy.go:27-28` builds a Session with classic=true, strict=false.
- `mobile/session.go:108-123` always creates Session and adds classic capability
  alongside it. `transport/session.go:181-208` creates both layerings: Session
  AES/batch plus classic codec/AES using shared key material.
- `session.go:417-439` starts helloLoop for a client even with ClassicFallback;
  it can return in classic mode after waiting, while hellos continue. This is
  explicit compatibility/upgrade behavior, not evidence of a flag regression.
- `framedemux.go:37-52` routes O-prefixed payload to Session irrespective of
  ClassicFallback. Disabling that route would break supported upgrade behavior.

Then the real Session receive path is:

`link.encrypted.Receive -> minimum length/magic/version/direction guards ->
r.open -> replay guard -> link.batched.Receive/decodeBatch -> control.Decode ->
envelope opposite-role check -> receiveHello/IPv4/control`.

Source locations: `encrypted.go:482-539`, `batched.go:152-185`,
`session.go:529-540,1253-1285,1295 onward`. Hello also validates tail, nonzero
sender, capabilities/packet limit and local/peer association before establishment.
Current diagnostic crypto flags intentionally measure classicEnc only; Session
crypto/batch/envelope/hello outcomes are not exposed in V2.

Offline actual-chain proof, with no runtime-source modifications:
`EngineUpdateSessionPath_test.go` exercises real newLink/startLink, demux,
Session AES, BatchedTransport and Session.receive. The same observed public
Session-only/classic-unobserved pattern occurs in seven different cases:
short O-prefix rejection; same crypto-role direction rejection; synthetic wrong-key
AEAD failure; decrypted content failing batch framing; decoded batch failing
envelope parsing; authenticated envelope with same role; and a valid opposite-role
hello that reaches receiveHello. Tests assert real existing private counters,
never output the keys, data or header values. No provider identity is inferred.

Additionally the unchanged upstream TestSessionClassicCompat passes all five
cases, including classic fallback against an old exit and a classic-configured
profile upgrading to Session with a compatible peer. These synthetic in-memory
transports prove source behavior, not the actual approved server's behavior.

Conclusion: routing to Session is expected source behavior and no protocol defect
has been demonstrated. This observation narrows further investigation to the
unmeasured Session receive path; it does not prove that path dropped the actual
packet. No role/key/server mismatch or handshake failure is established.

Minimal next proposal for main review, if more diagnosis is authorized: add only
fixed Session-specific Boolean observations at header/direction rejection,
AEAD failure/success, batch decode failure, envelope decode/same-role rejection,
hello acceptance/rejection and establishment. Keep a new exact versioned schema,
do not broaden V2, and keep the existing one-run process/STOP/privacy guards.
No production protocol/config/timing fix is proposed. The current proof already
supplies synthetic cases for these branches; additional source instrumentation,
native/module build and provider retry require separate main authorization.
