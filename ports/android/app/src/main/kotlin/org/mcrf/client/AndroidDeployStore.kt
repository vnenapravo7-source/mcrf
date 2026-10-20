package org.mcrf.client

import android.content.Context
import org.json.JSONObject

interface VkRelayPersistence {
    fun verifyCaptcha(url:String,cancelled:()->Boolean):String = throw IllegalStateException("Для VK требуется ручное подтверждение в приложении")
    fun get(section: String): JSONObject
    fun update(section: String, change: (JSONObject) -> Unit)
    fun remove(section: String)
}

/** Uses the existing Keystore-encrypted atomic generation so all settings participate in encrypted local/WebDAV backup. */
class AndroidDeployStore(context: Context) : VkRelayPersistence {
    private val appContext=context.applicationContext
    override fun verifyCaptcha(url:String,cancelled:()->Boolean)=CarrierManualChallenge.await(appContext,url,cancelled)
    // ProfileStore captures filesDir, not Context. Honor isolated wrappers used by coordinator device fixtures.
    private val preferences=ProfileStore(context,"preferences.enc")
    private fun root(): JSONObject = preferences.read().let { if(it.isEmpty()) JSONObject() else JSONObject(it) }
    private fun read(): JSONObject = root().optJSONObject(AndroidDeployBackupSettings.SECTION) ?: JSONObject()
    private fun write(value: JSONObject) {
        AndroidDeployBackupSettings.validate(value)
        val root=root();root.put(AndroidDeployBackupSettings.SECTION,value);preferences.write(root.toString())
    }
    override fun get(section: String): JSONObject = synchronized(ProfileStore.storageLock) { JSONObject(read().optJSONObject(section)?.toString() ?: "{}") }
    override fun update(section: String,change: (JSONObject) -> Unit) = synchronized(ProfileStore.storageLock) {
        val value=read();val current=value.optJSONObject(section) ?: JSONObject();change(current);value.put(section,current);write(value)
    }
    override fun remove(section: String) = synchronized(ProfileStore.storageLock) { val value=read();value.remove(section);write(value) }
    fun exportForEncryptedBackup(): JSONObject = synchronized(ProfileStore.storageLock) { JSONObject(read().toString()) }
    fun restoreFromEncryptedBackup(value: JSONObject) = synchronized(ProfileStore.storageLock) { write(JSONObject(value.toString())) }
}
