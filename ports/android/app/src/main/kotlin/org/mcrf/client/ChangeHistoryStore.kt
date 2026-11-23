package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Implementations must be local and encrypted. Plaintext is never a fallback. */
interface LocalDiagnosticStorage {
    fun read(): String
    fun write(value: String)
    fun delete()
}

/** Encrypted restore snapshots; presentation exposes only safe labels, never credentials. */
class ChangeHistoryStore(
    private val storage: LocalDiagnosticStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxCount: Int = MAX_COUNT,
    private val maxBytes: Int = MAX_BYTES,
    private val lock: Any = Any()
) {
    data class Summary(val id: String, val timestamp: Long, val changedStores: List<String>,
                       val changes:List<String> = emptyList(),val restoreChanges:List<String> = emptyList())
    // Approval is tied to this immutable version and the current configuration, never a bare boolean.
    class RestoreRequest internal constructor(val id: String, internal val digest: String, internal val currentDigest: String,
                                              internal val preview:ChangePreview, internal val undo:Boolean = false) {
        override fun toString()="RestoreRequest(values hidden)"
    }
    private data class Entry(val id: String, val at: Long, val values: Map<String, String>)
    init { require(maxCount in 1..MAX_COUNT && maxBytes in 128..MAX_BYTES) }

    fun checkpoint(values: Map<String, String>): Boolean = checkpointIfCurrent(values) {true}
    internal fun checkpointIfCurrent(values:Map<String,String>,stillCurrent:()->Boolean):Boolean {
        require(values.keys == STORE_NAMES)
        while(true) {
            val raw=readRaw()
            val encoded=prepareCheckpoint(raw,values)?:return false
            val saved=synchronized(lock) {
                if(!stillCurrent())return false
                if(storage.read()!=raw)false else {if(encoded!=raw)storage.write(encoded);true}
            }
            if(saved)return true
        }
    }
    private fun prepareCheckpoint(raw:String,values:Map<String,String>,undo:Entry?=decodeUndo(raw)):String? {
        val entries=decodeRaw(raw).toMutableList()
        if(entries.lastOrNull()?.values?.let {same(it,values)}==true && undo==decodeUndo(raw))return raw
        val entry = Entry(UUID.randomUUID().toString(), clock(), values.toMap())
        if (size(encode(emptyList(),undo)) > maxBytes || size(encode(listOf(entry))) > maxBytes) return null
        entries.add(entry)
        while (entries.size > maxCount) entries.removeAt(0)
        var encoded = encode(entries,undo)
        // The undo point is pinned. Ordinary history retention must never evict it.
        while (size(encoded) > maxBytes && entries.isNotEmpty()) { entries.removeAt(0); encoded = encode(entries,undo) }
        return encoded
    }
    /** Only capture encrypted bytes under the storage lock. JSON/diff work must not block writers. */
    fun summaries(current: Map<String, String>): List<Summary> {
        val entries=load()
        return entries.mapIndexed {index,entry->Summary(entry.id,entry.at,STORE_NAMES.sorted().filter {
            !equivalent(it,entry.values.getValue(it),current[it].orEmpty())
        },ChangeHistoryDiff.describe(entry.values,entries.getOrNull(index+1)?.values?:current),
            ChangeHistoryDiff.describe(current,entry.values))}.filter {it.changes.isNotEmpty()}.asReversed()
    }
    fun prepareRestore(id: String, current: Map<String, String>): RestoreRequest {
        val entry = load().firstOrNull { it.id == id } ?: error("Снимок больше не доступен.")
        return RestoreRequest(id, digest(entry.values), digest(current),ChangePreview.prepare(current,entry.values))
    }
    fun hasUndo():Boolean = decodeUndo(readRaw())!=null
    fun prepareUndo(current:Map<String,String>):RestoreRequest {
        val entry=decodeUndo(readRaw())?:error("Обратная точка восстановления отсутствует.")
        return RestoreRequest(entry.id,digest(entry.values),digest(current),ChangePreview.prepare(current,entry.values),true)
    }
    private fun find(request:RestoreRequest,raw:String):Entry =
        (if(request.undo)decodeUndo(raw)?.takeIf {it.id==request.id} else decodeRaw(raw).firstOrNull {it.id==request.id})
            ?:error("Снимок больше не доступен.")
    fun validateRestore(request:RestoreRequest,current:()->Map<String,String>,validate:(Map<String,String>)->Unit) {
        val entry=find(request,readRaw())
        require(digest(entry.values)==request.digest&&digest(current())==request.currentDigest) {"Настройки изменились. Повторите проверку."}
        request.preview.checkCurrent(current())
        validate(entry.values.toMap())
        require(digest(current())==request.currentDigest) {"Настройки изменились во время проверки."}
        require(digest(find(request,readRaw()).values)==request.digest) {"Снимок больше не доступен."}
    }
    /** The UI must present the summary and call confirm only from its explicit confirmation action.
     * Heavy validation is detached; recheck approval and commit atomically while writers are excluded. */
    fun restore(request: RestoreRequest, current: () -> Map<String, String>,
                confirm: (Summary) -> Boolean, validate: (Map<String, String>) -> Unit,
                commit: (Map<String, String>) -> Unit): Boolean {
        val raw=readRaw()
        val entry = find(request,raw)
        val before = current()
        require(digest(entry.values) == request.digest && digest(before) == request.currentDigest) {
            "Настройки изменились. Откройте подтверждение заново."
        }
        val summary = Summary(entry.id, entry.at, STORE_NAMES.sorted().filter { !equivalent(it, entry.values.getValue(it), before[it].orEmpty()) })
        if (!confirm(summary)) return false
        request.preview.checkCurrent(before)
        validate(entry.values.toMap())
        val undo=Entry(UUID.randomUUID().toString(),clock(),before.toMap())
        val safety=prepareCheckpoint(raw,before,undo)?:error("Текущий набор слишком велик для безопасного отката.")
        return synchronized(lock) {
            require(storage.read()==raw) {"История изменилась. Повторите проверку."}
            request.preview.apply(current,{}) {snapshot->
                // One durable safety snapshot; approval is still bound to exactly these live bytes.
                if(safety!=raw)storage.write(safety)
                check(storage.read()==safety && decodeUndo(safety)?.values==before) {
                    "Не удалось сохранить обратную точку. Восстановление отменено."
                }
                try { commit(snapshot) } catch(error:Exception) {
                    // A failed undo must remain retryable. Only revert its history
                    // publication when the live configuration is provably unchanged.
                    // An uncertain/partial commit keeps the new safety point intact.
                    if(request.undo)try {
                        if(digest(current())==request.currentDigest && storage.read()==safety)storage.write(raw)
                    }catch(historyError:Exception){error.addSuppressed(historyError)}
                    throw error
                }
            }
            true
        }
    }
    fun delete() = synchronized(lock) { storage.delete() }
    private fun readRaw():String = synchronized(lock) {storage.read()}
    private fun load(): List<Entry> = decodeRaw(readRaw())
    private fun decodeRaw(raw:String):List<Entry> {
        if (raw.isEmpty()) return emptyList()
        require(size(raw) <= maxBytes) { "История больше локального лимита." }
        return try { decode(raw) } catch (error: org.json.JSONException) {
            throw IllegalStateException("Повреждена локальная история. Исходные данные сохранены.", error)
        }
    }
    private fun decode(raw: String): List<Entry> {
        CompatibilityImportFormats.validateJsonDepth(raw)
        val document = JSONObject(raw)
        require(document.getInt("version") == 1)
        val array = document.getJSONArray("entries")
        require(array.length() <= maxCount)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val values = item.getJSONObject("stores").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
            require(values.keys == STORE_NAMES)
            values.values.filter {it.trimStart().startsWith('{')||it.trimStart().startsWith('[')}.forEach(CompatibilityImportFormats::validateJsonDepth)
            Entry(item.getString("id"), item.getLong("at"), values)
        }
    }
    private fun decodeUndo(raw:String):Entry? {
        if(raw.isEmpty())return null
        decodeRaw(raw) // Same size, depth and schema gates as ordinary entries.
        val root=JSONObject(raw)
        require(!root.has("undo") || root.optJSONObject("undo")!=null) {"Повреждена обратная точка восстановления."}
        val item=root.optJSONObject("undo")?:return null
        val obj=item.getJSONObject("stores")
        val values=obj.keys().asSequence().associateWith {obj.getString(it)}
        require(values.keys==STORE_NAMES) {"Повреждена обратная точка восстановления."}
        values.values.forEach(CompatibilityImportFormats::validateJsonDepth)
        return Entry(item.getString("id"),item.getLong("at"),values)
    }
    private fun encode(entries: List<Entry>,undo:Entry?=null): String {
        fun json(entry:Entry)=JSONObject().put("id",entry.id).put("at",entry.at).put("stores",JSONObject(entry.values as Map<*,*>))
        return JSONObject().put("version",1).put("entries",JSONArray().apply {entries.forEach {put(json(it))}})
            .apply {if(undo!=null)put("undo",json(undo))}.toString()
    }
    companion object {
        const val MAX_COUNT = 12
        const val MAX_BYTES = 2 * 1024 * 1024
        const val PRIVACY = "История хранится только на устройстве, зашифрована: до 12 снимков и 2 МиБ (плюс служебные байты шифрования). Старые снимки удаляются автоматически. Значения и секреты не показываются."
        val STORE_NAMES = setOf("profile.enc", "connections.enc", "routing.enc", "preferences.enc")
        internal fun size(value: String) = value.toByteArray(Charsets.UTF_8).size
        private fun canonical(value: Any?): String = when(value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        private fun normalized(value: String) = try {
            when { value.trimStart().startsWith("{") -> canonical(JSONObject(value))
                value.trimStart().startsWith("[") -> canonical(JSONArray(value)); else -> value }
        } catch (_: Exception) { value }
        internal fun equivalent(a: String, b: String) = normalized(a) == normalized(b)
        /** Probe results, refresh time and last runtime target are observations, not configuration edits. */
        internal fun equivalent(name: String, a: String, b: String):Boolean {
            val pair=comparable(name,a,b);return equivalent(pair.first,pair.second)
        }
        internal fun comparable(name:String,a:String,b:String):Pair<String,String> {
            val random=name=="preferences.enc"&&listOf(a,b).any {runCatching {JSONObject(it).optBoolean(RandomAppearance.KEY)}.getOrDefault(false)}
            var left=meaningful(name,a,random);var right=meaningful(name,b,random)
            if(name=="preferences.enc"&&runCatching {JSONObject(a).optString("selected")!=JSONObject(b).optString("selected")}.getOrDefault(false)) {
                left=JSONObject(left.ifEmpty {"{}"}).apply {remove("automatic")}.toString()
                right=JSONObject(right.ifEmpty {"{}"}).apply {remove("automatic")}.toString()
            }
            return left to right
        }
        /** History is a data projection, never a runtime compiler or GeoData/native validator.
         * Changing the open group/mode merely moves the same bank between root and inactive data. */
        private fun routingForHistory(source:String):String {
            val root=JSONObject(source)
            val fields=setOf("rules","defaultTarget","excludedApps","vpnApps","appMode","geoData","remoteRouting",RoutingGroups.KEY,RoutingConnectionBinding.KEY)
            val banks=root.optJSONObject(ExperienceRouting.KEY)
            return JSONObject().also {out->for(mode in ExperienceMode.entries) {
                val data=JSONObject(root.toString()).apply {remove(ExperienceRouting.KEY);remove(LegacyRoutingMigration.KEY)}
                if(banks!=null&&banks.optString("active")!=mode.id) {
                    fields.forEach(data::remove)
                    banks.optJSONObject(mode.id)?.let {bank->bank.keys().forEach {data.put(it,bank.get(it))}}
                } else if(banks==null&&mode.id=="simple") {
                    fields.forEach(data::remove)
                }
                if(!data.has("rules"))data.put("rules",JSONArray())
                if(!data.has("defaultTarget"))data.put("defaultTarget","proxy")
                if(!data.has("excludedApps"))data.put("excludedApps",JSONArray())
                if(!data.has("vpnApps"))data.put("vpnApps",JSONArray())
                if(!data.has("appMode"))data.put("appMode","all")
                val groups=data.optJSONObject(RoutingGroups.KEY)
                if(groups!=null) {
                    val active=groups.optString("active")
                    val list=groups.optJSONArray("groups")?:JSONArray()
                    repeat(list.length()) {i->val group=list.getJSONObject(i)
                        if(group.optString("id")==active)group.put("data",JSONObject().also {snapshot->
                            for(key in listOf("rules","defaultTarget","geoData","remoteRouting"))if(data.has(key))snapshot.put(key,data.get(key))
                        })
                        normalizeRules(group.optJSONObject("data")?:JSONObject())
                    }
                    groups.remove("active")
                    listOf("rules","defaultTarget","geoData","remoteRouting").forEach(data::remove)
                } else normalizeRules(data)
                out.put(mode.id,data)
            }}.toString()
        }
        private fun normalizeRules(data:JSONObject) {
            data.optJSONArray("rules")?.let {rules->repeat(rules.length()) {i->rules.optJSONObject(i)?.let {rules.put(i,TextGeoRules.editable(it))}}}
        }
        private fun meaningful(name: String, source: String,random:Boolean): String = try {
            if(name=="routing.enc"&&source.isNotEmpty()) {
                routingForHistory(source)
            } else if (name !in setOf("connections.enc", "preferences.enc") || source.isEmpty()) source else JSONObject(source).apply {
                if(name == "connections.enc") {
                    remove("probes"); remove("warpCommitReceipts"); remove("csqttUnifiedMigration") // Internal retry journal, not user settings.
                    optJSONArray("subscriptions")?.let { rows -> repeat(rows.length()) { rows.optJSONObject(it)?.remove("updatedAt") } }
                } else {
                    for(key in setOf("autoVpnReturn","selected","bsLastManualVpn","bsLastManualBs","wantedRunning","restoreAppliedGeneration","pendingPrimaryInstallKind",
                        "logsTerminal","logsFilter","lastPage","currentPage","settingsPage","scrollY"))remove(key)
                    optJSONObject("inviteCode")?.let {it.remove("lastSync");it.remove("lastCount")}
                    if(random)for(key in UserThemeColors.keys+listOf(PatternWallpaper.KEY,ConnectionAppearance.STYLE,ConnectionAppearance.EXIT))remove(key)
                    if(!optBoolean(RandomAppearance.KEY))remove(RandomAppearance.KEY)
                }
            }.toString()
        } catch (_: Exception) { source }
        internal fun selectionProfile(a:Map<String,String>,b:Map<String,String>):Boolean=runCatching {
            val before=JSONObject(a["preferences.enc"].orEmpty().ifEmpty {"{}"})
            val after=JSONObject(b["preferences.enc"].orEmpty().ifEmpty {"{}"})
            before.optBoolean("catalogueProfile")&&after.optBoolean("catalogueProfile")
        }.getOrDefault(false)
        private fun same(a: Map<String, String>, b: Map<String, String>) = a.keys == b.keys && a.all {
            it.key=="profile.enc"&&selectionProfile(a,b)||equivalent(it.key, it.value, b.getValue(it.key))
        }
        private fun digest(values: Map<String, String>): String = MessageDigest.getInstance("SHA-256")
            .digest(canonical(JSONObject(values.mapValues { normalized(it.value) } as Map<*, *>)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun encrypted(context: Context, name: String): LocalDiagnosticStorage {
            val store = ProfileStore(context.applicationContext, name)
            return object : LocalDiagnosticStorage {
                override fun read() = store.read()
                override fun write(value: String) { store.write(value) }
                override fun delete() { store.deleteLocal() }
            }
        }
        fun local(context: Context) = ChangeHistoryStore(encrypted(context, "change-history.enc"), lock = ProfileStore.storageLock).also {
            ChangeHistoryRecorder.observe(context,it)
        }
        /** Strict structural/assets/native validation without changing live settings or starting a VPN. */
        fun validateLocal(context: Context, values: Map<String, String>) {
            InviteCodeSync.registerBackup()
            AndroidBackup.registerPreference(RandomAppearance.KEY,false)
            requireCompleteSnapshot(values)
            values.values.forEach { require(size(it) <= ServerImport.MAX_BYTES); CompatibilityImportFormats.validateJsonDepth(it) }
            ConnectionCatalogue(values.getValue("connections.enc")).serialize()
            values.getValue("routing.enc").takeIf { it.isNotEmpty() }?.let {
                val routing = JSONObject(it); RoutingStore.validate(routing)
                for(mode in ExperienceMode.entries)PresetAssets.validate(context,ExperienceRouting.project(routing,mode))
            }
            val preferences = JSONObject(values.getValue("preferences.enc").ifEmpty { "{}" })
            AndroidBackup.validatePreferences(InviteCodeSync.historyPreferences(preferences))
            values.getValue("profile.enc").takeIf { it.isNotEmpty() }?.let {
                val vpn = preferences.optBoolean("tun", true)
                HistoryNativeValidation.validate(context,values,preferences,vpn)
            }
        }
        /** Legacy pre-save checkpoints used empty strings for uninitialized stores. Restoring
         * those bytes resets the catalogue/routes on next startup instead of restoring them. */
        internal fun requireCompleteSnapshot(values:Map<String,String>) {
            require(values.keys==STORE_NAMES) {"Неполный набор настроек в снимке."}
            require(values.getValue("connections.enc").isNotBlank() && values.getValue("routing.enc").isNotBlank()) {
                "Снимок не содержит полного набора подключений и маршрутов. Выберите другую точку."
            }
        }
        fun restoreLocal(context: Context, request: RestoreRequest, confirm: (Summary) -> Boolean): Boolean {
            return local(context).restore(request, { snapshotLocal(context) }, confirm,
                { values->try {validateLocal(context,values)}catch(_:Exception){
                    throw IllegalArgumentException("Проверка снимка не пройдена. Проверьте сохранённые настройки и установленные движки.")
                } }, { values -> ProfileStore.withoutHistory { ProfileStore.replaceAll(context, values) } })
        }
        internal fun snapshotLocal(context:Context):Map<String,String> = synchronized(ProfileStore.storageLock) {
            ProfileStore.historySnapshot(context)
        }
    }
}
