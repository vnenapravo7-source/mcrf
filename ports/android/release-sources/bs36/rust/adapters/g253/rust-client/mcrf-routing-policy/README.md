# BS routing policy — source-only foundation

This crate is **not wired into the released BS executable or Main**. It does not
establish a VPN, resolve DNS, inspect traffic, or replace the current connection
path. Passing its tests is not evidence of single-engine operation or upload parity.

The intended data path is Android TUN → the selected BS transport. Tunnel-bound
packets must retain the application's original TCP/IP conversation; matching a
rule must not create a second TCP connection or require sing-box. Direct traffic
can use the BS transport's native direct relay. Decisions belong to a bounded
per-flow session cache, not repeated allocation-heavy parsing per packet.

The current matcher implements ordered proxy/direct/block decisions, logical
AND/OR, inversion, domain conditions, IP prefixes, ports and package ownership.
Duplicate JSON fields, unknown selectors and unexpanded geo rule sets are errors.
A failed owner lookup remains an error through inversion and cannot select a
direct fallback. Neither errors nor policy objects expose profile data in Debug.

Default rules retain the selected primary's address/port grouping: destination
domain/exact/suffix/keyword/regex and destination CIDRs are alternatives within
one group; exact and range destination ports form another, source ports another.
Those groups and ordinary selectors are conjunctive. This was compared with
`route/rule/rule_abstract.go` in the pinned local sing-box-lx source, rather than
assuming that all fields are an AND. Four regressions cover alternatives,
between-group conjunction and whole-rule inversion. All 14 cases have run on
Windows and the physical Samsung Android ARM64 kernel. This remains matcher
logic evidence, not accepted packet routing.

Domain/protocol observations distinguish a pending inspection from an inspected
flow with no domain/protocol. An unresolved blocking or inverted condition
returns a metadata error, not a direct fallback. A proven independent false AND
condition or true OR alternative can resolve the rule without waiting. Three
additional regressions cover these cases (17 Windows and 17 Samsung cases now).

Remaining integration work:

- Compile the exact selected MCRF routing snapshot, including private profile
  GeoIP/GeoSite data, DNS policy, network conditions and application priorities.
  Preserve first-match semantics and reject every unsupported condition.
- Connect the native packet/TUN ingress for CSQTT 2.5.1/2.5.2/2.5.3. Preserve
  authenticated descriptor ownership, cancellation, retained TUN and bounded
  shutdown. A metadata flag alone does not grant native capability.
- Implement bounded flow ownership/domain metadata and managed DNS without
  silently bypassing VPN DNS or substituting standard geofiles.
- Integrate normal selection, auto selection, profile changes and network
  recovery without falling back to serial sing-box + BS processing.
- Run real Android packet/DNS/route/drop/leak/lifecycle tests. The user performs
  final CSQTT provider throughput comparison.

## Captured source rule sets and domain parity (2026-11-09)

The policy document can now contain origin-bound `ruleSets` source JSON snapshots
(version1..3). It opens no file or URL and never substitutes a standard dataset
for a missing private set. Exactly one noninverted default headless rule merges
its four address/port groups with the outer rule, matching the pinned primary.
Multi-row, logical and inverted sets keep separate outer conditions. Unknown
conditions, missing sets, nested/cyclic dependencies and expansion over the
shared16MiB/100000-node budget are rejected. Empty sets remain unadmitted.

Domain exact/suffix lookup is indexed, with no per-pattern normalization on a
new flow. Leading dots, terminal dots and keyword punctuation retain the
original matcher semantics.168 ASCII/A-label reference cases were generated
from the pinned original dependency; a50000-entry regression checks both exact
and suffix lookup.49 top-level cases, including those168 comparisons, passed
on Windows and the actual SamsungARM64 and emulatorx64 kernels. These are
source-only policy proofs, not complete runtime/DNS or speed acceptance.

Native test integration additionally combines a captured private GeoIP set with
the original direct relay and an owned UDP peer, while another port is blocked.
That newly added combined test still requires a fresh original-core build/run.
The complete native backend remains unactivated; managed DNS, ownership,
private snapshot capture, fragments, generations and lifecycle work remain.
