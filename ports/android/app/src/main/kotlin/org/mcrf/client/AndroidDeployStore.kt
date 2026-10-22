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
    fun inventory():JSONObject = synchronized(ProfileStore.storageLock) {
        val all=read()
        val previous=all.optJSONObject(AndroidDeployInventory.SECTION)?:JSONObject()
        val current=AndroidDeployInventory.fromHistory(previous,all.optJSONObject("deployResults")?:JSONObject())
        if(previous.toString()!=current.toString()){all.put(AndroidDeployInventory.SECTION,current);write(all)}
        JSONObject(current.toString())
    }
    fun renameInventory(kind:String,id:String,name:String) = synchronized(ProfileStore.storageLock) {
        val current=inventory()
        val all=read();all.put(AndroidDeployInventory.SECTION,AndroidDeployInventory.rename(current,kind,id,name))
        if(kind=="instances")all.optJSONObject(AndroidDeployWorkspace.SECTION)?.optJSONObject("drafts")?.optJSONObject(id)?.put("name",AndroidDeployInventory.name(name))
        write(all)
    }
    fun restoreFromEncryptedBackup(value: JSONObject) = synchronized(ProfileStore.storageLock) { write(JSONObject(value.toString())) }
    fun workspace():JSONObject = synchronized(ProfileStore.storageLock) {
        val all=read();val previous=all.optJSONObject(AndroidDeployWorkspace.SECTION)?:JSONObject()
        val next=AndroidDeployWorkspace.migrate(previous,all.optJSONObject("deployDrafts")?:JSONObject())
        if(previous.toString()!=next.toString()){all.put(AndroidDeployWorkspace.SECTION,next);write(all)}
        JSONObject(next.toString())
    }
    fun newDeployment(recipe:String,name:String,values:JSONObject=JSONObject(),panelKey:String=""):String = synchronized(ProfileStore.storageLock) {
        val root=workspace();val id=java.util.UUID.randomUUID().toString()
        root.getJSONObject("drafts").put(id,AndroidDeployWorkspace.record(recipe,name,values,panelKey))
        root.getJSONObject("selected").put(recipe,id)
        val all=read();all.put(AndroidDeployWorkspace.SECTION,root);write(all);id
    }
    fun selectDeployment(recipe:String,id:String) = synchronized(ProfileStore.storageLock) {
        val root=workspace();require(root.getJSONObject("drafts").getJSONObject(id).getString("recipe")==recipe)
        root.getJSONObject("selected").put(recipe,id);val all=read();all.put(AndroidDeployWorkspace.SECTION,root);write(all)
    }
    fun saveDeployment(id:String,name:String,values:JSONObject) = synchronized(ProfileStore.storageLock) {
        val root=workspace();val item=root.getJSONObject("drafts").getJSONObject(id)
        item.put("name",AndroidDeployInventory.name(name)).put("values",JSONObject(values.toString()))
        val all=read();all.put(AndroidDeployWorkspace.SECTION,root)
        all.optJSONObject(AndroidDeployInventory.SECTION)?.optJSONObject("instances")?.optJSONObject(id)?.put("name",AndroidDeployInventory.name(name))
        write(all)
    }
    fun selectedDeployment(recipe:String):String = synchronized(ProfileStore.storageLock) {
        workspace().getJSONObject("selected").optString(recipe).ifBlank {newDeployment(recipe,recipe+" · новая установка")}
    }
    fun completeDeployment(id:String,operationId:String,resultKey:String) = synchronized(ProfileStore.storageLock) {
        if(id.isNotEmpty()){
            val root=workspace();val item=root.getJSONObject("drafts").getJSONObject(id)
            val draft=item.getJSONObject("values")
            if(draft.optString("operationId")==operationId)draft.put("operationId",java.util.UUID.randomUUID().toString())
            item.put("panelResultKey",resultKey)
            val all=read();all.put(AndroidDeployWorkspace.SECTION,root);write(all)
        }
    }
    fun saveCredentialsFor(entry:AndroidDeployHistory.Entry,credentials:JSONObject) = synchronized(ProfileStore.storageLock) {
        val keys=setOf("sshAuth","sshPassword","privateKey","keyPassword")
        require(credentials.keys().asSequence().toSet()==keys)
        val keyed=credentials.getString("sshAuth").also {require(it in setOf("password","key"))}=="key"
        require(credentials.getString("sshPassword").length<=8192&&credentials.getString("privateKey").length<=65536&&credentials.getString("keyPassword").length<=8192)
        require(if(keyed)credentials.getString("privateKey").isNotBlank() else credentials.getString("sshPassword").isNotEmpty()) {"Заполните выбранный способ входа SSH"}
        val template=entry.result.getJSONObject("transport_template")
        fun matches(value:JSONObject)=value.optString("host")==template.getString("host")&&value.optString("sshPort","22")==template.getString("sshPort")&&value.optString("user","root")==template.getString("user")
        val all=read();val id=entry.result.optString("deployment_id")
        if(id.isNotEmpty()){
            val workspace=workspace();val values=workspace.getJSONObject("drafts").getJSONObject(id).getJSONObject("values")
            require(matches(values)){"Сохранённый доступ относится к другому серверу"}
            keys.forEach {values.put(it,credentials.getString(it))}
            all.put(AndroidDeployWorkspace.SECTION,workspace)
        }else{
            val drafts=all.optJSONObject("deployDrafts")?:JSONObject().also {all.put("deployDrafts",it)}
            val values=drafts.optJSONObject(entry.recipe)?:JSONObject(template.toString())
            require(matches(values)){"Не перезаписываем доступ другой установки"}
            keys.forEach {values.put(it,credentials.getString(it))};drafts.put(entry.recipe,values)
        }
        write(all)
    }
    fun saveServerCredentials(serverId:String,entry:AndroidDeployHistory.Entry,credentials:JSONObject) = synchronized(ProfileStore.storageLock) {
        val keys=setOf("sshAuth","sshPassword","privateKey","keyPassword")
        require(credentials.keys().asSequence().toSet()==keys)
        val keyed=credentials.getString("sshAuth").also {require(it in setOf("password","key"))}=="key"
        require(credentials.getString("sshPassword").length<=8192&&credentials.getString("privateKey").length<=65536&&credentials.getString("keyPassword").length<=8192)
        require(if(keyed)credentials.getString("privateKey").isNotBlank()else credentials.getString("sshPassword").isNotEmpty()) {"Заполните выбранный способ входа SSH"}
        val server=inventory().getJSONObject("servers").getJSONObject(serverId)
        val selected=entry.result.getJSONObject("transport_template")
        val user=selected.getString("user")
        fun matches(value:JSONObject)=value.optString("host").equals(server.getString("host"),true)&&
            value.optString("sshPort","22").toIntOrNull()==server.getInt("sshPort")&&value.optString("user","root")==user
        require(matches(selected)){"Сохранённая установка относится к другому серверу"}
        val all=read();val workspace=workspace();val drafts=workspace.getJSONObject("drafts")
        val legacy=all.optJSONObject("deployDrafts")?:JSONObject()
        val entries=AndroidDeployHistory.entries(all.optJSONObject("deployResults")?:JSONObject())
            .filter {it.result.optJSONObject("transport_template")?.let(::matches)==true}
        require(entries.any {it.key==entry.key}){"Установка больше не сохранена"}
        entries.forEach {current->
            val id=current.result.optString("deployment_id")
            val values=if(id.isNotEmpty())drafts.getJSONObject(id).getJSONObject("values")
                else legacy.optJSONObject(current.recipe)?:JSONObject(current.result.getJSONObject("transport_template").toString()).also {legacy.put(current.recipe,it)}
            require(matches(values)){"Доступ другой установки не изменён: адрес SSH не совпадает"}
            keys.forEach {values.put(it,credentials.getString(it))}
        }
        all.put(AndroidDeployWorkspace.SECTION,workspace)
        if(legacy.length()>0)all.put("deployDrafts",legacy)
        write(all)
    }
    fun credentialsFor(entry:AndroidDeployHistory.Entry):JSONObject = synchronized(ProfileStore.storageLock) {
        val template=entry.result.optJSONObject("transport_template")?:return@synchronized JSONObject()
        val id=entry.result.optString("deployment_id")
        val named=workspace().getJSONObject("drafts").optJSONObject(id)?.optJSONObject("values")
        val legacy=read().optJSONObject("deployDrafts")?.optJSONObject(entry.recipe)
        fun matches(d:JSONObject)=d.optString("host")==template.optString("host")&&d.optString("sshPort","22")==template.optString("sshPort")&&d.optString("user","root")==template.optString("user")
        val selected=if(id.isNotEmpty())named?.takeIf(::matches) else legacy?.takeIf(::matches)
        JSONObject((selected?:JSONObject()).toString())
    }
}
