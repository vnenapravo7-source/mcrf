package org.mcrf.client

/** An Android preview may offer its own tested primary engine, never arbitrary previews. */
object EnginePreviewChannel {
    fun tag(version:String):String? = if(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-preview\\.[0-9]{8}\\.[0-9]+"))) "android-v$version" else null
    fun allows(tag:String,expected:String?,engine:String?) = engine in setOf("sing-box","steer",WgAddonPolicy.KIND) && expected!=null &&
        tag==expected && tag.removePrefix("android-v").let {version->EnginePreviewChannel.tag(version)==tag}
}
