package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object ImportedRuleProviders {
    fun rows(geo:JSONObject):List<JSONObject> = geo.optJSONArray("ruleProviders")?.let {a->(0 until a.length()).map(a::getJSONObject)}.orEmpty()
    fun ipTags(geo:JSONObject)=rows(geo).filter {it.getString("behavior")!="domain"}.map {it.getString("tag")}.toSet()
    fun validate(geo:JSONObject) {
        val rows=rows(geo);require(rows.size<=128)
        require(rows.map {it.getString("tag")}.distinct().size==rows.size)
        rows.forEach {p->
            require(p.keys().asSequence().all {it in setOf("tag","behavior","format","url","payload","interval")})
            require(p.optLong("interval",86400) in 300..604800)
            require(p.getString("tag").matches(Regex("mcrf-import-[0-9]{1,3}")))
            require(p.getString("behavior") in setOf("domain","ipcidr","classical")&&p.getString("format") in setOf("mrs","yaml","text"))
            require(p.has("url")!=p.has("payload"))
            if(p.has("url"))RemoteRoutingConfig.https(p.getString("url"))else require(p.getJSONArray("payload").length() in 1..100000)
        }
    }
    internal fun convert(p:JSONObject,bytes:ByteArray):JSONObject {
        val behavior=p.getString("behavior");val format=p.getString("format")
        if(format=="mrs")return MihomoMrs.decode(bytes,behavior)
        val values=if(p.has("payload"))p.getJSONArray("payload").let {a->(0 until a.length()).map(a::getString)}
        else if(format=="yaml") {
            val document=RoutingYaml.parse(bytes.toString(Charsets.UTF_8));val payload=document["payload"] as? List<*>?:error("В YAML rule-provider нет payload.")
            payload.map {it as String}
        } else bytes.toString(Charsets.UTF_8).lineSequence().map {it.substringBefore('#').trim()}.filter {it.isNotEmpty()}.toList()
        require(values.size in 1..100000)
        val rules=JSONArray()
        values.forEach {raw->
            require(raw.length in 1..8192)
            val match=when(behavior) {
                "classical"->{val source=raw.removeSuffix(",no-resolve");val first=source.indexOf(',');require(first>0);ClashRoutingProfile.match(source.substring(0,first).uppercase(),source.substring(first+1),emptyMap())}
                "ipcidr"->JSONObject().put("ip_cidr",JSONArray().put(raw))
                else->{val value=raw.removePrefix("+.")
                    if(raw.startsWith("+."))JSONObject().put("domain_suffix",JSONArray().put(value))
                    else if(raw.startsWith('.')||raw.contains('*'))JSONObject().put("domain_regex",JSONArray().put("^"+raw.split('*').joinToString("[^.]+") {java.util.regex.Pattern.quote(it)}.let {if(raw.startsWith('.'))".+"+it else it}+"$"))
                    else JSONObject().put("domain",JSONArray().put(raw))}
            }
            RemoteRoutingImports.validateMatch(match);rules.put(match)
        }
        return JSONObject().put("version",3).put("rules",rules)
    }
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    fun resolve(context:Context,geo:JSONObject,deadlineNanos:Long=System.nanoTime()+30_000_000_000L):Map<String,String> {
        validate(geo)
        if(rows(geo).isEmpty())return emptyMap()
        val ns=geo.getString("cacheNamespace");require(ns.matches(Regex("profile-[a-f0-9]{32}")))
        val root=File(context.filesDir,"mcrf-routing-providers")
        require(!java.nio.file.Files.isSymbolicLink(root.toPath())&&root.canonicalFile.parentFile==context.filesDir.canonicalFile)
        check(root.isDirectory||root.mkdir())
        val lock=File(root,"$ns.lock").toPath()
        require(!java.nio.file.Files.isSymbolicLink(lock)&&(!java.nio.file.Files.exists(lock,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.isRegularFile(lock,java.nio.file.LinkOption.NOFOLLOW_LINKS)))
        return java.nio.channels.FileChannel.open(lock,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use {channel->
        val lease=try {channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){throw IllegalStateException("Списки этого профиля уже обновляются.")}
        check(lease!=null){"Списки этого профиля уже обновляются."}
        lease.use {
        rows(geo).associate {p->
            val file=File(root,"$ns-${digest(p.toString())}.json")
            // Stable within this profile and provider slot, even when its download URL changes.
            val previous=File(root,"$ns-${p.getString("tag")}-${p.getString("behavior")}-last.json")
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            require(!java.nio.file.Files.isSymbolicLink(previous.toPath()))
            for(suffix in listOf(".new",".bak"))require(!java.nio.file.Files.isSymbolicLink(File(root,file.name+suffix).toPath()))
            fun checked(bytes:ByteArray) {
                require(bytes.size in 1..(64*1024*1024))
                val document=JSONObject(bytes.toString(Charsets.UTF_8));require(document.getInt("version")==3)
                val rules=document.getJSONArray("rules");require(rules.length() in 1..100000)
                repeat(rules.length()){i->RemoteRoutingImports.validateMatch(rules.getJSONObject(i))}
            }
            fun cached(target:File):ByteArray? = target.takeIf {it.isFile&&it.length() in 1..(64L*1024*1024)}?.readBytes()?.also(::checked)
            val age=System.currentTimeMillis()-file.lastModified()
            val fresh=if(age in 0 until p.optLong("interval",86400)*1000)try {cached(file)}catch(_:Exception){null}else null
            val bytes=fresh?:GeodataRefresh.bytes({
                val fetched=if(p.has("payload"))ByteArray(0) else RemoteRoutingConfig.fetchWithin(p.getString("url"),64*1024*1024,deadlineNanos){RemoteRoutingNetwork.open(context,it)}
                convert(p,fetched).toString().toByteArray(Charsets.UTF_8)
            },{try {cached(file)}catch(_:Exception){null}?:cached(previous)},::checked)
            val sum=digest(bytes.toString(Charsets.UTF_8));val immutable=File(root,"$ns-$sum-source.json")
            require(!java.nio.file.Files.isSymbolicLink(immutable.toPath()))
            fun write(target:File) {
                for(suffix in listOf(".new",".bak"))require(!java.nio.file.Files.isSymbolicLink(File(root,target.name+suffix).toPath()))
                val atomic=android.util.AtomicFile(target);val out=atomic.startWrite();try {out.write(bytes);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}}
            if(!immutable.isFile)write(immutable) else require(immutable.length()==bytes.size.toLong()&&digest(immutable.readText(Charsets.UTF_8))==sum){"Повреждён подготовленный rule-provider."}
            if(fresh==null)write(file)
            write(previous)
            p.getString("tag") to immutable.absolutePath
        }
        }
        }
    }
    fun attach(config:JSONObject,geo:JSONObject,paths:Map<String,String>) {
        val rows=rows(geo);if(rows.isEmpty())return
        val route=config.getJSONObject("route");val sets=route.optJSONArray("rule_set")?:JSONArray().also {route.put("rule_set",it)}
        rows.forEach {p->val tag=p.getString("tag");require((0 until sets.length()).none {sets.getJSONObject(it).optString("tag")==tag})
            sets.put(JSONObject().put("tag",tag).put("type","local").put("format","source").put("path",paths[tag]?:error("Не подготовлен rule-provider профиля.")))
        }
    }
}
