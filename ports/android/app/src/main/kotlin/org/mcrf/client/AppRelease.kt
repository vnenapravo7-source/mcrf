package org.mcrf.client

import org.json.JSONArray
import java.net.URI

/** Do not compare a Windows tag with an Android version or select another ABI. */
data class AppRelease(val asset: EnginePackage, val prerelease: Boolean, val notes:String="") {
    val page: URI get() = URI("https://github.com/${EnginePackage.REPOSITORY}/releases/tag/${asset.version}")
    companion object {
        fun find(releases: JSONArray, abi: String): AppRelease? {
            val legacyName = when(abi) { "arm64-v8a" -> "arm64-v8a-release.apk";"armeabi-v7a" -> "armeabi-v7a-release.apk";"x86_64" -> "androidApp-x86_64-release.apk";else -> throw IllegalArgumentException("Неподдерживаемая архитектура Android.") }
            for(index in 0 until releases.length()) {
                val release=releases.getJSONObject(index);if(release.optBoolean("draft"))continue
                val tag=release.optString("tag_name")
                if(!tag.startsWith("android-v"))continue
                val assets=release.optJSONArray("assets")?:continue
                // Prefer one real universal main APK, even when a legacy alias appears first.
                val name=if((0 until assets.length()).any {
                    val item=assets.getJSONObject(it)
                    item.optString("name")=="mcrf-android-universal-release.apk"&&item.optString("state")=="uploaded"
                })"mcrf-android-universal-release.apk"else legacyName
                for(entry in 0 until assets.length()) {
                    val item=assets.getJSONObject(entry);if(item.optString("name")!=name||item.optString("state")!="uploaded")continue
                    require(tag.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Некорректное имя сборки." }
                    val bytes=item.getLong("size");val sum=item.optString("digest").removePrefix("sha256:")
                    val url=URI(item.getString("browser_download_url"))
                    require(bytes in 1..EnginePackage.MAX_BYTES && sum.matches(Regex("[0-9a-f]{64}")) && url.scheme=="https" && url.host=="github.com" && url.userInfo==null && url.port==-1 && url.query==null && url.fragment==null &&
                        url.path=="/${EnginePackage.REPOSITORY}/releases/download/$tag/$name") { "Некорректные данные Android APK." }
                    return AppRelease(EnginePackage(abi,tag,url,bytes,sum),release.optBoolean("prerelease"),release.optString("body").take(6000))
                }
            }
            return null
        }
    }
}
