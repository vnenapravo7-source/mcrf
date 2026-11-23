package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

object GeoDataConfig {
    fun defaults() = JSONObject().put("source", "Loyalsoldier").put("geoipUrl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat")
        .put("geositeUrl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat").put("selectors", JSONArray())
    fun value(state: JSONObject) = state.optJSONObject("geoData") ?: defaults()
    /** The standard DLC-derived datasets call the Russian domain group category-ru.
     * Custom datasets keep their own category names, including a literal ru. */
    fun categoryCode(s: JSONObject, kind: String, code: String): String {
        val standard = s.optString("geositeUrl") in setOf(
            defaults().getString("geositeUrl"), "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat")
        return if (kind == "geosite" && code.equals("ru", true) && standard) "category-ru" else code
    }
    /** Compatibility importer calls this for actual Happ/incy dataset references; no network side effects. */
    fun fromProvider(document: JSONObject): JSONObject {
        val s=defaults();val selectors=JSONArray();val providerRules=JSONArray()
        for(kind in listOf("geoip","geosite"))document.optString(if(kind=="geoip")"Geoipurl" else "Geositeurl").takeIf {it.isNotBlank()}?.let {s.put("${kind}Url",it).put("source","custom")}
        val order=document.optString("RouteOrder","block-proxy-direct").lowercase().split('-')
        require(order.size==3&&order.toSet()==setOf("block","proxy","direct")){"Неподдерживаемый порядок маршрутов провайдера."}
        for(target in order)for(suffix in listOf("Sites","Ip")) {
            val key=target.replaceFirstChar {it.uppercase()}+suffix
            require(!document.has(key)||document.optJSONArray(key)!=null){"Список $key должен быть массивом."}
            val a=document.optJSONArray(key)?:continue
            repeat(a.length()){val text=a.getString(it)
                require(!text.startsWith("ext:")){"Внешние ext:*.dat требуют явной привязки URL и формата; автоматический перенос недоступен."}
                if(text.isBlank())return@repeat
                val match=JSONObject()
                if(text.startsWith("geoip:")||text.startsWith("geosite:")){
                    val kind=text.substringBefore(':');val code=categoryCode(s,kind,text.substringAfter(':'))
                    val index=selectors.length();selectors.put(JSONObject().put("kind",kind).put("code",code).put("target",target).put("bound",true))
                    match.put("rule_set",JSONArray().put("mcrf-geo-$index"))
                }else if(suffix=="Ip")match.put("ip_cidr",JSONArray(RouteRules.parse(text).getValue("ip_cidr").also {require(it.isNotEmpty()){"Неверная IP-запись провайдера."}}))
                else when {
                    text.startsWith("full:")->match.put("domain",JSONArray().put(text.removePrefix("full:")))
                    text.startsWith("regexp:")->match.put("domain_regex",JSONArray().put(text.removePrefix("regexp:")))
                    text.startsWith("keyword:")->match.put("domain_keyword",JSONArray().put(text.removePrefix("keyword:")))
                    else->match.put("domain_suffix",JSONArray().put(text.removePrefix("domain:")))
                }
                RemoteRoutingImports.validateMatch(match)
                providerRules.put(JSONObject().put("id","provider-${providerRules.length()}").put("name",key).put("text","").put("mode","addresses").put("target",target).put("enabled",true).put("nativeMatch",match))
            }
        }
        s.put("selectors",selectors).put("providerRules",providerRules);validate(s);return s
    }
    /** Merge only for config generation; user's stored lists and country fallback remain editable and intact. */
    fun orderedRules(state:JSONObject):JSONArray {
        val source=state.getJSONArray("rules");val provider=value(state).optJSONArray("providerRules")?:return source
        val out=JSONArray();var added=false
        repeat(source.length()){i -> val r=source.getJSONObject(i)
            if(!added&&RouteRules.countryPreset(r)){repeat(provider.length()){j ->out.put(provider.getJSONObject(j))};added=true};out.put(r)
        }
        if(!added)repeat(provider.length()){out.put(provider.getJSONObject(it))};return out
    }
    fun validate(s: JSONObject) {
        ImportedRuleProviders.validate(s)
        if(s.has("cacheNamespace"))require(s.getString("cacheNamespace").matches(Regex("profile-[a-f0-9]{32}")))
        require(s.getString("source") in setOf("Loyalsoldier", "v2fly", "custom"))
        for (k in listOf("geoip", "geosite")) RemoteRoutingConfig.https(s.getString("${k}Url"))
        val a = s.getJSONArray("selectors"); require(a.length() <= 64)
        repeat(a.length()) { val r = a.getJSONObject(it); require(r.getString("kind") in setOf("geoip", "geosite")); require(r.getString("code").matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Категория: ru, cn, category-ads-all; @атрибуты пока не поддерживаются." }; require(r.getString("target") in setOf("proxy", "direct", "block")) }
        val boundReferences=hashSetOf<Int>()
        require(!s.has("providerRules")||s.optJSONArray("providerRules")!=null){"Правила провайдера должны быть массивом."}
        s.optJSONArray("providerRules")?.let {rules ->require(rules.length()<=1024)
            repeat(rules.length()){i ->val rule=rules.getJSONObject(i);require(rule.getString("target") in setOf("proxy","direct","block"));RemoteRoutingImports.validateMatch(rule.getJSONObject("nativeMatch"))
                if(rule.has("enabled"))require(rule.get("enabled") is Boolean)
                boundReferences.addAll(references(rule.getJSONObject("nativeMatch"),a.length(),true))
            }
        }
        repeat(a.length()){i ->val item=a.getJSONObject(i)
            if(item.has("managedRule"))require(item.get("managedRule") is Boolean)
            if(item.has("bound"))require(item.get("bound") is Boolean)
            if(item.optBoolean("bound")){
                if(!s.has("providerRules"))error("Привязка категории не содержит правил провайдера.")
                require(i in boundReferences){"Категория не используется привязанными правилами провайдера."}
            }
        }
    }
    private fun digest(s: String) = digest(s.toByteArray(Charsets.UTF_8))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun root(context: Context, create: Boolean = true): File = File(context.filesDir, "mcrf-geodata").also {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == context.filesDir.canonicalFile)
        if (create && !it.isDirectory) check(it.mkdir() || it.isDirectory)
    }
    internal fun sourceName(s:JSONObject,kind:String):String {
        require(kind in setOf("geoip","geosite"))
        val namespace=if(s.has("cacheNamespace"))s.getString("cacheNamespace").also {require(it.matches(Regex("profile-[a-f0-9]{32}")))}+"-" else ""
        return namespace+digest(s.getString("${kind}Url"))+".dat"
    }
    fun file(context: Context, s: JSONObject, kind: String): File {
        require(kind in setOf("geoip", "geosite")); return File(root(context), sourceName(s,kind)).also { require(!java.nio.file.Files.isSymbolicLink(it.toPath())) }
    }
    private fun lastProfileFile(context:Context,s:JSONObject,kind:String):File? {
        if(!s.has("cacheNamespace"))return null
        val ns=s.getString("cacheNamespace");require(ns.matches(Regex("profile-[a-f0-9]{32}"))&&kind in setOf("geoip","geosite"))
        return File(root(context),"$ns-$kind-last.dat").also {require(!java.nio.file.Files.isSymbolicLink(it.toPath()))}
    }
    internal fun cachedProfileBytes(context:Context,s:JSONObject,kind:String,check:(ByteArray)->Unit):ByteArray? {
        for(candidate in listOfNotNull(file(context,s,kind),lastProfileFile(context,s,kind))) {
            if(!candidate.isFile||candidate.length() !in 1..(64L*1024*1024))continue
            val bytes=candidate.readBytes()
            try {check(bytes);return bytes}catch(e:Exception){if(e is InterruptedException||e is java.util.concurrent.CancellationException)throw e}
        }
        return null
    }
    internal fun rememberProfileBytes(context:Context,s:JSONObject,kind:String,bytes:ByteArray) {
        val file=lastProfileFile(context,s,kind)?:return
        for(suffix in listOf(".new",".bak"))require(!java.nio.file.Files.isSymbolicLink(File(file.parentFile,file.name+suffix).toPath()))
        val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
        try {output.write(bytes);atomic.finishWrite(output)}catch(e:Exception){atomic.failWrite(output);throw e}
    }
    fun status(context: Context, s: JSONObject, kind: String): String = file(context, s, kind).let { if (it.isFile) "${it.length() / 1024} КБ · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified()))}" else "Ещё не загружен" }
    /** Explicit Steer preparation validates this byte snapshot with its bounded
     * selective reader BEFORE publishing a DAT file. No legacy Proto.fields. */
    internal fun fetchForSteer(context:Context,s:JSONObject,kind:String,deadlineNanos:Long?=null):ByteArray {
        validate(s);require(kind in setOf("geoip","geosite"))
        return fetchBytes(context,s,kind,deadlineNanos)
    }
    private fun fetchBytes(context:Context,s:JSONObject,kind:String,deadlineNanos:Long?=null):ByteArray =
        if(deadlineNanos==null)RemoteRoutingConfig.fetch(s.getString("${kind}Url"),64*1024*1024){RemoteRoutingNetwork.open(context,it)}
        else RemoteRoutingConfig.fetchWithin(s.getString("${kind}Url"),64*1024*1024,deadlineNanos){RemoteRoutingNetwork.open(context,it)}
    /** All DAT publishers use this same nonblocking cross-process fence. It is
     * never acquired by read-only preview/capture, which must not create files. */
    internal fun <T> withSourceWriteLock(context:Context,s:JSONObject,kind:String,action:(File)->T):T {
        val requested=file(context,s,kind);val destination=requested.canonicalFile
        require(destination.parentFile==File(context.filesDir,"mcrf-geodata").canonicalFile&&destination.name==requested.name)
        for(suffix in listOf(".new",".bak"))require(!java.nio.file.Files.isSymbolicLink(File(destination.parentFile,destination.name+suffix).toPath()))
        // A changed URL still writes the SAME profile-owned last-good DAT.
        // Serialize the profile/kind, not just its current URL hash.
        val lockName=if(s.has("cacheNamespace"))s.getString("cacheNamespace")+"-$kind.lock" else destination.name+".lock"
        val lockPath=File(destination.parentFile,lockName).toPath()
        require(!java.nio.file.Files.isSymbolicLink(lockPath)&&(!java.nio.file.Files.exists(lockPath,java.nio.file.LinkOption.NOFOLLOW_LINKS)||
            java.nio.file.Files.isRegularFile(lockPath,java.nio.file.LinkOption.NOFOLLOW_LINKS)))
        java.nio.channels.FileChannel.open(lockPath,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use {channel ->
            val lease=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){throw IllegalStateException("Геобаза уже загружается или обновляется.")}
            check(lease!=null){"Геобаза уже загружается или обновляется."}
            try{return action(destination)}finally{lease.release()}
        }
    }
    fun download(context: Context, s: JSONObject, kind: String, force: Boolean = false, activeSelectors:Set<Int>?=null,refreshDeadlineNanos:Long?=null): File {
        validate(s);val cached=file(context,s,kind)
        if(!force&&cached.isFile)return cached // preserve ordinary cached reads without creating a writer lock
        return withSourceWriteLock(context,s,kind){destination ->
        if (!force && destination.isFile) return@withSourceWriteLock destination
        val checked=if(activeSelectors==null)s else JSONObject(s.toString()).put("selectors",JSONArray(activeSelectors.map {s.getJSONArray("selectors").getJSONObject(it)}))
        val bytes=if(s.has("cacheNamespace"))GeodataRefresh.bytes(
            {fetchBytes(context,s,kind,refreshDeadlineNanos)},
            {cachedProfileBytes(context,s,kind){validateDownload(it,kind,checked)}},
            {validateDownload(it,kind,checked)}) else fetchBytes(context,s,kind,refreshDeadlineNanos).also {validateDownload(it,kind,checked)}
        val atomic = android.util.AtomicFile(destination); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
        rememberProfileBytes(context,s,kind,bytes)
        destination
        }
    }
    /** Validate requested categories before AtomicFile touches the prior usable dataset. */
    fun validateDownload(bytes:ByteArray,kind:String,s:JSONObject) {
        require(kind in setOf("geoip","geosite"));require(bytes.isNotEmpty()&&bytes.size<=64*1024*1024)
        val first=Proto(bytes).fields().firstOrNull {it.number==1&&it.bytes!=null}?:error("Файл не является geodata protobuf.")
        val fields=Proto(first.bytes!!).fields()
        require(fields.any {it.number==1&&it.bytes?.isNotEmpty()==true}&&fields.any {it.number==2&&it.bytes!=null}){"В файле отсутствуют категории геоданных."}
        val selectors=s.getJSONArray("selectors")
        repeat(selectors.length()){i -> val selector=selectors.getJSONObject(i)
            if(selector.getString("kind")==kind)convert(bytes,kind,selector.getString("code"))
        }
    }
    /** Dependency scan only, NOT native-match admission. Logical nesting and
     * scalar rule_set use the same references as actual route construction. */
    private fun references(match:JSONObject,count:Int,geoOnly:Boolean):Set<Int> {
        val found=linkedSetOf<Int>();var nodes=0;var tagsSeen=0
        fun visit(row:JSONObject,depth:Int) {
            require(depth<=16&&++nodes<=4096){"Слишком сложная привязка геоданных."}
            if(row.has("rule_set")) {
                val raw=row.get("rule_set")
                val tags=when(raw){is String->JSONArray().put(raw);is JSONArray->raw;else->error("Неверная ссылка геоданных.")}
                require(tags.length() in 1..4096&&tagsSeen<=4096-tags.length());tagsSeen+=tags.length()
                repeat(tags.length()){i ->
                    val value=tags.get(i);require(value is String)
                    if(geoOnly||value.startsWith("mcrf-geo-")) {
                        require(value.matches(Regex("mcrf-geo-(?:0|[1-9][0-9]?)"))){"Неверная ссылка категории провайдера."}
                        val index=value.substring(9).toInt();require(index in 0 until count){"Категория отсутствует в снимке геоданных."};found.add(index)
                    }
                }
            }
            if(row.has("rules")) {
                require(row.optString("type")=="logical")
                val children=row.getJSONArray("rules");require(children.length() in 1..256)
                repeat(children.length()){visit(children.getJSONObject(it),depth+1)}
            }
        }
        visit(match,0);return found
    }
    /** Legacy selectors stay compatible; managed categories follow all enabled
     * user/provider references, including nested rules, not just root arrays. */
    fun neededSelectors(state:JSONObject):Set<Int> {
        val geo=value(state);val selectors=geo.getJSONArray("selectors");require(selectors.length()<=64)
        require(!geo.has("providerRules")||geo.optJSONArray("providerRules")!=null)
        val referenced=hashSetOf<Int>()
        for(rules in listOfNotNull(state.getJSONArray("rules"),geo.optJSONArray("providerRules"))) {
            require(rules.length()<=1024)
            repeat(rules.length()){i->val rule=rules.getJSONObject(i)
                if(rule.has("enabled"))require(rule.get("enabled") is Boolean)
                if(rule.optBoolean("enabled",true))rule.optJSONObject("nativeMatch")?.let {referenced.addAll(references(it,selectors.length(),false))}}
        }
        return (0 until selectors.length()).filter {i->val selector=selectors.getJSONObject(i)
            if(selector.has("managedRule"))require(selector.get("managedRule") is Boolean)
            !selector.optBoolean("managedRule") || i in referenced}.toSet()
    }
    internal class MissingSources(private val settings: JSONObject, private val selectors: Set<Int>, val kinds: Set<String>) {
        fun download(context: Context, deadline: Long) {
            for (kind in kinds) {
                val force = file(context, settings, kind).let { it.isFile && it.length() !in 1..(64L * 1024 * 1024) }
                GeoDataConfig.download(context, settings, kind, force, selectors, deadline)
            }
        }
    }

    /** Read only. A source is needed only when at least one effective selector references it. */
    internal fun missingSources(context: Context, state: JSONObject, needed: Set<Int> = neededSelectors(state)): MissingSources? {
        if (needed.isEmpty()) return null
        val settings = value(state)
        validate(settings)
        val directory = root(context, create = false)
        val selectors = settings.getJSONArray("selectors")
        val kinds = needed.map { selectors.getJSONObject(it).getString("kind") }.toSet()
        val missing = kinds.filterTo(linkedSetOf()) { kind ->
            fun plausible(name: String): Boolean = File(directory, name).let {
                require(!java.nio.file.Files.isSymbolicLink(it.toPath()))
                it.isFile && it.length() in 1..(64L * 1024 * 1024)
            }
            if (plausible(sourceName(settings, kind))) false
            else if (!settings.has("cacheNamespace")) true
            else {
                val fallback = "${settings.getString("cacheNamespace")}-$kind-last.dat"
                if (!plausible(fallback)) true else {
                    val required = JSONObject(settings.toString()).put("selectors", JSONArray(needed.map(selectors::getJSONObject)))
                    cachedProfileBytes(context, settings, kind) { validateDownload(it, kind, required) } == null
                }
            }
        }
        return if (missing.isEmpty()) null else MissingSources(JSONObject(settings.toString()), needed.toSet(), missing)
    }

    /** Native .dat is never passed to sing-box; convert selected category into a source rule-set. */
    fun resolve(context: Context, state: JSONObject): Map<String, String> {
        val s = value(state); validate(s); val a = s.getJSONArray("selectors")
        val refreshDeadline=System.nanoTime()+30_000_000_000L
        val needed=neededSelectors(state)
        val sources=needed.map {a.getJSONObject(it).getString("kind")}.distinct().associateWith {kind->
            download(context,s,kind,force=s.has("cacheNamespace"),activeSelectors=needed,refreshDeadlineNanos=refreshDeadline.takeIf {s.has("cacheNamespace")})
        }
        return ImportedRuleProviders.resolve(context,s,refreshDeadline)+needed.associate { i ->
            val r = a.getJSONObject(i); val source = sources.getValue(r.getString("kind"))
            // Snapshot and compiled publication share the DAT publisher's cross-process fence.
            // Modification time alone can alias two downloads and overwrite a runtime asset.
            withSourceWriteLock(context,s,r.getString("kind")) { destination ->
                require(destination==source.canonicalFile&&destination.length() in 1..(64L*1024*1024))
                val bytes=destination.readBytes()
                val compiled = convert(bytes, r.getString("kind"), r.getString("code"))
                val target = File(root(context), "${digest(source.name + digest(bytes) + r.toString())}.json")
                require(!java.nio.file.Files.isSymbolicLink(target.toPath()))
                for(suffix in listOf(".new",".bak"))require(!java.nio.file.Files.isSymbolicLink(File(target.parentFile,target.name+suffix).toPath()))
                val atomic = android.util.AtomicFile(target); val out = atomic.startWrite()
                try { out.write(compiled.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
                "geo-$i" to target.absolutePath
            }
        }
    }
    fun attach(config: JSONObject, state: JSONObject, paths: Map<String, String>, proxy: String, direct: String) {
        val a = value(state).getJSONArray("selectors"); ImportedRuleProviders.attach(config,value(state),paths);if (a.length() == 0) return
        val route = config.getJSONObject("route"); val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        val added = JSONArray()
        val needed=neededSelectors(state)
        repeat(a.length()) { i ->
            if(i !in needed)return@repeat
            val r = a.getJSONObject(i); val tag = "mcrf-geo-$i"
            require((0 until sets.length()).none { sets.getJSONObject(it).optString("tag") == tag }) { "Тег геоданных занят профилем." }
            sets.put(JSONObject().put("type", "local").put("format", "source").put("tag", tag).put("path", paths["geo-$i"] ?: error("Сначала подготовь геоданные.")))
            if(!r.optBoolean("bound")&&!r.optBoolean("managedRule"))added.put(JSONObject().put("rule_set", JSONArray().put(tag)).put("action", if (r.getString("target") == "block") "reject" else "route").apply { if (r.getString("target") != "block") put("outbound", if (r.getString("target") == "direct") direct else proxy) })
        }
        // User lists are higher priority. Geodata sits before the final country fallback/catch-all.
        val old = route.getJSONArray("rules"); val rules = JSONArray(); var inserted = false
        repeat(old.length()) { val r = old.getJSONObject(it)
            if (!inserted && (r.optBoolean("invert") || (r.optString("action") == "reject" && r.length() == 1))) { repeat(added.length()) { j -> rules.put(added.getJSONObject(j)) }; inserted = true }; rules.put(r)
        }
        if (!inserted) repeat(added.length()) { rules.put(added.getJSONObject(it)) }; route.put("rules", rules)
    }
    data class Field(val number: Int, val integer: Long = 0, val bytes: ByteArray? = null)
    private class Proto(private val data: ByteArray) {
        private var p = 0
        private fun variable(): Long { var v = 0L; for (shift in 0..63 step 7) { require(p < data.size); val b = data[p++].toInt() and 255; if (shift == 63) require(b <= 1); v = v or ((b and 127).toLong() shl shift); if (b < 128) return v }; error("Некорректный protobuf") }
        fun fields(): List<Field> { val result = ArrayList<Field>(); while (p < data.size) {
            val key = variable(); val number = (key ushr 3).toInt(); require(number > 0)
            when ((key and 7).toInt()) {
                0 -> result.add(Field(number, variable()))
                2 -> { val n = variable(); require(n in 0..(data.size - p).toLong()); result.add(Field(number, bytes = data.copyOfRange(p, p + n.toInt()))); p += n.toInt() }
                1 -> { require(p + 8 <= data.size); p += 8 }
                5 -> { require(p + 4 <= data.size); p += 4 }
                else -> error("Неподдерживаемый protobuf")
            }
        }; return result }
    }
    fun convert(bytes: ByteArray, kind: String, code: String): JSONObject {
        require(kind in setOf("geoip", "geosite")); require(bytes.size <= 64 * 1024 * 1024)
        val categories = Proto(bytes).fields().filter { it.number == 1 }
        fun find(name: String) = categories.asSequence().map { Proto(it.bytes ?: error("Некорректная запись geo")).fields() }
            .firstOrNull { fields -> fields.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8)?.equals(name, true) == true }
        // Prefer a real ru entry in custom databases; alias only if it is absent.
        val selected = find(code) ?: (if (kind == "geosite" && code.equals("ru", true)) find("category-ru") else null)
            ?: error("Категория $kind:$code отсутствует в файле.")
        val rules = JSONArray()
        if (kind == "geoip") {
            val cidrs = JSONArray()
            selected.filter { it.number == 2 }.forEach { val fields = Proto(it.bytes ?: error("Нет CIDR")).fields(); val ip = fields.first { f -> f.number == 1 }.bytes ?: error("Нет IP"); val bits = fields.firstOrNull { f -> f.number == 2 }?.integer ?: 0
                require(ip.size in setOf(4, 16) && bits in 0..(ip.size * 8).toLong()); cidrs.put("${InetAddress.getByAddress(ip).hostAddress}/$bits") }
            require(cidrs.length() > 0); rules.put(JSONObject().put("ip_cidr", cidrs).apply { if (selected.any { it.number == 3 && it.integer != 0L }) put("invert", true) })
        } else {
            val groups = linkedMapOf("domain_keyword" to JSONArray(), "domain_regex" to JSONArray(), "domain_suffix" to JSONArray(), "domain" to JSONArray())
            selected.filter { it.number == 2 }.forEach { val fields = Proto(it.bytes ?: error("Нет домена")).fields(); val type = fields.firstOrNull { f -> f.number == 1 }?.integer?.toInt() ?: 0; val domain = fields.first { f -> f.number == 2 }.bytes?.toString(Charsets.UTF_8) ?: error("Нет домена")
                require(type in 0..3 && domain.isNotEmpty()); groups.getValue(groups.keys.elementAt(type)).put(domain) }
            val match = JSONObject(); groups.forEach { (key, values) -> if (values.length() > 0) match.put(key, values) }; require(match.length() > 0); rules.put(match)
        }
        return JSONObject().put("version", 3).put("rules", rules)
    }
}
