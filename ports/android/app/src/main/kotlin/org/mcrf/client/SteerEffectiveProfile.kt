package org.mcrf.client

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Working URI for the EFFECTIVE server, never a catalogue identity rewrite.
 * A full import roundtrip must preserve every outbound field (except tag).
 * Unsupported JSON is refused rather than discarded or replaced by old URI.
 * Unmodelled original URI options survive; new unrepresentable JSON cannot. */
internal object SteerEffectiveProfile {
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private fun decode(value:String)=URLDecoder.decode(value.replace("+","%2B"),"UTF-8")
    private fun transport(node:JSONObject?)=node?.let {JSONObject(it.toString()).apply {remove("tag")}}
    private fun same(left:ServerImport.Server,right:ServerImport.Server)=left.host==right.host&&left.port==right.port&&
        RoutingDefaultsEdits.equivalent(transport(left.outbound)?:JSONObject(),transport(right.outbound)?:JSONObject())
    private fun link(entry:ServerImport.Server)=runCatching {URI(entry.original)}.getOrNull()?.takeIf {
        it.scheme?.lowercase() in setOf("vless","vmess","trojan","ss","socks","socks5","http","https","hy2","hysteria2")
    }
    fun profile(entry:ServerImport.Server):String {
        val original=ServerImport.read(entry.original).singleOrNull()
            ?:throw IllegalArgumentException("Steer: нужен один профиль сервера.")
        // An unchanged JSON import is NOT a native URI. Generate it from the
        // effective model too, then prove exact roundtrip before handing it to
        // the external worker. Never pass a JSON blob into the URI-only JNI.
        val nativeLink=link(entry)
        if(nativeLink!=null&&same(original,entry))return entry.original
        val node=requireNotNull(entry.outbound)
        val result=if(entry.protocol=="vmess")vmess(entry,node)else uri(entry,node)
        val imported=ServerImport.read(result).singleOrNull()
        require(imported!=null&&same(imported,entry)) {
            "Steer не может точно преобразовать эти параметры JSON в ссылку. Настройки не отброшены; старый профиль вместо изменённого не используется."
        }
        return result
    }
    private fun uri(entry:ServerImport.Server,node:JSONObject):String {
        val source=link(entry)
        val query=java.util.TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER)
        source?.rawQuery?.split('&')?.filter {it.isNotEmpty()}?.forEach {part->
            val fields=part.split('=',limit=2);query[decode(fields[0])]=decode(fields.getOrElse(1){""})
        }
        // Remove aliases before emitting canonical fields; a stale alias may
        // otherwise win in a native parser even if the host roundtrip matches.
        listOf("security","sni","serverName","insecure","allowInsecure","alpn","fp","fingerprint","pbk","sid","flow",
            "type","network","path","host","serviceName","mode","obfs","obfs-password","upmbps","downmbps","plugin").forEach(query::remove)
        val type=node.getString("type")
        val scheme=when(type) {
            "shadowsocks"->"ss"
            "socks"->"socks"
            "http"->if(node.optJSONObject("tls")?.optBoolean("enabled")==true)"https"else"http"
            "hysteria2"->"hy2"
            "vless","trojan"->type
            else->throw IllegalArgumentException("Steer: этот профиль нельзя точно преобразовать в ссылку.")
        }
        val user=when(type) {
            "vless"->encode(node.getString("uuid"))
            "trojan","hysteria2"->encode(node.getString("password"))
            "shadowsocks"->Base64.getUrlEncoder().withoutPadding().encodeToString((node.getString("method")+":"+node.getString("password")).toByteArray(Charsets.UTF_8))
            "socks","http"->if(node.has("username"))encode(node.getString("username"))+(if(node.has("password"))":"+encode(node.getString("password"))else "")else ""
            else->throw IllegalArgumentException("Steer: этот профиль нельзя точно преобразовать в ссылку.")
        }
        if(node.has("flow"))query["flow"]=node.getString("flow")
        node.optJSONObject("tls")?.let {tls->
            if(tls.optBoolean("enabled")) {
                query["security"]="tls";query["sni"]=tls.getString("server_name")
                query["insecure"]=if(tls.optBoolean("insecure"))"1"else"0"
                tls.optJSONArray("alpn")?.let {values->query["alpn"]=(0 until values.length()).joinToString(","){values.getString(it)}}
                tls.optJSONObject("utls")?.takeIf {it.optBoolean("enabled")}?.let {query["fp"]=it.getString("fingerprint")}
                tls.optJSONObject("reality")?.takeIf {it.optBoolean("enabled")}?.let {reality->
                    query["security"]="reality";query["pbk"]=reality.getString("public_key");query["sid"]=reality.optString("short_id")
                }
            }
        }
        node.optJSONObject("transport")?.let {tr->
            val kind=tr.getString("type");query["type"]=kind
            if(kind=="grpc")query["serviceName"]=tr.optString("service_name")else {
                query["path"]=tr.optString("path","/")
                val host=if(kind=="xhttp")tr.optString("host")else tr.optJSONObject("headers")?.optString("Host").orEmpty()
                if(host.isNotEmpty())query["host"]=host
                if(tr.has("mode"))query["mode"]=tr.getString("mode")
            }
        }
        node.optJSONObject("obfs")?.let {obfs->query["obfs"]=obfs.getString("type");query["obfs-password"]=obfs.getString("password")}
        for(direction in listOf("up","down"))if(node.has("${direction}_mbps"))query["${direction}mbps"]=node.get("${direction}_mbps").toString()
        if(node.has("plugin"))query["plugin"]=node.getString("plugin")+node.optString("plugin_opts").let {if(it.isEmpty())""else";$it"}
        val host=node.getString("server").let {if(':' in it)"[$it]"else it}
        val port=node.getInt("server_port");require(port in 1..65535)
        return "$scheme://"+(if(user.isEmpty())""else"$user@")+"$host:$port"+
            (if(query.isEmpty())""else query.entries.joinToString("&",prefix="?"){encode(it.key)+"="+encode(it.value)})+
            "#"+encode(entry.name)
    }
    private fun vmess(entry:ServerImport.Server,node:JSONObject):String {
        val source=if(link(entry)?.scheme?.lowercase()=="vmess")JSONObject(CompatibilityImportFormats.decodeBase64(entry.original.substringAfter("://").substringBefore('#')))else JSONObject()
        source.put("add",node.getString("server")).put("port",node.getInt("server_port"))
            .put("id",node.getString("uuid")).put("scy",node.optString("security","auto")).put("aid",node.optInt("alter_id",0)).put("ps",entry.name)
        listOf("tls","sni","serverName","net","path","host","fp","fingerprint","alpn","insecure","allowInsecure").forEach(source::remove)
        node.optJSONObject("tls")?.takeIf {it.optBoolean("enabled")}?.let {tls->
            source.put("tls","tls").put("sni",tls.getString("server_name"))
            if(tls.has("insecure"))source.put("insecure",tls.getBoolean("insecure"))
            tls.optJSONObject("utls")?.takeIf {it.optBoolean("enabled")}?.let {source.put("fp",it.getString("fingerprint"))}
            tls.optJSONArray("alpn")?.let {values->source.put("alpn",(0 until values.length()).joinToString(","){values.getString(it)})}
        }
        node.optJSONObject("transport")?.let {tr->
            source.put("net",tr.getString("type"))
            source.put("path",if(tr.optString("type")=="grpc")tr.optString("service_name")else tr.optString("path","/"))
            tr.optJSONObject("headers")?.optString("Host")?.let {source.put("host",it)}
        }
        return "vmess://"+Base64.getEncoder().encodeToString(source.toString().toByteArray(Charsets.UTF_8))
    }
}
