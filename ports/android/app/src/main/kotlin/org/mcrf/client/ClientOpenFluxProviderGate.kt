package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private one-shot diagnostics, never the ordinary OpenFlux package/update path. */
internal object ClientOpenFluxProviderGate {
    const val PACKAGE="org.mcrf.engines.openflux.diagnostic"
    const val CLI="--mcrf-diagnostic-config-v1"
    const val APPROVED_ORIGINAL_SHA="2183c17dda4517210a2d99bafb57ae13fd60857a9df8d570dcd50ef95872f6c4"
    // Exact coordinator-built, signed and statically verified private diagnostic artifact.
    private const val APK_SHA="b2ab123dcff78bed36a000a0a2c4f153f58a3b8bc3f8d88cf40b608b86ac07e9"
    private const val NATIVE_SHA="e687063d433e4e93f128a59de4af56356632cdd8da52198e6c7626aa71812518"
    private const val NATIVE_BYTES=27428656L
    private const val V2_APK_SHA="7872d6887cdc7c2f2490f5b5b275b0fdce20bf04f0ef1a4b9b5dd0604d60228b"
    private const val V2_NATIVE_SHA="759245905e7d0ffa1cc3ed237a88aac5250c8819f8650035b32767e250747fea"
    private const val V2_NATIVE_BYTES=27433616L
    private const val V3_APK_SHA="5bbef1c273fbaa298c4474951f58f3f8bee449053092f7a4f799b4d189df412b"
    private const val V3_NATIVE_SHA="26af028a7283d9f73a17c8ae04a94ee357bbd58641b0b0f1434aceddce5f3c53"
    private const val V3_NATIVE_BYTES=27439984L
    private const val COMMIT="f12e981629a18c8c01007d59ac002254c02972bb"
    data class Verified(val executable:File,val apkSha:String,val nativeSha:String)

    fun requireApproved(entries:List<ServerImport.Server>) {
        check(BuildConfig.DEBUG&&entries.size==1) {"Диагностика доступна только в отдельной тестовой проверке."}
        val entry=entries.single()
        require(entry.protocol=="openflux"&&sha(entry.original.toByteArray(Charsets.UTF_8))==APPROVED_ORIGINAL_SHA)
        val stored=requireNotNull(entry.carrier)
        val original=requireNotNull(OpenFluxProfile.link(entry.original).carrier)
        require(listOf("mode","codec","negotiate","secret","context","transports").all {key->
            original.opt(key)?.toString()==stored.opt(key)?.toString()
        }) {"Сохранённая конфигурация отличается от разрешённого оригинала. Ничего не изменено."}
    }

    fun cli(version:Int):String {
        require(version in 1..3)
        return if(version==1)CLI else "--mcrf-diagnostic-config-v$version"
    }

    fun verify(context:Context,version:Int=1):Verified {
        val command=cli(version)
        check(BuildConfig.DEBUG&&Process.is64Bit()&&"arm64-v8a" in Build.SUPPORTED_ABIS) {"Диагностический модуль доступен только в тестовой ARM64-сборке."}
        val pm=context.packageManager
        val info=pm.getPackageInfo(PACKAGE,PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES)
        val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(value:android.content.pm.PackageInfo)=value.signingInfo?.apkContentsSigners?.map {it.toCharsString()}?.toSet().orEmpty()
        val app=requireNotNull(info.applicationInfo)
        require(info.longVersionCode==7L+version&&info.versionName=="0.7.3-mcrf-diagnostic.${7+version}")
        require(app.enabled&&app.splitSourceDirs.isNullOrEmpty()&&signers(info).isNotEmpty()&&signers(info)==signers(own))
        val metadata=requireNotNull(app.metaData)
        require(metadata.getInt("org.mcrf.engines.interface")==1&&metadata.getInt("org.mcrf.engines.openflux")>=2&&
            metadata.getString("org.mcrf.engines.kind")=="openflux"&&
            metadata.getString("org.mcrf.engines.source.repository")=="p1neappleXpress/OpenFlux"&&
            metadata.getString("org.mcrf.engines.source.version")=="v0.7.3"&&
            metadata.getString("org.mcrf.engines.source.commit")==COMMIT&&
            metadata.getString("org.mcrf.engines.source.overlay")=="mcrf-provider-stage.$version"&&
            metadata.getInt("org.mcrf.engines.script-updater")==0&&
            metadata.getInt("org.mcrf.engines.provider-diagnostic")==version&&
            metadata.getString("org.mcrf.engines.provider-diagnostic-cli")==command.removePrefix("--mcrf-"))
        val apk=File(app.sourceDir).canonicalFile
        val libraryRoot=File(requireNotNull(app.nativeLibraryDir)).canonicalFile
        val binary=File(libraryRoot,"libopenflux.so").canonicalFile
        val expectedBytes=when(version){3->V3_NATIVE_BYTES;2->V2_NATIVE_BYTES;else->NATIVE_BYTES}
        val expectedApk=when(version){3->V3_APK_SHA;2->V2_APK_SHA;else->APK_SHA}
        val expectedNative=when(version){3->V3_NATIVE_SHA;2->V2_NATIVE_SHA;else->NATIVE_SHA}
        require(binary.parentFile==libraryRoot&&binary.isFile&&binary.canExecute()&&binary.length()==expectedBytes)
        val apkSha=hash(apk)
        val nativeSha=hash(binary)
        require(apkSha==expectedApk&&nativeSha==expectedNative) {"Диагностический пакет не совпадает с проверенной сборкой."}
        return Verified(binary,apkSha,nativeSha)
    }

    fun proof(context:Context,version:Int=1):JSONObject=verify(context,version).let {value->JSONObject()
        .put("package",PACKAGE).put("apk_sha256",value.apkSha).put("native_sha256",value.nativeSha)
        .put("source_version","v0.7.3").put("source_commit",COMMIT).put("overlay","mcrf-provider-stage.$version")
        .put("schema",version)}

    private fun sha(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(65536);while(true){val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
