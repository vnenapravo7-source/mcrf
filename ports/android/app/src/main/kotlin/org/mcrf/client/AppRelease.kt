package org.mcrf.client

import org.json.JSONArray
import java.net.URI

/** Do not compare a Windows tag with an Android version or select another ABI. */
data class AppRelease(val asset: EnginePackage, val prerelease: Boolean, val notes:String="") {
    val page: URI get() = URI("https://github.com/${EnginePackage.REPOSITORY}/releases/tag/${asset.version}")
    companion object {
        /** Use the same platform/version gate as the offer; never include Windows notes. */
        fun withHistory(found:AppRelease,releases:JSONArray,installed:String):AppRelease {
            val notes=linkedMapOf<String,String>()
            repeat(releases.length()) { i ->
                val release=releases.optJSONObject(i)?:return@repeat;val tag=release.optString("tag_name")
                if(!release.optBoolean("draft")&&tag.startsWith("android-v")&&
                    AppUpdatePresentation.newer(tag,installed)&&!AppUpdatePresentation.newer(tag,found.asset.version)) {
                    notes.putIfAbsent(tag,release.optString("body").take(6000).ifBlank {"Исправления и улучшения приложения."})
                }
            }
            val ordered=notes.keys.sortedWith(Comparator {a,b->when {
                AppUpdatePresentation.newer(a,b)->-1
                AppUpdatePresentation.newer(b,a)->1
                else->a.compareTo(b)
            }})
            return found.copy(notes=ordered.joinToString("\n\n") {"${AppUpdatePresentation.buildLabel(it)}\n${notes.getValue(it)}"}.ifBlank {found.notes})
        }
        fun find(releases: JSONArray, abi: String): AppRelease? {
            val legacyName = when(abi) { "arm64-v8a" -> "arm64-v8a-release.apk";"armeabi-v7a" -> "armeabi-v7a-release.apk";"x86_64" -> "androidApp-x86_64-release.apk";else -> throw IllegalArgumentException("Неподдерживаемая архитектура Android.") }
            // API creation/list order is not semantic precedence (e.g. backports).
            val ordered=(0 until releases.length()).mapNotNull {releases.optJSONObject(it)}
                .filter {!it.optBoolean("draft")&&it.optString("tag_name").let {tag->tag.startsWith("android-v")&&AppUpdatePresentation.valid(tag)}}
                .sortedWith(Comparator {a,b->val left=a.getString("tag_name");val right=b.getString("tag_name");when {
                    AppUpdatePresentation.newer(left,right)->-1
                    AppUpdatePresentation.newer(right,left)->1
                    else->left.compareTo(right)
                }})
            for(release in ordered) {
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
