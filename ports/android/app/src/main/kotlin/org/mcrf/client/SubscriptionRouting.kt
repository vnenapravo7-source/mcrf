package org.mcrf.client

import org.json.JSONObject
import java.net.URI
import java.net.SocketTimeoutException
import java.security.MessageDigest

/** Subscription-owned routing is committed inside the same encrypted catalogue as its servers. */
internal object SubscriptionRouting {
    fun sourceIds(source:JSONObject,candidate:JSONObject?,entries:List<ServerImport.Server>):List<String> {
        source.optJSONObject("mcrf_bs")?.optJSONArray("candidates")?.let {a->return (0 until a.length()).map {a.getJSONObject(it).getString("id")}}
        candidate?.optString("id")?.takeIf {it.isNotEmpty()}?.let {return listOf(it)}
        val tags=source.optJSONArray("outbounds")?.let {a->(0 until a.length()).map {a.getJSONObject(it).optString("tag")}.toSet()}.orEmpty()
        return entries.filter {ServerImport.tag(it) in tags}.map {it.id}
    }
    fun projectPool(state:JSONObject,ids:List<String>,subscriptions:List<JSONObject>):JSONObject {
        if(RoutingGroups.isImported(RoutingGroups.active(state))||ids.isEmpty())return state
        fun ownerKey(id:String):String {
            val owners=subscriptions.filter {sub->sub.getJSONArray("servers").let {a->(0 until a.length()).any {ServerImport.read(a.getString(it)).any {entry->entry.id==id}}}}
            val profiles=owners.filter {it.optJSONObject("metadata")?.optJSONObject("routing")!=null}
            require(profiles.isEmpty()||owners.size==1){"Сервер принадлежит нескольким подпискам с разными маршрутами. Выберите отдельный профиль маршрутизации."}
            return profiles.singleOrNull()?.getString("id").orEmpty()
        }
        require(ids.map(::ownerKey).distinct().size<=1){"Автовыбор объединяет серверы с разными маршрутами подписок. Выберите сервер вручную или общий отдельный профиль маршрутизации."}
        return project(state,ids.first(),subscriptions)
    }
    private val link=Regex("(?i)^(?:(?:happ|incy))?://(?:auto)?routing/(?:onadd|add)/.+$|^(?:happ|incy)?://routing/off$")
    data class Extracted(val servers:String,val request:String?)
    fun extract(body:String,routing:String?,autorouting:String?):Extracted {
        var text=body
        if(!text.contains("://")&&!text.trimStart().startsWith('{')&&!text.trimStart().startsWith('['))
            text=try {CompatibilityImportFormats.decodeBase64(text.trim())}catch(_:Exception){text}
        val candidates=text.lineSequence().map {it.trim().removePrefix("#").trim().removePrefix("routing:").removePrefix("autorouting:").trim()}.filter {link.matches(it)}.toList()
        val request=autorouting?.takeIf(link::matches)?:candidates.firstOrNull {it.contains("://autorouting/")}?:routing?.takeIf(link::matches)?:candidates.firstOrNull()
        val servers=text.lineSequence().filterNot {line->val raw=line.trim().removePrefix("#").trim().removePrefix("routing:").removePrefix("autorouting:").trim();link.matches(raw)}.joinToString("\n")
        return Extracted(servers,request)
    }
    fun fetch(request:String,opener:SubscriptionClient.Opener,deadline:Long):JSONObject? {
        if(request.endsWith("://routing/off"))return null
        var raw=if(request.startsWith("://"))"incy$request" else request
        val automatic=raw.contains("://autorouting/")
        if(automatic)raw=raw.replace("://autorouting/","://routing/")
        val content=raw.substringAfter("://routing/").substringAfter('/')
        var source=""
        if(content.startsWith("https://")) {source=SubscriptionClient.address(content).toString();raw=download(source,opener,deadline)}
        else require(HappRoutingLinks.isRouting(raw))
        if(!raw.trimStart().startsWith('{')&&!HappRoutingLinks.isRouting(raw)&&!raw.contains("://"))raw=CompatibilityImportFormats.decodeBase64(raw.trim())
        val parsed=ImportedRoutingProfile.parse(raw)
        // A private draft validates the complete DNS/rules before the catalogue can be committed.
        RoutingGroups.add(RouteRules.defaults(),parsed.name,parsed.settings)
        return JSONObject().put("name",parsed.name).put("settings",parsed.settings).put("source",if(automatic)source else "").put("updatedAt",System.currentTimeMillis())
    }
    private fun download(source:String,opener:SubscriptionClient.Opener,deadline:Long):String {
        var uri=SubscriptionClient.address(source)
        fun remaining():Int {if(Thread.currentThread().isInterrupted)throw InterruptedException();val ms=(deadline-System.nanoTime())/1_000_000;if(ms<=0)throw SocketTimeoutException();return ms.coerceAtMost(30000).toInt()}
        repeat(6) {index->
            val c=opener.open(uri)
            try {
                c.instanceFollowRedirects=false;c.connectTimeout=remaining();c.readTimeout=remaining();c.useCaches=false
                // Device/authorization headers from the subscription are never forwarded to this resource.
                c.setRequestProperty("User-Agent","MCRF-Android");c.setRequestProperty("Accept-Encoding","identity")
                val status=c.responseCode
                if(status in setOf(301,302,303,307,308)){require(index<5);uri=SubscriptionClient.address(uri.resolve(c.getHeaderField("Location")?:error("Нет перенаправления маршрутов.")).toString());return@repeat}
                require(status in 200..299){"Профиль маршрутизации подписки: HTTP $status."}
                require(c.contentLengthLong<=ServerImport.MAX_BYTES)
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                c.inputStream.use {input->while(true){c.readTimeout=remaining();val n=input.read(buffer);if(n<0)break;require(out.size()+n<=ServerImport.MAX_BYTES);out.write(buffer,0,n)}}
                val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                return decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
            }finally{c.disconnect()}
        }
        error("Слишком много перенаправлений маршрутов.")
    }
    fun project(state:JSONObject,serverId:String,subscriptions:List<JSONObject>):JSONObject {
        if(RoutingGroups.isImported(RoutingGroups.active(state)))return state
        val owners=subscriptions.filter {sub->sub.getJSONArray("servers").let {a->(0 until a.length()).any {ServerImport.read(a.getString(it)).any {entry->entry.id==serverId}}}}
        // Shared server ownership cannot choose one provider's private policy arbitrarily.
        val owner=owners.singleOrNull()?:return state
        val profile=owner.optJSONObject("metadata")?.optJSONObject("routing")?:return state
        val settings=JSONObject(profile.getJSONObject("settings").toString())
        require(settings.keys().asSequence().all {it in setOf("rules","defaultTarget","geoData","remoteRouting","globalDns","advancedDns")})
        val next=RoutingGroups.runtime(state)
        // A subscription document owns these rules; ordinary family bindings must
        // not silently replace its selected server with a different global pool.
        for(key in listOf(RoutingConnectionBinding.KEY,RoutingConnectionBinding.RUNTIME,RoutingConnectionBinding.DEFAULT))next.remove(key)
        for(key in listOf("rules","defaultTarget","geoData","remoteRouting","globalDns","advancedDns"))next.remove(key)
        settings.keys().forEach {next.put(it,settings.get(it))}
        val namespace=MessageDigest.getInstance("SHA-256").digest(owner.getString("id").toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}.take(32)
        next.optJSONObject("geoData")?.put("cacheNamespace","profile-$namespace")
        RoutingStore.validate(next)
        return next
    }
}
