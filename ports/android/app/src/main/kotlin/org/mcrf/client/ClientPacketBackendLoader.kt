package org.mcrf.client

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import org.mcrf.engine.protocol.v1.PacketBackend
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Candidate-only optional backend. Resolve exclusively in a fresh host-owned primary worker. */
object ClientPacketBackendLoader {
    const val PACKAGE = "org.mcrf.engines.wg"
    const val ENTRY = "org.mcrf.engines.wg.WgPacketBackend"
    fun load(context: Context, expectedApkSha256: String): PacketBackend {
        val privateProcesses=setOf(context.packageName+":primary",context.packageName+":primaryProbe")+
            ClientPrimaryWorkerWire.routeWorkers.map {context.packageName+ClientPrimaryWorkerWire.processSuffix(it)}
        require(Application.getProcessName() in privateProcesses) { "PACKET_BACKEND_PRIVATE_WORKER" }
        require(expectedApkSha256.matches(Regex("[0-9a-f]{64}"))) { "PACKET_BACKEND_PIN" }
        val pm = context.packageManager
        fun inspect() = pm.getPackageInfo(PACKAGE, PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }?.sorted().orEmpty()
        val info = inspect()
        val app = requireNotNull(info.applicationInfo)
        val host = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val metadata = requireNotNull(app.metaData)
        require(app.enabled && app.minSdkVersion <= android.os.Build.VERSION.SDK_INT &&
            app.splitSourceDirs.isNullOrEmpty() && signers(info).isNotEmpty() && signers(info) == signers(host) &&
            metadata.getInt("org.mcrf.engine.protocol.api") == PacketBackend.API_VERSION &&
            metadata.getString("org.mcrf.engine.protocol.entry") == ENTRY &&
            metadata.getString("org.mcrf.engine.protocol.kind") == "wg-awg" &&
            metadata.getBoolean("org.mcrf.engine.protocol.candidate")) { "PACKET_BACKEND_IDENTITY" }
        val parent = context.classLoader
        listOf("go.Seq", "io.nekohasekai.libbox.Libbox", "org.steer.core.SteerCore").forEach {
            val absent = try { parent.loadClass(it); false } catch (_: ClassNotFoundException) { true }
            check(absent) { "PACKET_BACKEND_PARENT_NATIVE_RUNTIME" }
        }
        val apk = File(app.sourceDir).canonicalFile
        require(apk.isFile && hash(apk) == expectedApkSha256) { "PACKET_BACKEND_APK_HASH" }
        val directory = File(requireNotNull(app.nativeLibraryDir)).canonicalFile
        val native = File(directory, "libmcrfwg.so").canonicalFile
        require(native.parentFile == directory && native.isFile) { "PACKET_BACKEND_NATIVE" }
        val header = native.inputStream().use { input -> ByteArray(20).also { bytes ->
            var offset = 0
            while(offset < bytes.size) { val n = input.read(bytes, offset, bytes.size-offset); require(n>0); offset += n }
        } }
        require(header[0] == 127.toByte() && header[1] == 69.toByte() && header[2] == 76.toByte() && header[3] == 70.toByte() &&
            header[5] == 1.toByte() && header[4] == (if(android.os.Process.is64Bit()) 2 else 1).toByte()) { "PACKET_BACKEND_ELF" }
        val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
        val abi = when(machine) { 183 -> "arm64-v8a"; 40 -> "armeabi-v7a"; 62 -> "x86_64"; else -> "" }
        require(abi in android.os.Build.SUPPORTED_ABIS) { "PACKET_BACKEND_ABI" }
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.toList()
            require(entries.size == 1 && entries.single().name == "lib/$abi/libmcrfwg.so") { "PACKET_BACKEND_NATIVE_SET" }
            val expected = zip.getInputStream(entries.single()).use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while(true) {val n = input.read(buffer); if(n<0) break; digest.update(buffer,0,n)}
                digest.digest().toHex()
            }
            require(hash(native) == expected) { "PACKET_BACKEND_EXTRACTED_HASH" }
        }
        val loader = PathClassLoader(apk.path, directory.path, parent)
        val entry = Class.forName(ENTRY, false, loader)
        require(entry.classLoader === loader && PacketBackend::class.java.isAssignableFrom(entry)) { "PACKET_BACKEND_CLASS_IDENTITY" }
        listOf("go.Seq", "org.mcrf.wgbackend.wgbackend.Wgbackend").forEach {
            require(Class.forName(it, false, loader).classLoader === loader) { "PACKET_BACKEND_NATIVE_CLASSLOADER" }
        }
        val after = inspect(); val afterApp = requireNotNull(after.applicationInfo)
        require(after.longVersionCode == info.longVersionCode && after.lastUpdateTime == info.lastUpdateTime &&
            afterApp.enabled && afterApp.sourceDir == app.sourceDir && afterApp.nativeLibraryDir == app.nativeLibraryDir &&
            afterApp.splitSourceDirs.isNullOrEmpty() && signers(after) == signers(info) && hash(apk) == expectedApkSha256) { "PACKET_BACKEND_CHANGED_DURING_LOAD" }
        return try { entry.getConstructor(Context::class.java).newInstance(context.applicationContext) as PacketBackend }
        catch (_: ReflectiveOperationException) { throw IllegalStateException("PACKET_BACKEND_FACTORY_FAILED") }
    }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while(true) { val n = input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        digest.digest().toHex()
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
