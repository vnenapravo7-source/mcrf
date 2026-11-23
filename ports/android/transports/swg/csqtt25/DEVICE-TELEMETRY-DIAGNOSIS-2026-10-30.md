# CSQTT SWG 2.5.2/2.5.3: source diagnosis

Scope: source and narrow host tests only. No device, VPS, native/APK/Gradle build or publication.

## Zero streams: proven cause and fix

Pinned upstream revisions: 2.5.2 `1f51f962288d6e2286790e0770847a800745046e`,
2.5.3 `0188606a243c92a54440be09dc59a93848c463aa`.
The relevant Rust files are identical between these revisions.

- `rust-client/events.rs:84` emits `__CSQTT_EVENT__|STATS|{"active":N,"bytes_up":U,"bytes_down":D}`.
- `stats.rs:13,39-45` samples real `active_connections` every 500 ms on change,
  with an unchanged heartbeat every 3 seconds.
- `session.rs:829-840,1143-1152` counts registered data workers through RAII;
  READY IDs, configured workers and byte rates are not substitutes.
- Frozen source `p3-csqtt25-source-de108a9d3f6d4ba698fe47959c589197/module`
  has the retained reporter selecting STREAMS unless `McrfSampleStreamStats` is true.
  `Prepare-P3AbiSource.ps1` replaced `mcrf_csqtt25.go` from the canonical adapter,
  losing that performance-overlay flag. New clients emit STATS, so no count reached
  the reporter. This also explains why the separate legacy fix did not repair this wrapper.

Fix: restore the sampled flag when preparing the replacement bridge, before source
freeze. Require one exact anchor and no previous flag. The provenance verifier
checks the exact transformed bytes, so refreezing a bridge without the flag fails.
Runtime command, event environment, native identities and transport behavior stay unchanged.

## Upload: explicit limits, not a proven throughput repair

Pinned Rust source:

| Source | Explicit client limit |
| --- | --- |
| `turn.rs:39-52` | UDP receive/send requests 32/16 KiB; incoming queue 16; outbound queue 8 packets |
| `dispatcher.rs:38-47` | Per-worker token rate 247,000 B/s; adaptive minimum 150,000 B/s; bucket 10,500 bytes |
| `dispatcher.rs:49-60` | Flow burst 16 KiB; small UDP sticky 1 s; bulk pin detection 250 ms, bulk hold 2 s |
| `dispatcher.rs:496-510` | Inbound queue clamps workers×16 to 256–1024 packets; outbound capacity 32 |
| `session.rs:49` | Writer command capacity 256 |
| `main.rs:75-76,206-217` | At most 100 streams; Tokio defaults to ceil(requested/8), bounded by available CPUs |

The per-worker maximum is about 1.976 Mbit/s before overhead, not a total tunnel
cap. Dispatcher distributes flows across workers and awaits backpressure when
fast send cannot enqueue (`dispatcher.rs:1349-1384`). Changing queue sizes or
runtime threads without queue-pressure/CPU/actual worker observations cannot
establish a repair for measured 1.34 Mbit/s or the separate legacy 5.3 Mbit/s.
Legacy Go requests 625 KiB per socket (`session.go:28,145-146` in the retained
legacy source); it is not governed by these Rust constants. No performance
constants, workers, server policy or advertised throughput were changed.

## Verification

- Generated bridge Go regression: actual profile parsing preserves sampled STATS
  for default, 2.5.1, 2.5.2 and 2.5.3 implementations.
- Reporter Go tests: sampled counts, zero, malformed input, privacy, exact-event
  precedence, child replacement and concurrent owner retirement.
- Python provenance tests: exact replacement bytes and rejection of lost flag
  even after refreeze; actual PowerShell transform compared with verifier bytes.
- Source wiring tests and PowerShell parser check.

The source fix has not been built or run on Android. Device stream display and
throughput remain unverified for the corrected wrapper.
