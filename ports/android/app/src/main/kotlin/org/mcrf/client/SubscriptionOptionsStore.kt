package org.mcrf.client

import android.content.Context
import org.json.JSONObject

object SubscriptionOptionsStore {
    fun load(context: Context): SubscriptionOptions = synchronized(ProfileStore.storageLock) {
        val raw = ProfileStore(context, "preferences.enc").read()
        val root = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
        val saved = if(root.has(SubscriptionOptions.SECTION)) root.getJSONObject(SubscriptionOptions.SECTION) else null
        saved?.let(SubscriptionOptions::from) ?: SubscriptionOptions(intervalMinutes = legacyMinutes(context))
    }
    private fun legacyMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        return SubscriptionSchedule.selected(if(prefs.contains("subscriptionHours")) prefs.getInt("subscriptionHours",6) else null) * 60
    }
    fun save(context: Context, options: SubscriptionOptions) = synchronized(ProfileStore.storageLock) {
        val checked = SubscriptionOptions.validate(options.json())
        val store = ProfileStore(context, "preferences.enc"); val raw = store.read()
        val root = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
        root.put(SubscriptionOptions.SECTION, checked); store.write(root.toString())
    }
    /** Each control merges into the latest encrypted settings; adjacent owners' sections survive. */
    fun change(context: Context, edit: (SubscriptionOptions) -> SubscriptionOptions): SubscriptionOptions = synchronized(ProfileStore.storageLock) {
        edit(load(context)).also { save(context, it) }
    }
}
