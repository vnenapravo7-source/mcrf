package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Stored with the existing Keystore-encrypted settings and portable encrypted backup. */
object LocalProxySettings {
    fun value(context:Context):JSONObject=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val raw=store.read();val settings=if(raw.isEmpty())JSONObject()else JSONObject(raw)
        settings.optJSONObject("localProxy")?.let {return@synchronized LocalProxyConfig.validate(it)}
        LocalProxyConfig.generate().also {settings.put("localProxy",it);store.write(settings.toString())}
    }
    fun save(context:Context,auth:JSONObject)=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val raw=store.read();val settings=if(raw.isEmpty())JSONObject()else JSONObject(raw)
        settings.put("localProxy",LocalProxyConfig.validate(auth));store.write(settings.toString())
    }
}
