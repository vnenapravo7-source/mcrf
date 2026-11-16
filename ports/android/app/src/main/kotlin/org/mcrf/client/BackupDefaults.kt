package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Explicit scopes: never remove profiles, routing, installations, engine packages or proxy credentials. */
object BackupDefaults {
    enum class Group { GENERAL, ADVANCED, PRESERVE }
    enum class Scope { GENERAL, ADVANCED, ALL_SETTINGS }
    /** Pure copy used by the durable reset and isolated tests. Authorization/data groups survive. */
    internal fun settingsForReset(source: JSONObject, scope: Scope): JSONObject {
        val result = JSONObject(source.toString())
        val groups = when(scope) {
            Scope.GENERAL -> setOf(Group.GENERAL)
            Scope.ADVANCED -> setOf(Group.ADVANCED)
            Scope.ALL_SETTINGS -> setOf(Group.GENERAL,Group.ADVANCED)
        }
        groups.forEach { group -> (AndroidBackup.preferenceKeys(group)+AndroidBackup.sectionKeys(group)).forEach(result::remove) }
        return result
    }
    fun reset(context: Context, scope: Scope) = synchronized(ProfileStore.storageLock) {
        val stores = ProfileStore.snapshot(context).toMutableMap()
        if(stores["connections.enc"].isNullOrEmpty()) stores["connections.enc"] = ConnectionCatalogue("").serialize()
        if(stores["routing.enc"].isNullOrEmpty()) { val routes = RoutingStore(context); routes.load(); stores["routing.enc"] = routes.snapshot().toString() }
        var prefs = stores["preferences.enc"].orEmpty().let { if(it.isEmpty()) JSONObject() else JSONObject(it) }
        // Preserve every registered plain preference when the scope does not reset it.
        val activity = context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val preferenceSnapshot = activity.all
        AndroidBackup.preferenceKeys().forEach { key -> prefs.remove(key); preferenceSnapshot[key]?.let { prefs.put(key,it) } }
        prefs.put("localProxy",LocalProxySettings.value(context))
        prefs = settingsForReset(prefs,scope)
        // Validate before the pointer switch: malformed/unknown retained sections must
        // never commit a reset that startup cannot apply.
        AndroidBackup.validatePreferences(prefs)
        stores["preferences.enc"] = prefs.toString()
        ProfileStore.replaceAll(context,stores)
        AndroidBackup.applyRestoredPreferences(context)
    }
}

