package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** An account awaiting a verified exit is encrypted without adding an unverified server. */
object WarpRegistrationStore {
    const val SECTION = "warpPendingRegistration"
    fun validate(value: JSONObject): JSONObject {
        require(value.keys().asSequence().toSet()==setOf("account"))
        val account=WarpProfile.account(value.getString("account"))
        require(account.optString("id").isNotBlank() && account.optString("token").isNotBlank())
        return JSONObject().put("account",account.toString())
    }
    fun load(context: Context): String? = synchronized(ProfileStore.storageLock) {
        val raw=ProfileStore(context,"preferences.enc").read()
        val root=if(raw.isEmpty()) JSONObject() else JSONObject(raw)
        root.optJSONObject(SECTION)?.let { validate(it).getString("account") }
    }
    fun save(context: Context, account: String) = synchronized(ProfileStore.storageLock) {
        val checked=validate(JSONObject().put("account",account))
        val store=ProfileStore(context,"preferences.enc");val raw=store.read()
        val root=if(raw.isEmpty()) JSONObject() else JSONObject(raw)
        root.put(SECTION,checked);store.write(root.toString())
    }
    fun clearIf(context: Context, account: String) = synchronized(ProfileStore.storageLock) {
        val store=ProfileStore(context,"preferences.enc");val raw=store.read()
        val root=if(raw.isEmpty()) JSONObject() else JSONObject(raw)
        if(root.optJSONObject(SECTION)?.optString("account")==account) {root.remove(SECTION);store.write(root.toString())}
    }
}
