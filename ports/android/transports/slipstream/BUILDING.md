# Slipstream native pipeline — prepared source, not a build receipt

No script in this pipeline has been invoked for the current P3/P4 package.
Compilation and fixture execution remain blocked by the common batch gate.
Existing accepted modules and phone settings are not changed by preparation.

The intended sequence inside common verification is:

1. `Prepare-Source.ps1`: private-UDS/mandatory-leaf-pin Rust client and Go wrapper.
2. `Prepare-BuildInputs.ps1`: pinned C/TLS source, local GNU make and runtime
   packages, without package installation or execution.
3. `Prepare-CFixtureInputs.ps1`: original C server and its exact Git-link
   dependencies, plus a fixture-only loopback bind shim.
4. `Build-CDependencies.ps1`: three-ABI static OpenSSL/picotls/picoquic outputs,
   generated headers and tool fingerprints, in fresh owned directories.
5. `Build-Native.ps1`: three-ABI core/wrapper, plus x86_64 Rust server and Go
   fixture executable. Locked Rust dependencies are fetched once; builds are
   locked/offline. Compiler-artifact package IDs drive legal inventory.
6. `Build-CFixture.ps1`: the original C server against its original picoquic,
   reusing the pinned TLS libraries. The wire/TLS/server implementation is not
   replaced with Rust code. Only wildcard binds are clamped to loopback by a
   fixture-only linker wrapper. This binary is not shipped in the engine APK.
7. `Complete-NativeNotices.ps1`: notices from actual built Rust packages, Go,
   original picoquic/picotls/OpenSSL and embedded C dependencies. Missing
   notices block promotion; they are not replaced by a licence-name string.
8. `Invoke-OwnedFixture.ps1`: eleven new emulator-only cases, including real
   QUIC-over-DNS, wrong-leaf rejection, RFC1929, half-close/cancellation,
   parent death/EOF, and interoperability with the original C server.

Only the last successful step may produce
`COMPILED_OWNED_FIXTURE_VERIFIED`. Native compilation by itself is not accepted
by Gradle. The fixture confirms x86_64 execution, not phone/armv7 acceptance.
The shared phone verification remains a separate required step. Source hashes,
native bytes and legal/fixture evidence are frozen into the integration snapshot.

The emulator helper uses a new short private temporary directory, fixed local
test data and no external resolver or account. It changes no VPN/UI setting.
Peers are tied to the test process with Linux parent-death policy on a locked
owner thread. Successful cleanup removes only the five pushed helper binaries
and uses non-recursive `rmdir`; failed runs preserve the exact owned directory.
Cleanup alone must never cause successful cases to be replayed.

Windows tools are portable files extracted to the owned input stage, not a
system MSYS2/WSL installation. GNU make and its required runtime archives are
pinned to the hashes published by the [MSYS2 package catalogue](https://packages.msys2.org/packages/mingw-w64-x86_64-make).
The OpenSSL source checksum is the one in the pinned Rust `Cargo.lock`.
Automatic picoquic/picotls CMake fetching is disabled.
