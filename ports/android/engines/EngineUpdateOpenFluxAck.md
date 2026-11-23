# Reviewed Mailru ACK module v7

Coordinator-authorized packaging only:

`-PopenfluxAckStage=ABSOLUTE_FROZEN_VERIFIED_NATIVE -PopenfluxAckGate=true`

The input is the reviewed `verified-native` directory containing
`mailru-ack-candidate.json` and the three ABI directories. Configuration verifies
the exact upstream v0.7.3 commit, ACK overlay identity and all three overlay hashes,
module version 7, private CLI, disabled script updater, and the exact native hashes
and sizes. ACK, original v6 promotion and script candidate stages are mutually
exclusive. Production JNI and transport-lock remain separate inputs.

Output identity: `org.mcrf.engines.openflux`, versionCode 7,
versionName `0.7.3-mcrf.7`, source.version `v0.7.3`, source.overlay
`mcrf-mailru-ack.1`, source.commit
`f12e981629a18c8c01007d59ac002254c02972bb`, script-updater 0.
Only libopenflux.so is staged; no test-server fixtures or script staging.

Main must freeze these sources and reviewed stage, run the shared Gradle build,
verify all three ABI APKs plus universal, exact metadata/payload/signature and
installed receipt, then perform the separately approved single HTTPS check.
Local Mailru regression/CLI success does not establish provider traffic success.
