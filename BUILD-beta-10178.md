# Android beta 2.0.0-beta.10178 source

This bundle captures the Android Main inputs and matching app resources for the universal beta APK. Protocol engine packages have separate source provenance. The source manifest records each included input. A clean replay is required to claim an exact source-to-APK match.

## Build inputs

- Install JDK 17, Android SDK platform 37.0 and the Gradle 9.1.0 wrapper requirements. The project pins AGP and dependency versions in its build files.
- Obtain the exact compile-only libbox AAR from a verified source. See `ports/android/android-core-lock.json` for the source revision, upstream submodules, toolchain and digest. The AAR is external to this archive. The bundled native-core recipe documents its origin and assumes an upstream checkout and toolchain configured per the lock.
- Obtain either the exact beta universal APK or the three exact router libraries from a release artifact you can access. `beta-build-inputs.json` records the hashes and lengths. Router binaries remain external to this archive.
- From the archive root run `python prepare-beta-build-inputs.py --reference-apk /path/to/verified.apk --libbox-aar /path/to/libbox.aar --build-inputs /new/path`. You may use `--router-directory /path/to/router-artifacts` in place of `--reference-apk`. The helper checks all bytes, creates a local router receipt for the extracted location and installs the compile-only dependency under the source tree.
- From `ports/android`, run the Gradle wrapper with `:app:assembleRelease -PmobileBetaBuild=true` and both properties printed by the helper. Apply the authorized signing configuration separately. A different certificate changes the signed APK identity. No signing key or credentials are included.
- Compare the output to the pinned reference SHA-256. When rebuilding with another signing configuration, compare every unsigned ZIP entry by path and bytes, and report metadata or signing differences separately.
