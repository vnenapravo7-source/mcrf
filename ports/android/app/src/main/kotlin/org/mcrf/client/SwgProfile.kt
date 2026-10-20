package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Compatible SWG panel, WDTT Plus and SpaceNeuro/QWDTT links. Never truncate call pools. */
object SwgProfile {
    val TYPES = setOf("csqtt", "qwdtt", "wdtt")
    private fun decode(value: String, form: Boolean = false) = URLDecoder.decode(if (form) value else value.replace("+", "%2B"), "UTF-8")
    private fun query(uri: URI, form: Boolean = false): Map<String, String> {
        val result = linkedMapOf<String, String>()
        uri.rawQuery?.split('&')?.forEach {
            val parts = it.split('=', limit = 2); require(parts.size == 2)
            val key = decode(parts[0]); require(key !in result)
            result[key] = decode(parts[1], form)
        }
        return result
    }
    fun link(source: String): ServerImport.Server {
        require(source.length <= 65536)
        val normalized = if (source.startsWith("qwdtt:config?", true)) "qwdtt://config?" + source.substringAfter('?') else source
        val type = normalized.substringBefore("://").lowercase(Locale.ROOT); require(type in TYPES)
        if(type=="csqtt"&&!normalized.startsWith("csqtt://connect?",true)&&!CompatibilityImportCsqttLegacy.isLegacy(normalized))return Csqtt25Profile.encryptedLink(source)
        if (type == "wdtt" && !normalized.startsWith("wdtt://connect?", true)) {
            val fields = normalized.substringAfter("://").substringBefore('#').split(':')
            require(fields.size == 6) { "В старой WDTT-ссылке должно быть шесть полей." }
            val data = JSONObject().put("type",type).put("server", decode(fields[0])).put("server_port",fields[1].toInt())
                .put("wg_port",fields[2].toInt()).put("local_port",fields[3].toInt()).put("password",decode(fields[4]))
                .put("hashes",decode(fields[5])).put("name", decode(normalized.substringAfter('#', "WDTT")))
            return fromJson(source,data)
        }
        val uri = URI(normalized)
        if(type=="csqtt"&&uri.rawUserInfo!=null) {
            require(!uri.host.isNullOrBlank()&&uri.port in 1..65535&&uri.path.isNullOrEmpty()) { "Некорректный адрес старого подключения CSQTT." }
            val supplied=query(uri)
            require(!supplied["hashes"].isNullOrBlank()||!supplied["vkHashes"].isNullOrBlank()) { "Старая ссылка CSQTT содержит адрес и пароль, но не содержит звонки. Добавьте ручные ссылки VK в параметр hashes; без них подключение создать нельзя." }
            val data=JSONObject(supplied as Map<*,*>).put("type",type).put("server",uri.host.replace("[","").replace("]","")).put("server_port",uri.port)
                .put("password",decode(uri.rawUserInfo)).put("name",supplied["name"]?:decode(uri.rawFragment.orEmpty()).ifBlank {"CSQTT"})
            return fromJson(source,data)
        }
        require(uri.userInfo == null && uri.port == -1 && uri.path.isNullOrEmpty())
        val q = query(uri, type == "qwdtt" && uri.host == "config")
        val data = JSONObject(q as Map<*, *>).put("type", type).put("name",q["name"] ?: q["remark"] ?: decode(uri.rawFragment.orEmpty()).ifBlank { type.uppercase(Locale.ROOT) })
        if (uri.host == "connect") {
            require(q["v"] == if (type == "csqtt") "2" else "1")
            data.put("server", q.getValue("host")).put("server_port", q.getValue(if (type == "csqtt") "peer" else "dtls").toInt())
            if (type == "wdtt") data.put("wg_port",q.getValue("wg").toInt()).put("local_port",q.getValue("local").toInt())
        } else {
            require(type == "qwdtt" && uri.host == "config")
            val peer = endpoint(q.getValue("peer"), 56000)
            data.put("server", peer.host.replace("[", "").replace("]", "")).put("server_port",(q["dtls_port"] ?: q["server_port"])?.toInt() ?: peer.port)
        }
        data.put("password", q["password"] ?: q["pass"] ?: "")
        return fromJson(source, data)
    }
    fun fromJson(source: String, input: JSONObject): ServerImport.Server {
        require(source.toByteArray(Charsets.UTF_8).size <= 65536)
        val raw = JSONObject(input.toString()); val type = raw.getString("type"); require(type in TYPES)
        if(Csqtt25Profile.isProfile(raw))return Csqtt25Profile.fromJson(source,raw)
        require(type!="csqtt"||!raw.has("csqtt_generation")||raw.opt("csqtt_generation")=="legacy") {"Для CSQTT 2.5 нужен отдельный совместимый движок. Старый CSQTT не будет использован вместо него."}
        val port = if (raw.has("server_port")) raw.get("server_port").toString().toInt() else if (type == "csqtt") 46000 else 56000
        val peer = if (raw.has("peer") && !raw.has("server")) endpoint(raw.getString("peer"),port)
            else endpoint(address(raw.getString("server"),port),port)
        val host = peer.host.replace("[", "").replace("]", "")
        val password = raw.optString("password",raw.optString("pass"))
        require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= 1024 && password.none { it.isISOControl() || it == '|' }) { "Некорректный пароль SWG." }
        val names = raw.optString("name",raw.optString("tag",type.uppercase(Locale.ROOT))).ifBlank { type.uppercase(Locale.ROOT) }
        require(names.length <= 256 && names.none(Char::isISOControl))
        val field = if(raw.has("hashes")) "hashes" else "vkHashes"
        val hashes = if(raw.opt(field) is JSONArray) (0 until raw.getJSONArray(field).length()).map { raw.getJSONArray(field).getString(it) }
            else raw.optString(field).split(Regex("[,;+\\s]+"))
        val calls = hashes.filter { it.isNotBlank() }.map(::callHash).distinct()
        val maxCalls = if (type == "qwdtt") 64 else 6
        val sourceMode=raw.optString("hash_source","manual")
        require(sourceMode in setOf("manual","auto")&&(sourceMode=="manual"||type=="qwdtt")) { "Автоматические звонки поддерживаются для QWDTT. Для этого протокола добавьте ручные ссылки." }
        val automatic=sourceMode=="auto"&&calls.isEmpty()
        val requestedCalls=if(raw.has("vk_call_count"))VkRelayProtocol.count(raw.get("vk_call_count").toString().toInt(),type)else 6
        require(automatic||calls.size in 1..maxCalls) { "Нужны от одной до $maxCalls ссылок или хешей VK-звонков." }
        val data = JSONObject().put("type",type).put("name",names).put("server",host).put("server_port",peer.port)
            .put("password",password).put("hashes",JSONArray(calls)).put("hash_source",if(automatic)"auto"else"manual")
        if(automatic||raw.has("vk_call_count"))data.put("vk_call_count",requestedCalls)
        val device = raw.optString("device_id")
        require(device.length <= 256 && device.none { it.isISOControl() || it == '|' })
        if(device.isNotEmpty()) data.put("device_id",device)
        fun number(key: String, fallback: Int, range: IntRange): Int = (if(raw.has(key)) raw.get(key).toString().toInt() else fallback).also { require(it in range) }
        val workers = number("workers", if(type=="csqtt"||type=="wdtt") 18 else 9, 9..162)
        require(workers % 9 == 0 && workers <= (if(automatic)requestedCalls else calls.size)*27 && (type!="csqtt" || workers<=126) && (type!="wdtt" || workers<=108)) { "Число потоков должно быть кратно 9 и соответствовать числу звонков." }
        data.put("workers",workers)
        fun option(key: String, default: String, permitted: Set<String>): String = raw.optString(key,default).also { require(it in permitted) }
        data.put("captcha_mode",option("captcha_mode","auto",setOf("auto","wv","rjs")))
        if(type!="wdtt") {
            val turn = raw.opt("turn_tcp")
            require(turn == null || turn is Boolean || turn.toString() in setOf("true","false","1","0"))
            data.put("turn_tcp", if(turn==null) type=="qwdtt" else turn==true || turn.toString() in setOf("true","1"))
            data.put("obfs",option("obfs","audio",setOf("audio","video"))).put("vk_anon_path",option("vk_anon_path","vkcalls",setOf("vkcalls","legacy")))
        }
        if(type=="qwdtt") {
            // qThrone exports its explicit mode. Older SWG/SpaceNeuro links do
            // not: their peer is the DTLS/WG port, not the separate RAW port.
            val mode = when(raw.optString("mode","wg").lowercase(Locale.ROOT)) { "raw", "rawtun" -> "raw"; "wg","vpn","socks" -> "wg"; else -> throw IllegalArgumentException("Неизвестный режим QWDTT.") }
            val resolver = raw.optString("go_dns","yandex")
            require(resolver in setOf("yandex","google","cloudflare","doh-yandex","doh-google","doh-cloudflare")) { "Неизвестный DNS для VK." }
            data.put("mode",mode).put("raw_port",number("raw_port",56003,1..65535)).put("go_dns",resolver)
        }
        if(type=="wdtt") {
            data.put("wg_port",number("wg_port",56001,1..65535)).put("local_port",number("local_port",9000,1..65535))
            require(data.getInt("wg_port")!=peer.port)
            data.put("fingerprint",option("fingerprint","android",setOf("android","chrome","firefox","safari","ios")))
        }
        return ServerImport.Server(source,names,type,host,peer.port,null,null,null,data)
    }
    fun privateConfig(profile: JSONObject, udpPort: Int, socksPort: Int, username: String, password: String, installation: String): JSONObject {
        if(Csqtt25Profile.isProfile(profile))return Csqtt25Profile.privateConfig(profile,udpPort,socksPort,username,password,installation)
        val parsed = fromJson(profile.toString(),profile).carrier!!; val type=parsed.getString("type")
        require(parsed.getString("hash_source")=="manual"&&parsed.getJSONArray("hashes").length()>0) { "Сначала получите звонки для автоматического подключения QWDTT." }
        val peerPort = if(type=="qwdtt"&&parsed.optString("mode")=="raw") parsed.getInt("raw_port") else parsed.getInt("server_port")
        require(udpPort in 1..65535 && socksPort in 1..65535 && username.toByteArray().size in 1..255 && password.toByteArray().size in 1..255 && installation.isNotBlank())
        return JSONObject(parsed.toString()).put("peer",address(parsed.getString("server"),peerPort)).put("listen","127.0.0.1:$udpPort")
            .put("socks","127.0.0.1:$socksPort").put("socks_user",username).put("socks_pass",password)
            .put("device_id", parsed.optString("device_id").ifEmpty { installation })
            .apply {remove("vk_call_count")} // Host-only call creation policy, not a native transport option.
    }
    fun share(profile: JSONObject): String {
        // Preserve all2.5 local settings and its generation, not an old v2 link
        // which carries no unambiguous transport generation or TURN options.
        if(Csqtt25Profile.isProfile(profile))return Csqtt25Profile.fromJson("share-csqtt25",profile).carrier!!.toString()
        val data = fromJson(profile.toString(), profile).carrier!!; val type=data.getString("type")
        val fields = linkedMapOf<String,String>()
        if(type=="qwdtt") fields["peer"]=address(data.getString("server"),data.getInt("server_port"))
        else { fields["v"]=if(type=="csqtt") "2" else "1";fields["host"]=data.getString("server");fields[if(type=="csqtt") "peer" else "dtls"]=data.getInt("server_port").toString() }
        if(type=="wdtt") { fields["wg"]=data.getInt("wg_port").toString();fields["local"]=data.getInt("local_port").toString() }
        fields["password"]=data.getString("password");fields["hashes"]=(0 until data.getJSONArray("hashes").length()).joinToString(if(type=="qwdtt")"\n"else",") { data.getJSONArray("hashes").getString(it) }
        if(data.getString("hash_source")=="auto") fields["hash_source"]="auto"
        for(key in listOf("name","mode","raw_port","workers","turn_tcp","obfs","go_dns","device_id","vk_anon_path","captcha_mode","fingerprint","vk_call_count")) if(data.has(key)) fields[key]=data.get(key).toString()
        return "$type://${if(type=="qwdtt") "config" else "connect"}?" + fields.entries.joinToString("&") { (key,value) ->
            // Original CSQTT splits RAW '+' separators before percent-decoding each call.
            val encoded=if(type=="csqtt"&&key=="hashes") (0 until data.getJSONArray("hashes").length()).joinToString("+"){URLEncoder.encode(data.getJSONArray("hashes").getString(it),"UTF-8").replace("+","%20")}
                else URLEncoder.encode(value,"UTF-8").replace("+","%20")
            "$key=$encoded"
        }
    }
    private fun callHash(value: String): String {
        val hash = if(value.startsWith("https://",true)||value.startsWith("http://",true)) {
            val uri=URI(value);require(uri.scheme=="https"&&uri.host?.lowercase(Locale.ROOT) in setOf("vk.ru","vk.com","m.vk.ru","m.vk.com")&&uri.userInfo==null&&uri.port==-1&&uri.path.startsWith("/call/join/"))
            decode(uri.rawPath.substringAfter("/call/join/").trimEnd('/'))
        } else value
        require(hash.matches(Regex("[A-Za-z0-9_-]{16,2048}={0,2}"))) { "Некорректная ссылка или хеш VK-звонка." }
        return hash
    }
    private fun address(host: String, port: Int) = "${if(host.contains(':')&&!host.startsWith('[')) "[$host]" else host}:$port"
    private fun endpoint(address: String, defaultPort: Int): URI {
        val uri=URI("udp://$address");val host=uri.host.orEmpty()
        require(host.isNotBlank()&&host.length<=255&&uri.userInfo==null&&uri.path.isNullOrEmpty()&&uri.query==null&&uri.fragment==null&&uri.port!=0&&uri.port<=65535)
        return if(uri.port==-1) URI("udp://${address(host,defaultPort)}") else uri
    }
}
