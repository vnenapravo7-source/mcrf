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
    internal val presentationCache=RoutingPresentationCache<RoutingPresentation>()
    @Synchronized internal fun presentationRevision():String="${store.revision()}:$presentationEpoch"
    @Synchronized internal fun presentationSnapshot():Pair<String,JSONObject> {
        val copy=snapshot()
        return presentationRevision() to copy
    }
    @Synchronized fun generation():String = ProfileStore.generation(context)
    @Synchronized fun snapshotWithGeneration():Pair<JSONObject,String> = synchronized(ProfileStore.storageLock) {snapshot() to ProfileStore.generation(context)}
    @Synchronized fun editInGeneration(expected:String, edit:(JSONObject)->Unit):JSONObject = synchronized(ProfileStore.storageLock) {
        require(ProfileStore.generation(context)==expected){"Данные восстановлены из резервной копии. Открой настройки заново; старое окно не изменило их."}
        val next=snapshot();edit(next);save(next);next
    }
    @Synchronized fun load() {
        presentationEpoch++;presentationCache.clear()
        state = null
        val source = store.read()
        val candidate = if (source.isEmpty()) {
            RouteRules.defaults().apply {
                val previous = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("dns", "Cloudflare")
                if (previous in setOf("Cloudflare", "Google", "Системный")) getJSONObject("globalDns")
                    .put("provider", previous).put("protocol", if (previous == "Системный") "Системный" else "DoH")
                // .bin keeps AAPT from implicitly decompressing and renaming a .gz asset.
                val text = GZIPInputStream(context.assets.open("russia-routes.bin")).use { String(ImportIO.read(it), Charsets.UTF_8) }
                val parsed = RouteRules.parse(text)
                require(parsed.getValue("ip_cidr").size >= 500 && parsed.getValue("domain_suffix").size >= 100) { "Неполный список РФ" }
                put("defaultTarget", "direct"); getJSONArray("rules").put(RouteRules.outsideRussia(text))
            }
        } else JSONObject(source)
        if (!candidate.has("vpnApps")) candidate.put("vpnApps", JSONArray())
        RoutingGroups.ensure(candidate)
        RoutingGroups.synchronize(candidate)
        validate(candidate); PresetAssets.validate(context,candidate); state = candidate; diskRevision = store.revision();diskSource = source
    }
    @Synchronized fun snapshot(): JSONObject {
        if(state != null && diskRevision != store.revision()) load()
        return JSONObject((state ?: throw IllegalArgumentException("Не удалось прочитать маршрутизацию. Исходные настройки сохранены.")).toString())
    }
    @Synchronized fun save(candidate: JSONObject) {
        require(state != null) { "Сначала восстанови чтение маршрутизации. Исходные настройки сохранены." }
        RoutingGroups.synchronize(candidate)
        validate(candidate)
        PresetAssets.validate(context,candidate)
        val content = candidate.toString()
        require(content.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Маршрутизация больше 4 МБ." }
        synchronized(ProfileStore.storageLock) {
            if(diskRevision != store.revision() || diskSource != store.read()) { load();throw IllegalArgumentException("Маршруты изменены другим обновлением. Повтори действие; новые настройки сохранены.") }
            store.write(content); state = JSONObject(content);diskRevision = store.revision();diskSource = content
            presentationEpoch++;presentationCache.clear()
        }
    }
    @Synchronized fun updateDns(setting: JSONObject) {
        DnsConfig.build(setting, "chosen", "bootstrap")
        save(snapshot().put("globalDns", JSONObject(setting.toString())))
    }
    companion object {
        @JvmStatic fun validate(settings: JSONObject) {
            require(settings.getInt("version") == 1) { "Неизвестная версия настроек маршрутизации." }
            RoutingGroups.validate(settings)
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
