package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object PresetAssets {
    data class Item(val id: String, val name: String, val kind: String, val records: Int, val domains: Int, val ips: Int, val bytes: Long, val sha256: String, val source: String)
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
        val geoPaths = GeoDataConfig.resolve(context, settings)
        val ids = PresetRules.needed(settings); if (ids.isEmpty()) return geoPaths
        val items = catalogue(context).associateBy { it.id }
        val root = File(context.filesDir, "routing-assets")
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == context.filesDir.canonicalFile)
        if (!root.isDirectory) check(root.mkdir())
        return geoPaths + ids.associateWith { id ->
            val item = items[id] ?: throw IllegalArgumentException("Готовый список недоступен: обнови MCRF.")
            val file = File(root, "${item.id}-${item.sha256}.srs")
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.canonicalFile)
            fun hash(): String = file.inputStream().use { input -> val sum=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val count=input.read(buffer);if(count<0)break;sum.update(buffer,0,count)};sum.digest().joinToString(""){"%02x".format(it.toInt() and 255)} }
            if (!file.isFile || file.length()!=item.bytes || hash()!=item.sha256) {
                val atomic = android.util.AtomicFile(file)
                val output = atomic.startWrite()
                try { context.assets.open("presets/$id.srs").use { input -> input.copyTo(output) }; atomic.finishWrite(output) }
                catch (error: Exception) { atomic.failWrite(output); throw error }
                require(file.length()==item.bytes && hash()==item.sha256) { "Повреждён встроенный готовый список." }
            }
            file.absolutePath
        }
    }
}
