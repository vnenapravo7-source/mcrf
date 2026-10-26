# OLC lifecycle wrapper — common verification pending

The protocol library remains the pinned original
`openlibrecommunity/olcrtc@f3ad8fb7c0d0fa981423f83269526fa94077c23d`, WTFPL-2.0.
Transport-option mapping derives from `ttrroyy/qThroneMobile` commit
`8463d3397b0a8522f92811f5c0698679ab8efc77`, `carriers/olcrtc/main.go`, GPL-3.0.
Both notices are retained. No prebuilt module or original checkout is modified.

Provider and transport choices, keys, tokens, channel/device identity, DNS,
VP8/SEI/video settings and the original 10s/15s/four-failure control liveness
configuration are preserved. No periodic app-side readiness poll is added.
The original SDK opens its authenticated SOCKS listener after bringing up the
remote link and already tracks/closes owned SOCKS connections on cancellation.

The MCRF frontend binds STOP, stdin EOF, SIGTERM and interruption to this owner
context. Shutdown has a two-second process-level backstop. READY is emitted
only from the SDK listener callback; it is not a claim of successful Internet
access. Imported profiles cannot override local credentials or choose device-ID
files, arbitrary listeners or logging paths. The strict private schema rejects
duplicates, nulls, unknown/case-aliased keys and malformed nested options.

Provider logs and arbitrary error bodies are not forwarded. Confirmed TLS,
DNS, timeout, refused/closed connection and typed protocol/rejection causes
remain distinct, as does an actual HTTP status. The fallback explicitly says
that a safe detailed cause is unavailable. Rooms, tokens and symmetric keys
are redacted from the app's bounded failure detail as well.

Source-only isolated preparation, host/native producers, Gradle external stage
and integration snapshot wiring are prepared for module version 5. Five new
adapter tests, two original SDK local shutdown tests (not previously accepted
MCRF checks) and two JVM tests are queued. None have run; native/APK/device
acceptance remains in the common P3/P4 verification batch. Existing modules
remain unchanged when no new external OLC stage is supplied.
