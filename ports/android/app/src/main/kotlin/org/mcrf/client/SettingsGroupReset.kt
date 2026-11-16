package org.mcrf.client

import org.json.JSONObject

/** A reset owns explicit keys only, never an entire store, profile or credential. */
object SettingsGroupReset {
    fun apply(current:JSONObject,defaults:JSONObject,keys:Set<String>):JSONObject {
        require(keys.isNotEmpty()&&keys.all(defaults::has)){"Неизвестная группа настроек"}
        return JSONObject(current.toString()).apply {
            keys.forEach {key->put(key,defaults.get(key).let {when(it){is JSONObject->JSONObject(it.toString());is org.json.JSONArray->org.json.JSONArray(it.toString());else->it}})}
        }
    }
}
