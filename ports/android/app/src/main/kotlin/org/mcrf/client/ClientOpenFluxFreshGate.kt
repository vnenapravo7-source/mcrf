package org.mcrf.client

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.security.MessageDigest

/** Explicit source-bound test candidate admission. Does not touch the dedicated production package.
 * Expected hashes must come from the final common coordinator artifact receipt, not invented here. */
internal object ClientOpenFluxFreshGate {
    class Verified internal constructor(val application: ApplicationInfo, val executable: File)
    fun requireCaller(context: Context) {
        val own = context.packageManager.getPackageInfo(context.packageName, 0)
        require(OpenFluxFreshCandidatePolicy.callerAllowed(context.packageName, own.longVersionCode) &&
            own.applicationInfo?.uid == Process.myUid() && Application.getProcessName() == context.applicationInfo.processName) {
            "Fresh OpenFlux candidate is restricted to the isolated review main process."
        }
    }
    fun verify(context: Context, expected: OpenFluxFreshCandidatePolicy.Expected): Verified {
        requireCaller(context)
        require(OpenFluxFreshCandidatePolicy.validExpected(expected, Build.SUPPORTED_ABIS.toSet(), Process.is64Bit()))
        val pm = context.packageManager
        val info = pm.getPackageInfo(OpenFluxFreshCandidatePolicy.PACKAGE,
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES)
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(value: PackageInfo) = value.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val app = requireNotNull(info.applicationInfo); val metadata = requireNotNull(app.metaData)
        require(OpenFluxFreshCandidatePolicy.admits(OpenFluxFreshCandidatePolicy.Descriptor(
            app.packageName, info.longVersionCode, info.versionName, app.enabled,
            signers(info).isNotEmpty() && signers(info) == signers(own),
            app.splitSourceDirs.isNullOrEmpty() && info.splitNames.isNullOrEmpty(),
            metadata.getString("org.mcrf.engines.kind"), metadata.getString("org.mcrf.engines.source.repository"),
            metadata.getString("org.mcrf.engines.source.version"), metadata.getString("org.mcrf.engines.source.commit"),
            metadata.getString("org.mcrf.engines.source.overlay"), metadata.getInt("org.mcrf.engines.interface"),
            metadata.getInt("org.mcrf.engines.openflux"), metadata.getInt("org.mcrf.engines.openflux.verification"),
            metadata.getInt("org.mcrf.engines.openflux.observation"), metadata.getInt("org.mcrf.engines.script-updater"),
            metadata.getInt("org.mcrf.engines.provider-diagnostic")))) { "Fresh OpenFlux candidate identity/capabilities do not match." }
        val root = File(requireNotNull(app.nativeLibraryDir)).canonicalFile
        val binary = File(root, "libopenflux.so").canonicalFile
        val apk = File(app.sourceDir).canonicalFile
        require(binary.parentFile == root && binary.isFile && binary.canExecute() && binary.length() == expected.nativeBytes &&
            apk.isFile && apk.length() in 1..(256L shl 20))
        val header = ByteArray(20)
        binary.inputStream().use { input ->
            var position = 0
            while (position < header.size) { val count = input.read(header, position, header.size - position); require(count > 0); position += count }
        }
        require(OpenFluxFreshCandidatePolicy.elfMatches(header, expected.abi))
        require(OpenFluxFreshCandidatePolicy.matches(expected, hash(apk), hash(binary), binary.length())) {
            "Fresh OpenFlux APK/native bytes differ from the common artifact receipt."
        }
        return Verified(app, binary)
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
