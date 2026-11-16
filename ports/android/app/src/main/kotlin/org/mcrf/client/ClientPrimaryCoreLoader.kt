package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import java.io.File
import java.security.MessageDigest

/** Resolve only in a fresh host-owned worker under the coordinator's module usage lease.
 * Does not initialize/construct the entry or call native code. Package owner supplies metadata.
 * Entire module runtime and JNI must be child-defined; the existing shared SPI is parent-defined. */
object ClientPrimaryCoreLoader {
    const val SPI_CLASS = "org.mcrf.engine.spi.v1.Engine"
    private const val META = "org.mcrf.engines."
    data class Resolved(val entry: Class<*>, val loader: ClassLoader, val version: Long)

    /** Agreed factory: exactly public Context constructor for BOTH primary entries.
     * No no-arg fallback, guessed factory, arbitrary overload or absolute native preload.
     * Main calls only in the fresh worker while holding its verified usage lease. */
    fun instantiate(resolved: Resolved,context: Context): Any {
        require(ClientPrimaryCorePolicy.Core.values().any {it.entryClass==resolved.entry.name} &&
            resolved.entry.classLoader===resolved.loader) { "PRIMARY_CORE_FACTORY_IDENTITY" }
        return try {resolved.entry.getConstructor(Context::class.java).newInstance(context.applicationContext)}
        catch(_:ReflectiveOperationException) {throw IllegalStateException("PRIMARY_CORE_CONTEXT_FACTORY_FAILED")}
    }

    fun resolve(context: Context, core: ClientPrimaryCorePolicy.Core, expectedCoreApi: Int,
                expectedApkSha256: String): Resolved {
        require(expectedApkSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "PRIMARY_CORE_ARTIFACT_PIN" }
        val pm=context.packageManager
        fun inspect()=pm.getPackageInfo(core.packageName,PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES)
        val host=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo)=info.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }?.sorted().orEmpty()
        val info=inspect();val app=requireNotNull(info.applicationInfo)
        require(app.minSdkVersion<=android.os.Build.VERSION.SDK_INT) { "PRIMARY_CORE_ANDROID_VERSION" }
        val meta=app.metaData
        ClientPrimaryCorePolicy.requireIdentity(core,info.packageName,meta?.getString(META+"kind").orEmpty(),
            meta?.getInt("org.mcrf.engine.spi.version") ?: 0,meta?.getInt("org.mcrf.engine.core.api") ?: 0,expectedCoreApi,
            meta?.getString("org.mcrf.engine.entry").orEmpty(),app.enabled,signers(host).isNotEmpty() && signers(host)==signers(info))
        require(meta?.getInt(META+"primary-core")==1) { "PRIMARY_CORE_METADATA" }
        // Parent-first resolution is deliberate: no duplicate shared SPI and no runtime shadowing.
        val parent=context.classLoader
        val spi=parent.loadClass(SPI_CLASS)
        listOf("go.Seq","io.nekohasekai.libbox.Libbox","org.steer.core.SteerCore").forEach { name ->
            val absent=try { parent.loadClass(name);false } catch(_:ClassNotFoundException) { true }
            check(absent) { "PRIMARY_CORE_BASE_RUNTIME_PRESENT" }
        }
        val apk=File(app.sourceDir).canonicalFile
        require(apk.isFile && apk.canRead()) { "PRIMARY_CORE_APK" }
        // Split delivery needs a trusted complete split manifest, not an unpinned extra code path.
        require(app.splitSourceDirs.isNullOrEmpty()) { "PRIMARY_CORE_SPLIT_MANIFEST_REQUIRED" }
        fun hash(file: File): String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        require(hash(apk).equals(expectedApkSha256,true)) { "PRIMARY_CORE_APK_HASH" }
        val libraries=File(requireNotNull(app.nativeLibraryDir)).canonicalFile
        val nativeName=if(core==ClientPrimaryCorePolicy.Core.SINGBOX) "libbox.so" else "libsteer_vpn.so"
        val native=File(libraries,nativeName)
        require(native.canonicalFile.parentFile==libraries && native.isFile && native.canRead()) { "PRIMARY_CORE_EXTRACTED_ABI_REQUIRED" }
        val header=native.inputStream().use { input -> ByteArray(20).also { input.readFully(it) } }
        require(header[0]==127.toByte() && header[1]=='E'.code.toByte() && header[2]=='L'.code.toByte() &&
            header[3]=='F'.code.toByte() && header[5]==1.toByte() &&
            header[4]==(if(android.os.Process.is64Bit())2 else 1).toByte()) { "PRIMARY_CORE_ELF_PROCESS_ABI" }
        val machine=(header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
        val abi=when(machine) { 183->"arm64-v8a";40->"armeabi-v7a";62->"x86_64";else->"" }
        require(abi in android.os.Build.SUPPORTED_ABIS) { "PRIMARY_CORE_ELF_MACHINE" }
        val declared=meta.getString("org.mcrf.engine.abis").orEmpty().split(',')
        require(declared.distinct().size==declared.size && declared.all {it in setOf("arm64-v8a","armeabi-v7a","x86_64")} && abi in declared) { "PRIMARY_CORE_ABI_METADATA" }
        val loader=PathClassLoader(apk.path,libraries.path,parent)
        val entry=Class.forName(core.entryClass,false,loader)
        require(entry.classLoader===loader && spi.isAssignableFrom(entry)) { "PRIMARY_CORE_SPI_CLASS_IDENTITY" }
        if(core==ClientPrimaryCorePolicy.Core.SINGBOX) {
            listOf("go.Seq","io.nekohasekai.libbox.Libbox").forEach { name ->
                require(Class.forName(name,false,loader).classLoader===loader) { "PRIMARY_CORE_NATIVE_CLASSLOADER" }
            }
        }
        val after=inspect();val afterApp=requireNotNull(after.applicationInfo)
        require(after.longVersionCode==info.longVersionCode && after.lastUpdateTime==info.lastUpdateTime &&
            afterApp.sourceDir==app.sourceDir && afterApp.nativeLibraryDir==app.nativeLibraryDir &&
            afterApp.splitSourceDirs.isNullOrEmpty() && afterApp.enabled && signers(after)==signers(info) &&
            hash(apk).equals(expectedApkSha256,true)) { "PRIMARY_CORE_CHANGED_DURING_LOAD" }
        return Resolved(entry,loader,info.longVersionCode)
    }
    private fun java.io.InputStream.readFully(bytes: ByteArray) {
        var offset=0
        while(offset<bytes.size) { val count=read(bytes,offset,bytes.size-offset);require(count>0) { "PRIMARY_CORE_ELF_TRUNCATED" };offset+=count }
    }
}
