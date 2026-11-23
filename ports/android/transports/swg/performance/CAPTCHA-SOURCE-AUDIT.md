# Pinned CAPTCHA lifecycle evidence — source, not a test receipt

The historical CSQTT modules use the same id48 waiter instrumentation as the
Rebirth module. This is confirmed in their accepted source copies, not inferred
from a profile's generic `type: csqtt`:

- CSQTT 2.0: `2ff8a1a4fbe031fb76b799e172dce25cfe1ca942`.
- CSQTT 2.1.5: `de7afc23cec7481f98845bf24fc8943af19b4cf8`.
- `rust-client/captcha.rs` uses automatic 10s, manual 60s and selected 120s.
- `csqtt-waiters.patch` registers each ticket with that same timeout and uses
  cancellation-aware native waiting. The shared Go wrapper sets
  `MCRF_CAPTCHA_IDS=1`; historical manifests declare `csqtt_captcha_ids=1`.

The app therefore reserves one second for delivery: 9s/59s/119s for those three
Rust module kinds. An id48 scoped expiry is safe for the exact historical
waiter as well. Unmodified CSQTT 2.5 remains a separate implementation and
must not receive a guessed Rust waiter budget or an unscoped cancellation.
QWDTT tagged id32 and WDTT tagged worker IDs retain their own contracts.

The two new generation-budget JVM tests were corrected to match these pinned
sources before any execution. Their earlier source-only expectation of 150s
for historical modules was not an accepted test result. No provider calls,
live CAPTCHA attempts or limit workarounds were performed for this audit.
