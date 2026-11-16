package org.mcrf.client

import android.content.Context
import org.json.JSONObject

object ClientBehaviorStore {
    fun load(context:Context):ClientBehaviorSettings=synchronized(ProfileStore.storageLock){
        val source=ProfileStore(context,"preferences.enc").read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.optJSONObject(ClientBehaviorSettings.KEY)?.let(ClientBehaviorSettings::fromJson)?:ClientBehaviorSettings()
    }
    fun save(context:Context,value:ClientBehaviorSettings)=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val source=store.read();val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.put(ClientBehaviorSettings.KEY,value.json());store.write(root.toString())
    }
}
