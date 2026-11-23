# Common P1 original-socket owner fixture

Source only, not compiled/run. Optional `-Pp1OwnerFixture=true` module, DEBUG/testOnly;
requires a SHA-bound frozen whole-P1 `readyForBuild=true` receipt. No release variant.
Build/sign alongside the next common immutable P1 snapshot, not as an extra APK.

`ClientP1SocketOwnerChecks.run` borrows an already-owned DEBUG main fixture VPN;
the coordinator must supply its live Network and exact helper APK SHA plus fresh
protected-state baselines. Four real different-UID loopback TCP/UDP4/6 cases compare
observed peer tuples, actual platform UID and installed package. No VPN manipulation,
profiles, runtime grants, Internet peers or native inclusion. Signature permission
exists only in main DEBUG manifest. Helper permits only loopback and one25s session.

This proves only the platform lookup prerequisite, NOT Steer JNI app routing,
shared-UID unions, system-TUN egress or all helper-process death. Closed-session
responses are IO cleanup, never a process-death receipt.

Source-wired instrumentation dispatch: `mode=p1-original-owner`, `fixture=<exact
helper APK SHA256>`. It reuses the existing exclusive saved-state/owned-VPN
lifetime only, runs these four new checks, and observes exact worker/TUN/service
cleanup. It does NOT dispatch closed Direct/HTTP/restart/notification suites.
Common build source: `integration-build.ps1 -P1OwnerFixture` requires ALL three
debug app/runner/helper tasks, active whole-P1 readiness, frozen receipt/control
inputs and SHA-bound Gradle gate. Pre/post inputs AND manifest are checked; the
three APK hashes/bytes are recorded in `p1-owner-artifacts.json` and archived by
retention. This script has NOT been executed or parser-tested. Device coordinator
must still supply fresh lease/permissions/baselines and installed helper pin.
There is no ad-hoc helper launcher or reused one-off APK permission.
Real Steer/DNS/native gates remain open. No execution has taken place.
