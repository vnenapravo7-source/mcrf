package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in disconnect policy, not a routing rule or a reason to trust unknown SSIDs. */
internal data class BsWifiSettings(val pauseOnWifi:Boolean=false,val trustedEnabled:Boolean=false,val trustedSsids:List<String> = emptyList()) {
    fun shouldPause(wifi:Boolean,ssids:Set<String>)=wifi&&(pauseOnWifi||trustedEnabled&&trustedSsids.any {it in ssids})
    fun json()=JSONObject().put("pauseOnWifi",pauseOnWifi).put("trustedEnabled",trustedEnabled).put("trustedSsids",JSONArray(trustedSsids))
    companion object {
        const val KEY="bsWifiOptions"
        fun fromJson(value:JSONObject):BsWifiSettings {
            require(value.keys().asSequence().all {it in setOf("pauseOnWifi","trustedEnabled","trustedSsids")}) {"Неизвестная настройка Wi-Fi БС."}
            for(key in listOf("pauseOnWifi","trustedEnabled"))require(!value.has(key)||value.get(key) is Boolean)
            require(!value.has("trustedSsids")||value.get("trustedSsids") is JSONArray)
            val array=value.optJSONArray("trustedSsids")?:JSONArray()
            require(array.length()<=32) {"Не более 32 доверенных сетей."}
            val names=(0 until array.length()).map {require(array.get(it) is String);array.getString(it)}
            require(names.all {it.isNotBlank()&&it.toByteArray(Charsets.UTF_8).size<=32&&it.none(Char::isISOControl)&&it!="<unknown ssid>"}&&names.distinct().size==names.size) {"Укажите разные имена Wi-Fi длиной до 32 байт."}
            return BsWifiSettings(value.optBoolean("pauseOnWifi"),value.optBoolean("trustedEnabled"),names)
        }
    }
}

internal object BsWifiStore {
    fun load(context:Context)=synchronized(ProfileStore.storageLock) {
        val source=ProfileStore(context,"preferences.enc").read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.optJSONObject(BsWifiSettings.KEY)?.let(BsWifiSettings::fromJson)?:BsWifiSettings()
    }
    fun save(context:Context,value:BsWifiSettings)=synchronized(ProfileStore.storageLock) {
        BsWifiSettings.fromJson(value.json())
        val store=ProfileStore(context,"preferences.enc");val source=store.read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.put(BsWifiSettings.KEY,value.json());store.write(root.toString())
    }
}
