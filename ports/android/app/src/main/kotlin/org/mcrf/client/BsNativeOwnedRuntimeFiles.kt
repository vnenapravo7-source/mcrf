package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** The native session gets an empty VK backoff store only for an authenticated synthetic fixture. */
internal class BsNativeOwnedRuntimeFiles private constructor(base: Context) : ContextWrapper(base), AutoCloseable {
    private val root = File(base.cacheDir, "mcrf-bs-owned-files-${UUID.randomUUID()}").apply {
        check(mkdir())
        Os.chmod(path, 0x1c0)
    }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = root

    override fun close() {
        check(root.canonicalFile.parentFile == baseContext.cacheDir.canonicalFile && !Files.isSymbolicLink(root.toPath()))
        for (file in requireNotNull(root.listFiles())) {
            check(file.name in setOf("vk-credential-backoff-v1", "vk-credential-backoff-v1.lock",
                "vk-credential-backoff-v1.new", "vk-credential-backoff-v1.bak") &&
                file.isFile && !Files.isSymbolicLink(file.toPath()) && file.delete())
        }
        check(root.delete())
    }

    companion object {
        private fun ownedProfile(profile: JSONObject) = profile.optString("type") == "csqtt" &&
            CsqttGenerations.implementation(profile) in setOf("swg-panel-2.5.1", "swg-panel-2.5.3") &&
            profile.optString("server") == "192.0.2.1" && profile.optInt("server_port") == 9 &&
            profile.optString("password") == "owned-fixture-only"

        private fun debugTestOnly(application: ApplicationInfo) =
            application.flags and ApplicationInfo.FLAG_TEST_ONLY != 0 &&
                application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                application.splitSourceDirs.isNullOrEmpty()

        fun instrumentation(context: Context, runner: Context, profile: JSONObject, executable: File, pin: String): BsNativeOwnedRuntimeFiles {
            check(BuildConfig.DEBUG && ownedProfile(profile) && context.packageName == "org.mcrf.client" &&
                runner.packageName == "org.mcrf.client.test" && debugTestOnly(runner.applicationInfo))
            val pm = context.packageManager
            val archive = requireNotNull(EngineSignatures.archive(pm, runner.applicationInfo.sourceDir))
            check(archive.info.packageName == runner.packageName)
            EngineSignerPolicy.requireSame(archive.signers,
                EngineSignatures.installed(pm, context.packageName).signers)
            check(executable.canonicalFile == executable && executable.parentFile == File(runner.applicationInfo.nativeLibraryDir) &&
                executable.name == "libmcrf_owned_native_udp_fixture.so" &&
                pin.matches(Regex("[0-9a-f]{64}")) && BsEngineContract.sha(executable.inputStream()) == pin)
            check(executable.inputStream().use { BsNativeContract.hasMarkers(it, listOf("owned-fixture-only")) })
            return BsNativeOwnedRuntimeFiles(context)
        }

        fun module(context: Context, profile: JSONObject?, executable: File, apkPin: String): BsNativeOwnedRuntimeFiles? {
            if (!BuildConfig.DEBUG || profile == null || !ownedProfile(profile)) return null
            val application = BsEnginePackage.installation(context)
            if (!debugTestOnly(application)) return null
            check(BsNativeContract.pin(context) == apkPin && executable.canonicalFile == executable &&
                executable.parentFile == File(application.nativeLibraryDir) && executable.name == "libcsqtt_transport.so")
            check(executable.inputStream().use { BsNativeContract.hasMarkers(it, listOf("OWNED_BOOTSTRAP_CLOSED",
                "OWNED_BOOTSTRAP_SYNTHETIC_PROFILE_REQUIRED", "OWNED_BOOTSTRAP_GENERATION_MISMATCH")) })
            return BsNativeOwnedRuntimeFiles(context)
        }
    }
}
