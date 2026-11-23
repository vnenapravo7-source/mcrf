package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Exact GitHub asset metadata, not an estimated or universal APK size. */
data class EnginePackage(val abi: String, val version: String, val url: URI, val bytes: Long, val sha256: String, val engine:String?=null, val versionCode:Long?=null) {
    class Unpublished(message: String) : IllegalArgumentException(message)
    /** An older installed package is not proof that the requested update was accepted. */
    fun matchesInstalled(size:Long, source:()->InputStream):Boolean {
        if(size!=bytes)return false
        return try {source().use {input->copyVerified(input,object:OutputStream(){override fun write(value:Int)=Unit;override fun write(buffer:ByteArray,offset:Int,length:Int)=Unit},this,{false},{})};true}
        catch(_:Exception){false}
    }
    companion object {
        const val REPOSITORY = "vnenapravo7-source/mcrf"
        const val CATALOGUE_RELEASE = "engines-2026-10-13-1"
        const val ANDROID_STABLE_RELEASE = "android-engines-v2.0.0"
        internal fun stableCatalogue(tag:String)=tag==ANDROID_STABLE_RELEASE||tag==CATALOGUE_RELEASE
        const val MAX_BYTES = 200L * 1024 * 1024
        fun abi(supported: List<String>, is64Bit: Boolean) = supported.firstOrNull {
            it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") && (it != "armeabi-v7a") == is64Bit
        } ?: throw IllegalArgumentException("Для этой архитектуры пакет движков не собран.")
        fun find(releases: JSONArray, abi: String, engine:String?=null, previewTag:String?=null): EnginePackage {
            require(abi!="universal") { "Для загрузки движка нужна архитектура этого устройства." }
            return findExact(releases, abi, engine, previewTag)
        }

        internal fun findExact(releases: JSONArray, abi: String, engine:String?=null, previewTag:String?=null): EnginePackage {
            require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "universal"))
            if(engine in BsEngineContract.engines&&abi!="universal")try{return findExact(releases,abi,BsEngineContract.KIND,previewTag)}catch(_:Unpublished){ /* old catalogue remains usable */ }
            require(engine!=BsEngineContract.KIND||abi!="universal"){"BS Engine выпускается отдельно для каждой архитектуры."}
            val expected = if(engine==null)"mcrf-engines-$abi-release.apk"else EngineModules.assetName(engine,abi)
            // One enduring cross-platform catalogue; older Android tags stay as compatibility fallbacks.
            val order=(0 until releases.length()).mapNotNull(releases::optJSONObject).sortedWith(
                compareBy<JSONObject> { release ->
                    val tag=release.optString("tag_name")
                    when {release.optBoolean("prerelease")&&EnginePreviewChannel.allows(tag,previewTag,engine)->0;tag==ANDROID_STABLE_RELEASE->1;tag==CATALOGUE_RELEASE->2;else->3}
                }.thenByDescending {EnginePreviewChannel.mobileVersion(it.optString("tag_name"))})
            var bestMobile:EnginePackage?=null
            for (release in order) {
                if(release.optBoolean("draft"))continue
                val tag = release.getString("tag_name")
                val prerelease=release.optBoolean("prerelease")
                if ((prerelease||EnginePreviewChannel.requiresOptIn(tag)) &&
                    (!prerelease||!EnginePreviewChannel.allows(tag,previewTag,engine))) continue
                require(tag.length in 1..128)
                val assets = release.optJSONArray("assets") ?: continue
                if (prerelease && EnginePreviewChannel.mobileVersion(tag)!=null) {
                    // An incomplete future beta does not block the published fallback for this engine and ABI.
                    val candidate=try { readAsset(assets,expected,tag,abi,engine) } catch (_:IllegalArgumentException) { null }
                    val code=candidate?.versionCode ?: continue
                    if (code>(bestMobile?.versionCode?:0)) bestMobile=candidate
                    continue
                }
                val mobile=bestMobile
                if (mobile!=null && !stableCatalogue(tag)) return mobile
                val candidate=readAsset(assets,expected,tag,abi,engine)?:continue
                if (mobile!=null && (candidate.versionCode?:0)<=(mobile.versionCode?:0)) return mobile
                return candidate
            }
            bestMobile?.let {return it}
            throw Unpublished("Пакет ${engine?.let(CsqttGenerations::engineLabel)?:"движков"} для $abi ещё не опубликован в репозитории. Повтори проверку позже.")
        }
        private fun readAsset(assets:JSONArray,expected:String,tag:String,abi:String,engine:String?):EnginePackage? {
            var candidate:EnginePackage?=null
            for (assetIndex in 0 until assets.length()) {
                val asset=assets.optJSONObject(assetIndex)?:continue
                if (asset.optString("name")!=expected||asset.optString("state")!="uploaded") continue
                require(candidate==null) { "В каталоге несколько пакетов с именем $expected." }
                val size=asset.optLong("size",-1L)
                val digest=asset.optString("digest")
                val uri=URI.create(asset.optString("browser_download_url"))
                require(size in 1..MAX_BYTES && digest.matches(Regex("sha256:[0-9a-f]{64}")) && uri.scheme=="https" && uri.host=="github.com" &&
                    uri.userInfo==null && uri.port==-1 && uri.query==null && uri.fragment==null &&
                    uri.rawPath==URI(null,null,"/$REPOSITORY/releases/download/$tag/$expected",null).rawPath &&
                    uri.normalize()==uri) { "Некорректные данные пакета движков." }
                val code=Regex("(?:^|;\\s*)mcrf-version-code=([1-9][0-9]*)$").find(asset.optString("label"))?.groupValues?.get(1)?.toLongOrNull()
                candidate=EnginePackage(abi,tag,uri,size,digest.removePrefix("sha256:"),engine,code)
            }
            return candidate
        }
        fun allowed(uri: URI) = uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
            (uri.host == "github.com" || uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
        fun copyVerified(input: InputStream, output: OutputStream, expected: EnginePackage, cancelled: () -> Boolean, progress: (Long) -> Unit) {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64*1024); var count = 0L
            while (true) {
                check(!cancelled()) { "Загрузка отменена" }
                val length = input.read(buffer); if (length < 0) break
                require(count + length <= expected.bytes) { "Размер пакета не совпадает с опубликованным." }
                output.write(buffer, 0, length); digest.update(buffer, 0, length); count += length; progress(count)
            }
            require(count == expected.bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected.sha256) {
                "Проверка целостности пакета не пройдена. Подключения не добавлены."
            }
        }
    }
}
