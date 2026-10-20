package org.mcrf.client

import android.content.Context
import org.json.JSONObject

object WarpSettingsStore {
    fun load(context: Context): WarpSettings = synchronized(ProfileStore.storageLock) {
        val raw = ProfileStore(context,"preferences.enc").read()
        val root = if(raw.isEmpty()) JSONObject() else JSONObject(raw)
        if(root.has(WarpSettings.SECTION)) WarpSettings.from(root.getJSONObject(WarpSettings.SECTION)) else WarpSettings()
    }
    fun save(context: Context, settings: WarpSettings) = synchronized(ProfileStore.storageLock) {
        val checked = WarpSettings.validate(settings.json())
        val store = ProfileStore(context,"preferences.enc"); val raw = store.read()
        val root = if(raw.isEmpty()) JSONObject() else JSONObject(raw)
        root.put(WarpSettings.SECTION,checked); store.write(root.toString())
    }
    fun change(context: Context, edit: (WarpSettings) -> WarpSettings): WarpSettings = synchronized(ProfileStore.storageLock) {
        edit(load(context)).also { save(context,it) }
    }
}
