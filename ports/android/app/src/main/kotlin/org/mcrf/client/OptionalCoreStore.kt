package org.mcrf.client

import android.content.Context
import org.json.JSONObject

class OptionalCoreStore(context: Context) {
    private val store=ProfileStore(context,"preferences.enc")
    fun load(): OptionalCoreSettings = synchronized(ProfileStore.storageLock) {
        val root=store.read().let { if(it.isBlank()) JSONObject() else JSONObject(it) }
        root.optJSONObject(OptionalCoreSettings.KEY)?.let(OptionalCoreSettings::fromJson) ?: OptionalCoreSettings()
    }
    fun save(settings: OptionalCoreSettings) = synchronized(ProfileStore.storageLock) {
        settings.validate(); val root=store.read().let { if(it.isBlank()) JSONObject() else JSONObject(it) }
        root.put(OptionalCoreSettings.KEY,settings.json());store.write(root.toString())
    }
}
