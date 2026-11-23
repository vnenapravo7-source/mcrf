package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

class RoutingStore(private val context: Context) {
    private val store = ProfileStore(context, "routing.enc")
    private var state: JSONObject? = null
    private var diskRevision = ""
    private var diskSource = ""
    private var presentationEpoch=0L
    private var snapshotMode:ExperienceMode?=null
    @Volatile internal var conflicts:Pair<String,RoutingConflictAnalyzer.Report>?=null
    internal val presentationCache=RoutingPresentationCache<RoutingPresentation>()
    @Synchronized internal fun presentationRevision():String { reconcileTemporary();return "${store.revision()}:$presentationEpoch:${ExperienceModeStore(context).load().id}" }
    @Synchronized internal fun presentationSnapshot():Pair<String,JSONObject> = synchronized(ProfileStore.storageLock) {
        val copy=snapshot()
        presentationRevision() to copy
    }
    @Synchronized fun generation():String = "${ProfileStore.generation(context)}:${ExperienceModeStore(context).load().id}"
    @Synchronized fun snapshotWithGeneration():Pair<JSONObject,String> = synchronized(ProfileStore.storageLock) {snapshot() to generation()}
    @Synchronized fun editInGeneration(expected:String, edit:(JSONObject)->Unit):JSONObject = synchronized(ProfileStore.storageLock) {
        require(generation()==expected){"Данные восстановлены или режим изменился. Открой настройки заново; старое окно не изменило их."}
        val next=snapshot();edit(next);save(next);next
    }
    @Synchronized internal fun editAtRevision(expectedRevision:String,expectedGeneration:String,edit:(JSONObject)->Unit):Pair<JSONObject,String> = synchronized(ProfileStore.storageLock) {
        require(presentationRevision()==expectedRevision){"Маршруты изменились. Повтори действие или проверку конфликтов."}
        editInGeneration(expectedGeneration,edit) to presentationRevision()
    }
    private fun loadTiming(enabled: Boolean, stage: String, started: Long) {
        if (!BuildConfig.DEBUG || !enabled) return
        val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        runCatching { android.util.Log.d("MCRF_STARTUP", "routing_load.$stage=${elapsed}ms") }
    }
    private inline fun <T> loadStage(enabled: Boolean, stage: String, action: () -> T): T {
        if (!BuildConfig.DEBUG || !enabled) return action()
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        try { return action() } finally { loadTiming(enabled, stage, started) }
    }
    @Synchronized fun load() {
        val timing = BuildConfig.DEBUG && loadTraceCount.getAndIncrement() < 8
        conflicts=null
        presentationEpoch++;presentationCache.clear()
        state = null
        // Parse outside the storage lock, but never stamp an old document with
        // a newer writer's revision. snapshot() can then detect any intervening save.
        val readQueued = if (timing) android.os.SystemClock.elapsedRealtimeNanos() else 0L
        val (source,loadedRevision)=synchronized(ProfileStore.storageLock){
            loadTiming(timing,"storage_wait",readQueued)
            loadStage(timing,"read"){store.read() to store.revision()}
        }
        val candidate = loadStage(timing,"parse") { if (source.isEmpty()) {
            val previous = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("dns", "Cloudflare")
            // .bin keeps AAPT from implicitly decompressing and renaming a .gz asset.
            val text = GZIPInputStream(context.assets.open("russia-routes.bin")).use { String(ImportIO.read(it), Charsets.UTF_8) }
            val parsed = RouteRules.parse(text)
            require(parsed.getValue("ip_cidr").size >= 500 && parsed.getValue("domain_suffix").size >= 100) { "Неполный список РФ" }
            initialSettings(text,previous)
        } else JSONObject(source) }
        val normalizeStarted = if (timing) android.os.SystemClock.elapsedRealtimeNanos() else 0L
        val retired=TemporaryRules.removeLegacy(candidate)
        val retiredDns=DnsConfig.retireLegacyPolicies(candidate)
        val migrate=if(candidate.optInt(LegacyRoutingMigration.KEY,0)<1){
            val countryText=GZIPInputStream(context.assets.open("russia-routes.bin")).use {String(ImportIO.read(it),Charsets.UTF_8)}
            LegacyRoutingMigration.apply(candidate,countryText)
        }else false
        if (!candidate.has("vpnApps")) candidate.put("vpnApps", JSONArray())
        val countryMigrated=LegacyRoutingMigration.replaceCountryRules(candidate)
        RoutingGroups.ensure(candidate)
        RoutingGroups.synchronize(candidate)
        ExperienceRouting.ensure(candidate)
        loadTiming(timing,"normalize",normalizeStarted)
        loadStage(timing,"validate"){validate(candidate)}
        loadStage(timing,"assets"){validateAssets(candidate)}
        state = candidate; diskRevision = loadedRevision;diskSource = source
        if(migrate||retired||retiredDns||countryMigrated)synchronized(ProfileStore.storageLock){
            if(store.revision()!=loadedRevision||store.read()!=source){load();return}
            val content=candidate.toString();store.write(content);diskRevision=store.revision();diskSource=content
        }
        loadStage(timing,"temporary"){reconcileTemporary()}
        loadStage(timing,"schedule"){TemporaryRulesRuntime.schedule(context,requireNotNull(state))}
    }
    /** Base rules never contain temporary edits. Pruning removes only the lease. */
    @Synchronized private fun reconcileTemporary() {
        val current=state?:return
        if(diskRevision!=store.revision()){load();return}
        val now=TemporaryRulesRuntime.clock(context)
        if(TemporaryRules.rows(current).none {TemporaryRules.expired(it,now)})return
        synchronized(ProfileStore.storageLock) {
            if(diskRevision!=store.revision()||diskSource!=store.read()){load();return}
            val next=JSONObject(current.toString())
            if(TemporaryRules.reconcile(next,TemporaryRulesRuntime.clock(context))) {
                val raw=next.toString();ProfileStore.withoutHistory {store.write(raw)}
                state=next;diskSource=raw;diskRevision=store.revision();presentationEpoch++;presentationCache.clear();conflicts=null
                TemporaryRulesRuntime.schedule(context,next)
                TemporaryRoutesPreparation.schedule(context)
            }
        }
    }
    @Synchronized fun snapshot(): JSONObject {
        if(state != null && diskRevision != store.revision()) load()
        reconcileTemporary()
        val current=state ?: throw IllegalArgumentException("Не удалось прочитать маршрутизацию. Исходные настройки сохранены.")
        val mode=ExperienceModeStore(context).load();snapshotMode=mode
        return ExperienceRouting.Snapshot(ExperienceRouting.project(current,mode).apply {
            TextGeoRules.stripSources(this)
            optJSONObject(TemporaryRules.KEY)?.put("viewMode",mode.id)
        },mode)
    }
    @Synchronized fun save(candidate: JSONObject) {
        require(state != null) { "Сначала восстанови чтение маршрутизации. Исходные настройки сохранены." }
        val mode=ExperienceModeStore(context).load()
        require((candidate as? ExperienceRouting.Snapshot)?.mode?.let {it==mode} ?: (snapshotMode==mode)) {"Режим интерфейса изменился. Открой маршруты заново."}
        TextGeoRules.recoverSources(candidate,ExperienceRouting.project(requireNotNull(state),mode))
        TextGeoRules.compile(candidate)
        RoutingGroups.synchronize(candidate)
        validate(candidate)
        PresetAssets.validate(context,candidate)
        val combined=ExperienceRouting.merge(requireNotNull(state),candidate,mode)
        // The normal editor cannot author leases, including via restored/cached UI metadata.
        combined.remove(TemporaryRules.KEY)
        state?.optJSONObject(TemporaryRules.KEY)?.let {combined.put(TemporaryRules.KEY,JSONObject(it.toString()))}
        TemporaryRules.removeLegacy(combined)
        DnsConfig.retireLegacyPolicies(combined)
        validate(combined)
        PresetAssets.requireActive(context,ExperienceRouting.project(combined,mode))
        val content = combined.toString()
        require(content.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Маршрутизация больше 4 МБ." }
        synchronized(ProfileStore.storageLock) {
            if(diskRevision != store.revision() || diskSource != store.read()) { load();throw IllegalArgumentException("Маршруты изменены другим обновлением. Повтори действие; новые настройки сохранены.") }
            store.write(content); state = JSONObject(content);diskRevision = store.revision();diskSource = content
            presentationEpoch++;presentationCache.clear()
            conflicts=null
        }
        TemporaryRoutesPreparation.schedule(context)
        TemporaryRulesRuntime.schedule(context,requireNotNull(state))
    }
    @Synchronized internal fun temporaryChange(revision:String,expectedGeneration:String,edit:(JSONObject,TemporaryRules.Clock)->Unit) = synchronized(ProfileStore.storageLock) {
        require(presentationRevision()==revision&&generation()==expectedGeneration){"Маршруты или срок изменились. Откройте временные правила заново."}
        val next=JSONObject(requireNotNull(state).toString())
        edit(next,TemporaryRulesRuntime.clock(context));validate(next);validateAssets(next)
        val raw=next.toString();require(raw.toByteArray(Charsets.UTF_8).size<=ServerImport.MAX_BYTES){"Временные правила больше 4 МБ. Удалите лишние записи."}
        require(diskRevision==store.revision()&&diskSource==store.read()){"Маршруты изменились; ничего не перезаписано."}
        ProfileStore.withoutHistory {store.write(raw)}
        state=next;diskRevision=store.revision();diskSource=raw;presentationEpoch++;presentationCache.clear();conflicts=null
        TemporaryRulesRuntime.schedule(context,next);TemporaryRoutesPreparation.schedule(context)
    }
    @Synchronized fun updateDns(setting: JSONObject) {
        DnsConfig.build(setting, "chosen", "bootstrap")
        save(snapshot().put("globalDns", JSONObject(setting.toString())))
    }
    private fun validateAssets(candidate:JSONObject) {
        for(mode in ExperienceMode.entries)PresetAssets.validate(context,ExperienceRouting.project(candidate,mode))
        for(row in TemporaryRules.rows(candidate)) {
            val scope=TemporaryRules.scope(candidate,row.getString("mode"),row.getString("group"))
            scope.put("rules",JSONArray().put(row.getJSONObject("rule")))
            PresetAssets.validate(context,scope)
        }
    }
    @Synchronized fun editable(rule:JSONObject):JSONObject {
        val reference=ExperienceRouting.project(requireNotNull(state),ExperienceModeStore(context).load())
        val wrapper=JSONObject().put("rules",JSONArray().put(JSONObject(rule.toString())))
        TextGeoRules.recoverSources(wrapper,reference)
        return TextGeoRules.editable(wrapper.getJSONArray("rules").getJSONObject(0))
    }
    companion object {
        private val loadTraceCount = java.util.concurrent.atomic.AtomicInteger()
        private val validationTraceCount = if (BuildConfig.DEBUG) java.util.concurrent.atomic.AtomicInteger() else null
        private val structuralValidation = RoutingValidationMemo(::validateUncached)
        internal fun initialSettings(countryText:String,previousDns:String?):JSONObject=RouteRules.defaults().apply {
            if(previousDns in setOf("Cloudflare","Google","Системный"))getJSONObject("globalDns")
                .put("provider",previousDns).put("protocol",if(previousDns=="Системный")"Системный"else"DoH")
            getJSONArray("rules").put(RouteRules.russiaDirect(countryText))
        }
        @JvmStatic fun validate(source: JSONObject) {
            structuralValidation.validate(source)
        }
        private inline fun <T> validationStage(callId: Int, stage: String, action: () -> T): T {
            if (!BuildConfig.DEBUG || callId == 0) return action()
            val started = System.nanoTime()
            try { return action() } finally {
                runCatching {
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    android.util.Log.d("MCRF_STARTUP", "routing_validate.call=$callId stage=$stage elapsed_ms=$elapsed")
                }
            }
        }
        /** Release transitive path: JSON schemas, numeric address parsing, fixed tables and pure text cache only.
         * No Context/assets/files/clock/network/policy/generation/lease admission. These remain outside the memo.
         * All outer/bank/recursive checks must finish before publishing a full-input success proof.
         * DEBUG-only first-eight-call phase timings never influence validation or memo admission.
         */
        private fun validateUncached(source: JSONObject) {
            val traceId = if (BuildConfig.DEBUG) validationTraceCount!!.incrementAndGet().let { if (it in 1..8) it else 0 } else 0
            validationStage(traceId,"temp") { TemporaryRules.validate(source) }
            validationStage(traceId,"experience") { ExperienceRouting.validate(source) }
            validationStage(traceId,"geo_editor") {
                source.getJSONArray("rules").let {rules->repeat(rules.length()){i->RouteEditorDraft.validateGeo(TextGeoRules.editableText(rules.getJSONObject(i)),source)}}
            }
            val settings=validationStage(traceId,"clone") { JSONObject(source.toString()).apply {remove(ExperienceRouting.KEY)} }
            validationStage(traceId,"geo_compile") { TextGeoRules.compile(settings) }
            validationStage(traceId,"core") { validateCore(settings) }
        }
        private fun validateCore(settings: JSONObject) {
            require(!settings.has(RoutingConnectionBinding.RUNTIME)&&!settings.has(RoutingConnectionBinding.DEFAULT)){"Служебные маршруты нельзя сохранять как пользовательские настройки."}
            require(settings.getInt("version") == 1) { "Неизвестная версия настроек маршрутизации." }
            RoutingGroups.validate(settings)
            RoutingConnectionBinding.validate(settings)
            require(settings.getString("defaultTarget") in setOf("proxy", "direct", "block")) { "Неизвестный выход по умолчанию." }
            require(settings.optString("appMode", "all") in setOf("all", "include")) { "Неизвестный режим VPN приложений." }
            if(settings.optString("appMode","all")=="include")require((settings.optJSONArray("vpnApps")?.length()?:0)>0){"Режим «только выбранные» требует хотя бы одно приложение VPN."}
            DnsConfig.build(settings.getJSONObject("globalDns"), "chosen", "bootstrap")
            settings.optJSONObject("advancedDns")?.let(AdvancedDnsConfig::validate)
            settings.optJSONObject("remoteRouting")?.let(RemoteRoutingConfig::validate)
            settings.optJSONObject("geoData")?.let(GeoDataConfig::validate)
            val excluded = settings.getJSONArray("excludedApps")
            require(excluded.length() <= 256) { "Допускается до 256 исключений приложений." }
            val direct = (0 until excluded.length()).mapTo(hashSetOf()) { excluded.getString(it).also(RouteRules::validatePackage) }
            settings.optJSONArray("vpnApps")?.let { forced ->
                require(forced.length() <= 256) { "Допускается до 256 приложений только в VPN." }
                repeat(forced.length()) { i -> val app = forced.getString(i); RouteRules.validatePackage(app)
                    require(app !in direct) { "Приложение не может одновременно идти только напрямую и только в VPN." } }
            }
            val rules = settings.getJSONArray("rules")
            require(rules.length() <= 1024) { "Допускается до 1024 списков маршрутизации." }
            val ids = hashSetOf<String>()
            repeat(rules.length()) { i ->
                val rule = rules.getJSONObject(i); val id = rule.getString("id"); val name = rule.getString("name")
                require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && ids.add(id) && name.trim().isNotEmpty() && name.length <= 256) { "Некорректное имя или идентификатор списка." }
                require(rule.optString("target", "proxy") in setOf("proxy", "direct", "block")) { "Неизвестный выход списка." }
                RouteServerPlan.validate(rule)
                RouteRules.matches(rule)
                rule.optJSONObject("dns")?.let { DnsConfig.build(it, "test-dns", "bootstrap") }
            }
            RouteServerPlan.activeIds(settings)
        }
    }
}
