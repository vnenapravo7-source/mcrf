package org.mcrf.client

import android.content.Context
import org.json.JSONObject

class CoreTuningStore(context: Context) {
    private val store = ProfileStore(context,"preferences.enc")
    fun load(): CoreTuningSettings = synchronized(ProfileStore.storageLock) { val root = store.read().let { if(it.isBlank()) JSONObject() else JSONObject(it) }; root.optJSONObject(CoreTuningSettings.KEY)?.let(CoreTuningSettings::fromJson) ?: CoreTuningSettings() }
    fun save(settings: CoreTuningSettings) = synchronized(ProfileStore.storageLock) { settings.validate(); val root = store.read().let { if(it.isBlank()) JSONObject() else JSONObject(it) }; root.put(CoreTuningSettings.KEY,settings.json()); store.write(root.toString()) }
}
