package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Single preference file keeps settings in the existing encrypted backup extension path. */
class ClientTunStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
    private val store = ProfileStore(context,"preferences.enc")
    fun load(): ClientTunSettings = synchronized(ProfileStore.storageLock) { val root = store.read().let { if(it.isBlank()) JSONObject() else JSONObject(it) }
        root.optJSONObject(ClientTunSettings.KEY)?.let(ClientTunSettings::fromJson) ?: ClientTunSettings(mixedPort = preferences.getInt("port",10808)) }
    fun auth(): JSONObject = LocalProxySettings.value(context)
    fun save(settings: ClientTunSettings) = synchronized(ProfileStore.storageLock) { settings.validate(auth()); val root = store.read().let { if(it.isBlank()) JSONObject() else JSONObject(it) }; root.put(ClientTunSettings.KEY,settings.json()); store.write(root.toString()) }
    fun saveAuth(auth: JSONObject) { load().validate(auth); LocalProxySettings.save(context,auth) }
    fun regenerateAuth(): JSONObject = LocalProxyConfig.generate(auth().getBoolean("enabled")).also(::saveAuth)
}
