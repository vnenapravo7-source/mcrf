package org.mcrf.client

/** An Android preview may offer its own tested primary engine, never arbitrary previews. */
object EnginePreviewChannel {
    internal data class BetaVersion(val major:Int,val minor:Int,val patch:Int,val build:Int):Comparable<BetaVersion> {
        override fun compareTo(other:BetaVersion)=compareValuesBy(this,other,BetaVersion::major,BetaVersion::minor,BetaVersion::patch,BetaVersion::build)
    }
    private val mobileTag=Regex("android-v(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})-beta\\.([1-9][0-9]{0,8})")
    internal fun mobileVersion(tag:String)=mobileTag.matchEntire(tag)?.groupValues?.let {
        BetaVersion(it[1].toInt(),it[2].toInt(),it[3].toInt(),it[4].toInt())
    }
    private val pinnedMobileVersion=requireNotNull(mobileVersion(MobileBetaUpdates.TAG))
    fun tag(version:String):String? = if(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-preview\\.[0-9]{8}\\.[0-9]+"))) "android-v$version" else null
    fun requiresOptIn(releaseTag:String) = MobileBetaUpdates.isBetaTag(releaseTag) || tag(releaseTag.removePrefix("android-v"))==releaseTag
    fun allows(tag:String,expected:String?,engine:String?) = (expected==MobileBetaUpdates.TAG&&engine in EngineModules.allKinds&&mobileVersion(tag)?.let {it>=pinnedMobileVersion}==true)||engine in setOf("sing-box","steer",WgAddonPolicy.KIND) && expected!=null &&
        tag==expected && tag.removePrefix("android-v").let {version->EnginePreviewChannel.tag(version)==tag}
}
