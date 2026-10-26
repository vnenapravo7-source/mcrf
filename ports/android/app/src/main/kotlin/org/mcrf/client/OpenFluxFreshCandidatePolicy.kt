package org.mcrf.client

/** Isolated acceptance only. Never an update preference, profile field or production fallback. */
internal object OpenFluxFreshCandidatePolicy {
    const val PACKAGE = "org.mcrf.engines.openflux.candidate"
    const val VERSION_NAME = "0.13.0-mcrf-candidate.13"
    const val REPOSITORY = "p1neappleXpress/OpenFlux"
    data class Descriptor(
        val packageName: String, val versionCode: Long, val versionName: String?, val enabled: Boolean,
        val sameSigners: Boolean, val singleApk: Boolean, val kind: String?, val repository: String?,
        val version: String?, val commit: String?, val overlay: String?, val interfaceVersion: Int,
        val carrier: Int, val verification: Int, val observation: Int, val scriptUpdater: Int, val diagnostic: Int,
    )
    data class Expected(val apkSha256: String, val nativeSha256: String, val nativeBytes: Long, val abi: String)
    private val sha = Regex("[0-9a-f]{64}")
    fun callerAllowed(packageName: String, versionCode: Long) = packageName == "org.mcrf.p3test" && versionCode >= 10056
    fun admits(value: Descriptor) = value.packageName == PACKAGE && value.versionCode == 13L && value.versionName == VERSION_NAME &&
        value.enabled && value.sameSigners && value.singleApk && value.kind == "openflux" && value.repository == REPOSITORY &&
        value.version == OpenFluxProfile.FRESH_VERSION && value.commit == OpenFluxProfile.FRESH_COMMIT && value.overlay == "" &&
        value.interfaceVersion == 1 && value.carrier >= 2 && value.verification == 1 && value.observation == 1 &&
        value.scriptUpdater == 1 && value.diagnostic == 0
    fun validExpected(value: Expected, supportedAbis: Set<String>, is64Bit: Boolean): Boolean =
        value.apkSha256.matches(sha) && value.nativeSha256.matches(sha) && value.nativeBytes in 1..(128L shl 20) &&
            value.abi in supportedAbis && (if (is64Bit) value.abi in setOf("arm64-v8a", "x86_64") else value.abi == "armeabi-v7a")
    fun matches(value: Expected, apk: String, native: String, bytes: Long) =
        value.apkSha256 == apk && value.nativeSha256 == native && value.nativeBytes == bytes
    fun elfMatches(header: ByteArray, abi: String): Boolean {
        if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() || header[5] != 1.toByte() ||
            header[6] != 1.toByte() || header[16] != 3.toByte() || header[17] != 0.toByte()) return false
        val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
        return when (abi) {
            "arm64-v8a" -> header[4] == 2.toByte() && machine == 183
            "armeabi-v7a" -> header[4] == 1.toByte() && machine == 40
            "x86_64" -> header[4] == 2.toByte() && machine == 62
            else -> false
        }
    }
}
