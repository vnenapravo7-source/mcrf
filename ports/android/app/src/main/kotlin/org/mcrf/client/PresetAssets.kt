package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest

object PresetAssets {
    data class Item(val id: String, val name: String, val kind: String, val records: Int, val domains: Int, val ips: Int, val bytes: Long, val sha256: String, val source: String)
    internal class DownloadRequired(val items: List<Item>, val geo: List<GeoDataConfig.MissingSources>) :
        IllegalArgumentException("Загрузите геоданные для маршрутов в MCRF.") {
        val names: List<String> get() = items.map(Item::name) + geo.flatMap { it.kinds }.distinct().map {
            if (it == "geoip") "GeoIP — IP-сети" else "GeoSite — домены"
        }
    }
    @Volatile private var cached: List<Item>? = null
    @Synchronized fun catalogue(context: Context): List<Item> {
        cached?.let { return it }
        val root = context.assets.open("presets/catalogue.json").use { JSONObject(String(ImportIO.read(it), Charsets.UTF_8)) }
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("presets"); require(rows.length() == 68)
        val list = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            Item(it.getString("id"),it.getString("name"),it.getString("kind"),it.getInt("records"),it.getInt("domains"),it.getInt("ips"),it.getLong("bytes"),it.getString("sha256"),it.optString("repository"))
        }.also { require(it.id.matches(Regex("[0-9a-f]{16}")) && it.sha256.matches(Regex("[0-9a-f]{64}")) && it.bytes in 1..8_388_608 && it.records > 0 && it.name.length in 1..256) } }
        require(list.map { it.id }.distinct().size == list.size)
        return list.toList().also { cached = it }
    }
    fun validate(context: Context, settings: JSONObject) {
        val rules = settings.getJSONArray("rules")
        val requested = (0 until rules.length()).flatMap { PresetRules.ids(rules.getJSONObject(it)) }
        if (requested.isNotEmpty()) {
            val available=catalogue(context).associateBy {it.id}
            require(available.keys.containsAll(requested)) { "В бэкапе есть готовые списки, которых нет в этой версии MCRF." }
            repeat(rules.length()) { index -> val rule=rules.getJSONObject(index);val ids=PresetRules.ids(rule)
                if(ids.isNotEmpty()&&rule.has("dns")) {
                    val manual=RouteRules.parse(rule.getString("text"))
                    require(ids.any {available.getValue(it).domains>0} || manual.getValue("domain").isNotEmpty() || manual.getValue("domain_suffix").isNotEmpty()) {
                        "Для отдельного DNS в списке нужны домены. Для IP-сетей используется DNS из настроек."
                    }
                }
            }
        }
    }
    /** Trusted packaged catalogue, never guess IP content from a user label or path. */
    fun ipRuleSetTags(context:Context,settings:JSONObject):Set<String> {
        val needed=PresetRules.needed(settings)
        if(needed.isEmpty())return emptySet()
        val available=catalogue(context).associateBy {it.id}
        return needed.filter {id->requireNotNull(available[id]) {"Готовый список недоступен: обнови MCRF."}.ips>0}.map(PresetRules::tag).toSet()
    }
    @Synchronized fun resolve(context: Context, settings: JSONObject): Map<String, String> {
        val items = required(context, PresetRules.needed(settings))
        requireAvailable(context, items, listOf(settings))
        val geoPaths = GeoDataConfig.resolve(context, settings)
        if (items.isEmpty()) return geoPaths
        return geoPaths + items.associate { item ->
            val file = destination(context, item)
            if (!verified(file, item)) {
                val bytes = ByteArray(item.bytes.toInt())
                DataInputStream(context.assets.open("presets/${item.id}.srs")).use {
                    it.readFully(bytes)
                    require(it.read() == -1) { "Повреждён встроенный готовый список." }
                }
                require(sha256(bytes) == item.sha256) { "Повреждён встроенный готовый список." }
                write(file, item, bytes)
            }
            item.id to file.absolutePath
        }
    }

    @Synchronized internal fun requireAvailable(context: Context, vararg states: JSONObject) =
        requireAvailable(context, required(context, states.flatMap(PresetRules::needed).toSet()), states.toList())

    internal fun requireSteerGeo(context: Context, state: JSONObject, indices: Set<Int>) {
        GeoDataConfig.missingSources(context, state, indices)?.let { throw DownloadRequired(emptyList(), listOf(it)) }
    }

    internal fun requireActive(context: Context, candidate: JSONObject) {
        if (McrfVpnService.hasActiveSession()) {
            requireAvailable(context, TemporaryRulesRuntime.project(candidate))
        }
    }

    private fun requireAvailable(context: Context, items: List<Item>, states: List<JSONObject>) {
        val missing = items.filter { PresetReleaseRanges.downloadable(context, it) && !verified(destination(context, it), it) }
        val geo = states.distinctBy(JSONObject::toString).mapNotNull { GeoDataConfig.missingSources(context, it) }
        if (missing.isNotEmpty() || geo.isNotEmpty()) throw DownloadRequired(missing, geo)
    }

    private fun required(context: Context, ids: Set<String>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val items = catalogue(context).associateBy { it.id }
        return ids.map { requireNotNull(items[it]) { "Готовый список недоступен: обнови MCRF." } }
    }

    private val downloadLock = java.util.concurrent.locks.ReentrantLock()

    internal fun download(context: Context, requested: DownloadRequired) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        downloadLock.lockInterruptibly()
        try {
            val missing = synchronized(this) { requested.items.filterNot { verified(destination(context, it), it) } }
            val contents = missing.associateWith { PresetReleaseRanges.download(context, it) }
            val deadline = System.nanoTime() + 30_000_000_000L
            requested.geo.forEach { it.download(context, deadline) }
            synchronized(this) {
                contents.forEach { (item, bytes) -> write(destination(context, item), item, bytes) }
            }
        } finally { downloadLock.unlock() }
    }

    private fun destination(context: Context, item: Item): File {
        val root = File(context.filesDir, "routing-assets")
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val file = File(root, "${item.id}-${item.sha256}.srs")
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.canonicalFile)
        return file
    }

    private fun verified(file: File, item: Item): Boolean {
        if (!file.isFile || file.length() != item.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = item.bytes.toInt()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) return false
                digest.update(buffer, 0, count)
                remaining -= count
            }
            if (input.read() != -1) return false
        }
        return hex(digest.digest()) == item.sha256
    }

    private fun write(file: File, item: Item, bytes: ByteArray) {
        val root = checkNotNull(file.parentFile)
        if (!root.isDirectory) check(root.mkdir() || root.isDirectory)
        for (suffix in listOf(".new", ".bak")) require(!java.nio.file.Files.isSymbolicLink(File(root, file.name + suffix).toPath()))
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        require(verified(file, item)) { "Не удалось сохранить готовый список." }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
