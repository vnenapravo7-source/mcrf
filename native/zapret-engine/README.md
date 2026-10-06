# MCRF process-aware Zapret

Upstream: bol-van/zapret, commit `d437963452674faadfd45adcd62466272b5a2fcd`.
Archive: https://codeload.github.com/bol-van/zapret/zip/d437963452674faadfd45adcd62466272b5a2fcd
SHA-256: `bf2efd098999a547d97288c00205bbc30a45418b27a447d4dc7729fb857ce5bf`.

The MIT-licensed `app_filter.c` extension is applied to each DPI profile, including
cached TCP/UDP profiles and replay paths. Upstream Zapret remains under its MIT
license; the existing WinDivert distribution and its LGPL/GPL notices are retained.

`--mcrf-apps=<UTF8 file>` includes executable names/full paths.
`--mcrf-except-apps=<UTF8 file>` excludes those applications, but **never** treats
an unknown process as an allowed process. Lines are `app:browser.exe` or
`path:C:\Apps\Browser.exe`. Conditions remain separate for every routing list.
Normal rules use TCP 80/443 and UDP 443. Independent opt-in Game TCP and Game UDP
checkboxes add the selected Flowseal strategy's respective game profiles on ports
1024-65535. Legacy Game Filter=true remains TCP+UDP unless explicitly overridden.
Every game profile
retains the routing list's IP/application constraints; domain-only game scopes
are rejected. Game Filter is off by default and saved per routing list.
Address-only lists continue using the upstream binary unchanged.

Process recording without VPN uses separate FLOW/SOCKET SNIFF|RECV_ONLY handles
through GameFlowObserver.cs. It collects IPs of newly observed TCP/UDP connections
for the selected executable, checks process birth against event time, and skips
inaccessible owners. It neither intercepts nor reinjects packets, starts TUN, nor
changes the existing Zapret profiles. Administrator rights are required. It does
not infer domains from DNS or reconstruct already established connections.

FLOW/SOCKET observers associate full IPv4/IPv6 5-tuples with endpoint IDs.
The observer retains the live process handle, checks its birth against the event
timestamp, and compares its executable using Unicode ordinal case-insensitive
comparison. Missing/ambiguous owners, inaccessible processes, stale events,
observer failure, loopback/impostor packets, and an exhausted 8192-flow limit
skip application-scoped processing. There is no port-only/PID-only fallback,
packet wait, firewall rule, registry mutation, or separate blocking service.
Do not claim perfect per-app isolation without live-driver tests. Existing
connections are not reconstructed; restart the selected applications' connections
after enabling Zapret. Early packets may pass unchanged while events are pending.

Build with `build.ps1`: LLVM-MinGW clang cross-targeting Cygwin, official Cygwin
3.6.11-1 SDK/import libraries, w32api 14.0.0-1, statically linked zlib 1.3.2.
SDK packages were checked against SHA-512 from the official Cygwin setup index.
The matching unmodified `cygwin1.dll` is bundled, not the old Flowseal runtime.
Cygwin source: https://mirrors.kernel.org/sourceware/cygwin/src/release/cygwin/cygwin-3.6.11-1-src.tar.xz
Cygwin licensing: https://cygwin.com/licensing.html
zlib source: https://mirrors.kernel.org/sourceware/cygwin/x86_64/release/zlib/zlib-1.3.2-1-src.tar.zst
Retain corresponding sources and licenses when distributing a public release.
The upstream archive + patch + extension are sufficient to reproduce the modified
Zapret source; no modified system driver is built or distributed.

Checks: `work/AppFilterCheck.c` exercises mock events without loading a driver;
`work/ZapretAppsCheck.cs` checks rule semantics, independent list scopes, bundled
PE/runtime, and executable help. HTTPS service scans intentionally use address
scopes: they do not certify application selection, video playback, or voice calls.
