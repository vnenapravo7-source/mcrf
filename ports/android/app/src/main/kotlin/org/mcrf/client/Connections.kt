package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.Collections

/** URLs, subscription snapshots, selected pool and probe results are encrypted together. */
class Connections(context: Context) {
    private val context = context.applicationContext
    private val store = ProfileStore(context, "connections.enc")
    private var data: ConnectionCatalogue? = null
    private var revision = ""
    @Volatile private var entries: List<ServerImport.Server> = emptyList()
    fun entries(): List<ServerImport.Server> = entries
    @Synchronized fun load() = synchronized(storageLock) {
        data = null
        val loadedRevision=store.revision()
        val loaded = ConnectionCatalogue(store.read())
        entries = Collections.unmodifiableList(loaded.entries())
        data = loaded
        revision=loadedRevision
    }
    /** Other owners may update health checks in the same encrypted catalogue. */
    @Synchronized fun refreshFromStorage(): Boolean {
        if(store.revision()==revision)return false
        load();return true
    }
    /** Metadata-only refreshes update existing rows; structural edits still rebuild the page. */
    @Synchronized fun refreshPresentationFromStorage():Boolean {
        if(store.revision()==revision)return false
        fun structure():JSONObject=JSONObject(readable().serialize()).apply {remove("probes");remove("autoServers")}
        val before=structure();load()
        return !RoutingDefaultsEdits.equivalent(before,structure())
    }
    @Synchronized fun add(source: String): Int = transact { it.add(source) }
    @Synchronized fun remove(id: String) = transact { it.remove(id) }
    @Synchronized fun subscriptions(): List<JSONObject> = readable().subscriptions()
    @Synchronized fun addSubscription(url: String, imported: List<ServerImport.Server>): Int {
        return transact { it.replaceSubscription(url, imported); imported.size }
    }
    @Synchronized fun refresh(downloaded: Map<String, List<ServerImport.Server>>) {
        transact { candidate -> downloaded.forEach { (url, entries) -> candidate.replaceSubscription(url, entries) } }
    }
    @Synchronized fun importBatch(manual: String, downloaded: Map<String, List<ServerImport.Server>>): Int {
        return transact { candidate -> var count = if (manual.trim().isEmpty()) 0 else candidate.add(manual)
            downloaded.forEach { (url, entries) -> candidate.replaceSubscription(url, entries); count += entries.size }; count }
    }
    @Synchronized fun removeSubscription(id: String) = transact { it.removeSubscription(id) }
    @Synchronized fun editSubscription(id: String, url: String, imported: List<ServerImport.Server>) = transact { it.editSubscription(id, url, imported) }
    @Synchronized fun editFetched(id: String, url: String, fetched: SubscriptionClient.Downloaded): SubscriptionChanges {
        val changes = transact { it.editSubscription(id, url, fetched.servers, fetched.metadata, SubscriptionOptionsStore.load(context).clearOnSuccess) }
        ClientEvents.record(context,"Подписки",changes.summary())
        return changes
    }
    @Synchronized fun importFetched(manual: String, downloaded: Map<String, SubscriptionClient.Downloaded>): Int {
        return transact { candidate -> var count = if (manual.isBlank()) 0 else candidate.add(manual)
            val clear = SubscriptionOptionsStore.load(context).clearOnSuccess
            downloaded.forEach { (url, fetched) -> candidate.replaceSubscription(url, fetched.servers, fetched.metadata, clear); count += fetched.servers.size }; count }
    }
    @Synchronized @JvmOverloads fun refreshFetched(downloaded: Map<String, SubscriptionClient.Downloaded>, expectedIds: Map<String,String>? = null): SubscriptionChanges {
        return transact { candidate ->
            candidate.refreshFetched(downloaded, SubscriptionOptionsStore.load(context).clearOnSuccess, expectedIds)
        }
    }
    @Synchronized fun refreshFetchedGuarded(downloaded:Map<String,SubscriptionClient.Downloaded>,expectedIds:Map<String,String>,checkActive:()->Unit):SubscriptionChanges =
        transact(checkActive) {candidate->
            checkActive()
            candidate.refreshFetched(downloaded,SubscriptionOptionsStore.load(context).clearOnSuccess,expectedIds)
        }
    @Synchronized fun autoIds(): Set<String> = readable().autoIds()
    @Synchronized fun setAuto(id: String, selected: Boolean) = transact { it.setAuto(id, selected) }
    @Synchronized fun probes(): JSONObject = readable().probes()
    @Synchronized fun edited(id: String) = readable().edited(id)
    @Synchronized fun warpAccount(id:String)=readable().warpAccount(id)
    @Synchronized @JvmOverloads fun saveWarp(account:String,config:String,previousId:String?=null,receiptId:String?=null)=transact {it.saveWarp(account,config,previousId,receiptId)}
    /** Re-read disk so recovery never accepts a cached candidate as commit proof. */
    @Synchronized fun warpReceipt(id:String):JSONObject? = synchronized(storageLock) {load();readable().warpReceipt(id)}
    @Synchronized fun edit(id: String, options: JSONObject?) = transact { it.edit(id, options) }
    /** Merge one explicit choice into the latest disk state, not a stale editor snapshot. */
    @Synchronized fun setCaptchaMode(id:String,automatic:Boolean) = transact { candidate ->
        val entry=candidate.entries().firstOrNull {it.id==id} ?: error("Подключение больше недоступно.")
        val options=ConnectionEdits.captcha(entry,automatic)
        candidate.edit(id,options)
    }
    @Synchronized fun share(entry: ServerImport.Server) = ConnectionEdits.share(entry, readable().edited(entry.id))
    @Synchronized fun recordProbes(results: Map<String, Long>) {
        transact { candidate -> val now = System.currentTimeMillis(); val ids = candidate.entries().map { it.id }.toSet()
            results.filterKeys { it in ids }.forEach { (id, delay) -> candidate.recordProbe(id, delay, now) } }
    }
    @Synchronized @JvmOverloads fun autoEntries(policy: String = AutoSelection.LATENCY): List<ServerImport.Server> {
        val ids = readable().autoIds(); val probes = readable().probes(); val now = System.currentTimeMillis()
        return AutoSelection.proven(entries, ids, probes, now,policy)
    }
    /** Startup has one fresh, storage-bound view of marks and measurements.
     * Health-only proven() remains separate; expiry is not a missing selection. */
    @Synchronized @JvmOverloads fun startupEntries(policy:String=AutoSelection.LATENCY,refresh:Boolean=false):List<ServerImport.Server> = synchronized(storageLock) {
        if(refresh)refreshFromStorage() // Background admission only; UI checks must not decrypt the catalogue.
        AutoSelection.startup(entries,readable().autoIds(),readable().probes(),System.currentTimeMillis(),policy)
    }
    private fun readable() = data ?: throw IllegalStateException("Не удалось прочитать подключения. Исходные данные сохранены.")
    @Synchronized fun bsStartupEntries():List<ServerImport.Server> =
        AutoSelection.bsStartup(entries,readable().autoIds(),readable().probes(),System.currentTimeMillis())
    private fun <T> transact(beforeSave:()->Unit={},change: (ConnectionCatalogue) -> T): T = synchronized(storageLock) {
        val candidate = ConnectionCatalogue(store.read())
        val result = change(candidate); save(candidate,beforeSave); result
    }
    private fun save(candidate: ConnectionCatalogue,beforeWrite:()->Unit={}) {
        val parsed = Collections.unmodifiableList(candidate.entries())
        val serialized=candidate.serialize()
        beforeWrite() // Cancellation before this admission prevents the atomic write; never roll back a started write.
        store.write(serialized); data = candidate; entries = parsed;revision=store.revision()
    }
    companion object { private val storageLock = ProfileStore.storageLock }
}
