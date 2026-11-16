package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** URLs, subscription snapshots, selected pool and probe results are encrypted together. */
class Connections(context: Context) {
    private val context = context.applicationContext
    private val store = ProfileStore(context, "connections.enc")
    private val snapshots = CatalogueSnapshots()
    private var revision = ""
    private val entries get() = snapshots.current?.entries ?: emptyList()
    fun entries(): List<ServerImport.Server> = entries
    @Synchronized fun load() = synchronized(storageLock) {
        val loadedRevision=store.revision()
        snapshots.replace { CatalogueSnapshot(ConnectionCatalogue(store.read())) }
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
        val before=snapshots.current;load()
        return before==null || !before.sameStructure(snapshots.readable())
    }
    @Synchronized fun add(source: String): Int = transact(checkNewCountries=true) { it.add(source) }
    @Synchronized fun remove(id: String) = transact { it.remove(id) }
    fun subscriptions(): List<JSONObject> = readable().subscriptions()
    fun inviteEntries():List<ServerImport.Server> = readable().inviteEntries()
    @Synchronized fun replaceInvite(imported:List<ServerImport.Server>,versionEdits:Map<String,JSONObject>,newOnlyEdits:Map<String,JSONObject>):Int = transact(checkNewCountries=true) {candidate->
        val previous=candidate.entries().mapTo(hashSetOf()){it.id}
        val count=candidate.replaceInvite(imported)
        versionEdits.forEach {(id,options)->require(!candidate.edited(id)){"Настройки сервера изменились. Повторите синхронизацию."};candidate.edit(id,options)}
        newOnlyEdits.forEach {(id,options)->if(id !in previous&&!candidate.edited(id))candidate.edit(id,options)}
        count
    }
    @Synchronized fun addSubscription(url: String, imported: List<ServerImport.Server>): Int {
        return transact(checkNewCountries=true) { it.replaceSubscription(url, imported); imported.size }
    }
    @Synchronized fun refresh(downloaded: Map<String, List<ServerImport.Server>>) {
        transact(checkNewCountries=true) { candidate -> downloaded.forEach { (url, entries) -> candidate.replaceSubscription(url, entries) } }
    }
    @Synchronized fun importBatch(manual: String, downloaded: Map<String, List<ServerImport.Server>>): Int {
        return transact(checkNewCountries=true) { candidate -> var count = if (manual.trim().isEmpty()) 0 else candidate.add(manual)
            downloaded.forEach { (url, entries) -> candidate.replaceSubscription(url, entries); count += entries.size }; count }
    }
    @Synchronized fun removeSubscription(id: String) = transact { it.removeSubscription(id) }
    @Synchronized fun editSubscription(id: String, url: String, imported: List<ServerImport.Server>) = transact(checkNewCountries=true) { it.editSubscription(id, url, imported) }
    @Synchronized fun editFetched(id: String, url: String, fetched: SubscriptionClient.Downloaded): SubscriptionChanges {
        val changes = transact(checkNewCountries=true) { it.editSubscription(id, url, fetched.servers, fetched.metadata, SubscriptionOptionsStore.load(context).clearOnSuccess) }
        ClientEvents.record(context,"Подписки",changes.summary())
        return changes
    }
    @Synchronized fun importFetched(manual: String, downloaded: Map<String, SubscriptionClient.Downloaded>): Int {
        return importFetched(manual,downloaded,emptyMap())
    }
    @Synchronized fun importFetched(manual:String,downloaded:Map<String,SubscriptionClient.Downloaded>,versionEdits:Map<String,org.json.JSONObject>):Int {
        return importFetched(manual,downloaded,versionEdits,emptyMap())
    }
    @Synchronized fun importFetched(manual:String,downloaded:Map<String,SubscriptionClient.Downloaded>,versionEdits:Map<String,org.json.JSONObject>,newOnlyEdits:Map<String,org.json.JSONObject>):Int {
        return transact(checkNewCountries=true) { candidate ->
            val previous=candidate.entries().mapTo(hashSetOf()) {it.id}
            var count = if (manual.isBlank()) 0 else candidate.add(manual)
            val clear = SubscriptionOptionsStore.load(context).clearOnSuccess
            downloaded.forEach { (url, fetched) -> candidate.replaceSubscription(url, fetched.servers, fetched.metadata, clear); count += fetched.servers.size }
            versionEdits.forEach {(id,options)->
                require(!candidate.edited(id)){"Настройки существующего сервера уже изменены. Повторите импорт, чтобы перечитать его выбранную версию."}
                candidate.edit(id,options)
            }
            newOnlyEdits.forEach {(id,options)->if(id !in previous&&!candidate.edited(id))candidate.edit(id,options)}
            count }
    }
    @Synchronized @JvmOverloads fun refreshFetched(downloaded: Map<String, SubscriptionClient.Downloaded>, expectedIds: Map<String,String>? = null): SubscriptionChanges {
        return transact(checkNewCountries=true) { candidate ->
            candidate.refreshFetched(downloaded, SubscriptionOptionsStore.load(context).clearOnSuccess, expectedIds)
        }
    }
    @Synchronized fun refreshFetchedGuarded(downloaded:Map<String,SubscriptionClient.Downloaded>,expectedIds:Map<String,String>,checkActive:()->Unit):SubscriptionChanges =
        transact(checkActive,checkNewCountries=true) {candidate->
            checkActive()
            candidate.refreshFetched(downloaded,SubscriptionOptionsStore.load(context).clearOnSuccess,expectedIds)
        }
    fun autoIds(): Set<String> = autoIds(snapshots.readable())
    private fun autoIds(snapshot:CatalogueSnapshot):Set<String> = ProfilePolicyRuntime.pool(context,snapshot.ids,snapshot.autoIds)
    @Synchronized fun setAuto(id: String, selected: Boolean) = transact { it.setAuto(id, selected) }
    fun autoExclusions()=readable().autoExclusions()
    @Synchronized fun setAutoExclusions(source:String)=transact {it.setAutoExclusions(source)}
    fun exitTests()=readable().exitTests()
    @Synchronized fun clearExitCountry(id:String)=transact {it.clearExitCountry(id)}
    @Synchronized fun clearExitCountry(entry:ServerImport.Server)=transact {it.clearExitCountry(entry)}
    @Synchronized fun recordExitCountry(id:String,country:String)=transact {it.recordExitCountry(id,country,System.currentTimeMillis())}
    @Synchronized fun recordExitCountry(entry:ServerImport.Server,country:String)=transact {it.recordExitCountry(entry,country,System.currentTimeMillis())}
    fun autoCandidates(input:List<ServerImport.Server>)=readable().autoCandidates(input)
    fun probes(): JSONObject {
        val snapshot=snapshots.readable()
        return if(ClientPrimarySelection.selected(context)=="steer")SteerProbeCache.hints(context,snapshot.entries)else snapshot.catalogue.probes()
    }
    internal fun presentationProbes():JSONObject {
        val snapshot=snapshots.readable()
        val stored=snapshot.catalogue.probes()
        if(ClientPrimarySelection.selected(context)!="steer")return stored
        return SteerProbeCache.hints(context,snapshot.entries).apply {
            snapshot.entries.filter {ProbePlan.independentCarrier(it.carrier)}.forEach {entry->
                if(!has(entry.id))stored.optJSONObject(entry.id)?.let {put(entry.id,it)}
            }
        }
    }
    fun edited(id: String) = readable().edited(id)
    fun warpAccount(id:String)=readable().warpAccount(id)
    @Synchronized @JvmOverloads fun saveWarp(account:String,config:String,previousId:String?=null,receiptId:String?=null)=transact(checkNewCountries=true) {it.saveWarp(account,config,previousId,receiptId)}
    /** Re-read disk so recovery never accepts a cached candidate as commit proof. */
    @Synchronized fun warpReceipt(id:String):JSONObject? = synchronized(storageLock) {load();readable().warpReceipt(id)}
    @Synchronized fun edit(id: String, options: JSONObject?) = transact { it.edit(id, options) }
    /** Migration claim and result share the catalogue lock with edits and restore.
     * No network/package inspection is permitted inside these callbacks. */
    @Synchronized internal fun csqttMigrationClaim(replacement:String,now:Long):CsqttEngineMigration.Ticket? = synchronized(storageLock) {
        val candidate=ConnectionCatalogue(store.read())
        val before=candidate.serialize()
        CsqttEngineMigration.initialize(candidate,replacement,java.util.UUID.randomUUID().toString())
        val ticket=CsqttEngineMigration.claim(candidate,ProfileStore.generation(context),replacement,now)
        if(candidate.serialize()!=before)ProfileStore.withoutHistory {save(candidate)}
        ticket
    }
    @Synchronized internal fun csqttMigrationCurrent(ticket:CsqttEngineMigration.Ticket):Boolean = synchronized(storageLock) {
        CsqttEngineMigration.accepts(ConnectionCatalogue(store.read()),ticket,ProfileStore.generation(context),ticket.replacement)
    }
    @Synchronized internal fun csqttMigrationComplete(ticket:CsqttEngineMigration.Ticket,replacement:String,
        evidence:CsqttEngineMigration.Evidence?,cancelled:()->Boolean):Boolean = synchronized(storageLock) {
        if(cancelled())return@synchronized false
        val candidate=ConnectionCatalogue(store.read());val before=candidate.serialize()
        val committed=CsqttEngineMigration.complete(candidate,ticket,ProfileStore.generation(context),replacement,evidence)
        if(candidate.serialize()!=before) {
            val write={save(candidate) {check(!cancelled()) {"CSQTT_MIGRATION_RETIRED"}}}
            if(committed)write()else ProfileStore.withoutHistory(write)
        }
        committed
    }
    @Synchronized internal fun csqttMigrationNextAt():Long? = synchronized(storageLock) {
        CsqttEngineMigration.nextAt(ConnectionCatalogue(store.read()))
    }
    /** Merge one explicit choice into the latest disk state, not a stale editor snapshot. */
    @Synchronized fun setCaptchaMode(id:String,automatic:Boolean) = transact { candidate ->
        val entry=candidate.entries().firstOrNull {it.id==id} ?: error("Подключение больше недоступно.")
        val options=ConnectionEdits.captcha(entry,automatic)
        candidate.edit(id,options)
    }
    fun share(entry: ServerImport.Server) = ConnectionEdits.share(entry, readable().edited(entry.id))
    @Synchronized fun recordProbes(results: Map<String, Long>) {
        if(results is SteerProbeDelays)return // Already published to its own volatile, module-bound namespace.
        transact { candidate -> val now = System.currentTimeMillis(); val ids = candidate.entries().map { it.id }.toSet()
            results.filterKeys { it in ids }.forEach { (id, delay) -> candidate.recordProbe(id, delay, now) } }
    }
    @Synchronized fun invalidateProbes()=transact {it.clearProbes()}
    @Synchronized @JvmOverloads fun autoEntries(policy: String = AutoSelection.LATENCY): List<ServerImport.Server> {
        val snapshot=snapshots.readable()
        val ids = autoIds(snapshot); val steer=policy==AutoSelection.LATENCY&&ClientPrimarySelection.selected(context)=="steer"
        val probes = if(steer)SteerProbeCache.hints(context,snapshot.entries)else snapshot.catalogue.probes(); val now = System.currentTimeMillis()
        return AutoSelection.proven(snapshot.catalogue.autoCandidates(if(steer)snapshot.entries.filter {SteerAutomaticPool.compatible(it)}else snapshot.entries), ids, probes, now,policy)
    }
    /** Startup has one fresh, storage-bound view of marks and measurements.
     * Health-only proven() remains separate; expiry is not a missing selection. */
    @Synchronized @JvmOverloads fun startupEntries(policy:String=AutoSelection.LATENCY,refresh:Boolean=false):List<ServerImport.Server> = synchronized(storageLock) {
        if(refresh)refreshFromStorage() // Background admission only; UI checks must not decrypt the catalogue.
        val steer=policy==AutoSelection.LATENCY&&ClientPrimarySelection.selected(context)=="steer"
        AutoSelection.startup(readable().autoCandidates(if(steer)entries.filter {SteerAutomaticPool.compatible(it)}else entries),autoIds(),
            if(steer)SteerProbeCache.hints(context,entries)else readable().probes(),System.currentTimeMillis(),policy)
    }
    private fun readable() = snapshots.readable().catalogue
    @Synchronized fun bsStartupEntries():List<ServerImport.Server> =
        AutoSelection.bsStartup(readable().autoCandidates(entries),autoIds(),readable().probes(),System.currentTimeMillis())
    private fun <T> transact(beforeSave:()->Unit={},checkNewCountries:Boolean=false,change: (ConnectionCatalogue) -> T): T = synchronized(storageLock) {
        val candidate = ConnectionCatalogue(store.read())
        val previous=if(checkNewCountries)candidate.entries().mapTo(hashSetOf()) {it.id} else emptySet()
        val before=if(candidate.csqttMigrationState()!=null)ConnectionCatalogue(candidate.serialize())else null
        val result = change(candidate)
        if(before!=null)CsqttEngineMigration.invalidateChanged(before,candidate)
        save(candidate,beforeSave)
        if(checkNewCountries)ServerCountryLookup.enqueue(context,entries.filter {it.id !in previous})
        result
    }
    private fun save(candidate: ConnectionCatalogue,beforeWrite:()->Unit={}) {
        val prepared = CatalogueSnapshot(candidate)
        val serialized=candidate.serialize()
        beforeWrite() // Cancellation before this admission prevents the atomic write; never roll back a started write.
        snapshots.replace { store.write(serialized); prepared };revision=store.revision()
    }
    companion object { private val storageLock = ProfileStore.storageLock }
}
