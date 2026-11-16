package org.mcrf.client

internal object MobileBetaUpdates {
    const val DISABLED = "Бета-обновления отключены. Проверьте обновления ещё раз."
    fun allows(asset:EnginePackage,enabled:Boolean) = enabled || asset.version!=TAG
    fun allows(context:android.content.Context,asset:EnginePackage) =
        allows(asset,context.getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE).getBoolean(KEY,false))
    fun requireAllowed(context:android.content.Context,asset:EnginePackage) {
        require(allows(context,asset)){DISABLED}
    }
    const val KEY = "mobileBetaUpdates"
    const val TAG = "android-v2.0.0-beta.10177"
    const val APP_ASSET = "mcrf-android-beta-universal-release.apk"
}
