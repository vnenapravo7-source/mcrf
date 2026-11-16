package org.mcrf.client

internal object MobileBetaUpdates {
    const val DISABLED = "Бета-обновления отключены. Проверьте обновления ещё раз."
    fun allows(asset:EnginePackage,enabled:Boolean) = enabled || !isBetaTag(asset.version)
    fun allows(context:android.content.Context,asset:EnginePackage) =
        allows(asset,context.getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE).getBoolean(KEY,false))
    fun requireAllowed(context:android.content.Context,asset:EnginePackage) {
        require(allows(context,asset)){DISABLED}
    }
    const val KEY = "mobileBetaUpdates"
    // The engine catalogue can outlive an individual beta app build.
    const val TAG = "android-v2.0.0-beta.10177"
    private val betaTag = Regex("android-v[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}-beta\\.[0-9]{1,9}")
    fun isBetaTag(tag:String) = betaTag.matches(tag)
    const val APP_ASSET = "mcrf-android-beta-universal-release.apk"
}
