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
        if(type=="csqtt")q["csqtt_generation"]?.let {data.put("csqtt_generation",it)}
        if(type=="wdtt")for(key in listOf("vkcalls_preflight","turn_stream_first","rt_masque"))q[key]?.let {value->
            require(value in setOf("true","false")) {"Некорректный переключатель WDTT: $key"}
            data.put(key,value=="true")
        }
        if(type=="csqtt")q["extra_streams"]?.let {value->
            require(value in setOf("true","false")) {"Некорректный переключатель дополнительных потоков."}
            data.put("extra_streams",value=="true")
        }
        return fromJson(source, data)
    }
    fun fromJson(source: String, input: JSONObject): ServerImport.Server {
        require(source.toByteArray(Charsets.UTF_8).size <= 65536)
        val raw = JSONObject(input.toString()); val type = raw.getString("type"); require(type in TYPES)
        if(Csqtt25Profile.isProfile(raw))return Csqtt25Profile.fromJson(source,raw)
        if(type=="csqtt"){require(CsqttGenerations.family(raw) in CsqttGenerations.legacy);CsqttGenerations.requireLegacyOptions(raw)}
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
        val accountMode=type=="csqtt"&&raw.optString("vk_anon_path")=="auto_js"
        if(type=="csqtt"&&raw.has("vk_hash_mode"))require(raw.opt("vk_hash_mode")==if(accountMode)"auto_js"else"manual") {"Режим хешей CSQTT должен соответствовать режиму получения TURN-данных: Авто VK использует свои автоматические звонки."}
        val sourceMode=raw.optString("hash_source","manual")
        require(sourceMode in setOf("manual","auto")&&(sourceMode=="manual"||type=="qwdtt")) { "Автоматические звонки поддерживаются для QWDTT. Для этого протокола добавьте ручные ссылки." }
        val automatic=sourceMode=="auto"&&calls.isEmpty()
        val requestedCalls=if(raw.has("vk_call_count"))VkRelayProtocol.count(raw.get("vk_call_count").toString().toInt(),type)else 6
        require(accountMode||automatic||calls.isNotEmpty()) { "Добавьте ссылки или хеши VK-звонков." }
        val data = JSONObject().put("type",type).put("name",names).put("server",host).put("server_port",peer.port)
            .put("password",password).put("hashes",JSONArray(calls)).put("hash_source",if(automatic)"auto"else"manual")
        if(type=="csqtt"&&raw.has("csqtt_generation"))data.put("csqtt_generation",CsqttGenerations.family(raw).id)
        if(automatic||raw.has("vk_call_count"))data.put("vk_call_count",requestedCalls)
        val device = raw.optString("device_id")
        require(device.length <= 256 && device.none { it.isISOControl() || it == '|' })
        if(device.isNotEmpty()) data.put("device_id",device)
        fun number(key: String, fallback: Int, range: IntRange): Int = (if(raw.has(key)) raw.get(key).toString().toInt() else fallback).also { require(it in range) }
        val workers = number("workers", if(type=="csqtt"||type=="wdtt") 18 else 9, 9..162)
        require(workers % 9 == 0 && (accountMode||workers <= (if(automatic)requestedCalls else calls.size)*27) && (type!="csqtt" || workers<=126) && (type!="wdtt" || workers<=108)) { "Число потоков должно быть кратно 9 и соответствовать числу звонков." }
        data.put("workers",workers)
        if(raw.has(CarrierStreamDefaults.OVERRIDE)) {
            data.put(CarrierStreamDefaults.OVERRIDE,raw.get(CarrierStreamDefaults.OVERRIDE))
            CarrierStreamDefaults.validateOverride(data)
        }
        if(type=="csqtt") {
            require(!raw.has("extra_streams")||raw.get("extra_streams") is Boolean) {"Некорректный переключатель дополнительных потоков."}
            val extra=raw.optBoolean("extra_streams",workers>72)
            require(extra||workers<=72) {"Для более 72 потоков включите дополнительные потоки."}
            data.put("extra_streams",extra)
        }
        fun option(key: String, default: String, permitted: Set<String>): String = raw.optString(key,default).also { require(it in permitted) }
        data.put("captcha_mode",option("captcha_mode","auto",setOf("auto","wv","rjs")))
        if(type!="wdtt") {
            val turn = raw.opt("turn_tcp")
            require(turn == null || turn is Boolean || turn.toString() in setOf("true","false","1","0"))
            data.put("turn_tcp", if(turn==null) type=="qwdtt" else turn==true || turn.toString() in setOf("true","1"))
            data.put("obfs",option("obfs","audio",setOf("audio","video"))).put("vk_anon_path",option("vk_anon_path","vkcalls",if(type=="csqtt")setOf("vkcalls","legacy","auto_js")else setOf("vkcalls","legacy")))
        }
        if(type=="csqtt") {
            data.put("vk_hash_mode",if(accountMode)"auto_js"else"manual")
            data.put("fingerprint",option("fingerprint","chrome",setOf("firefox","chrome","safari")))
        }
        if(type=="qwdtt") {
            data.put("fingerprint",option("fingerprint","chrome",setOf("chrome","firefox","safari")))
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
            for(key in listOf("vkcalls_preflight","turn_stream_first","rt_masque")) {
                require(!raw.has(key)||raw.get(key) is Boolean) {"Некорректный переключатель WDTT: $key"}
                data.put(key,raw.optBoolean(key,key=="vkcalls_preflight"))
            }
            val sni=raw.optString("turn_sni")
            require(sni.isEmpty()||sni.length<=253&&sni.split('.').all {it.length in 1..63&&it.matches(Regex("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))}) {"SNI TURN/TLS: имя домена без схемы, порта и пути."}
            data.put("turn_sni",sni)
            require(!data.optBoolean("rt_masque")||sni.isNotEmpty()) {"Для MASQUE укажите внешний SNI TURN/TLS."}
            val clients=raw.optString("client_ids")
            require(clients.isEmpty()||clients.length<=1024&&clients.split(',').let {ids->ids.size<=32&&ids.all {it.trim().matches(Regex("[0-9]{1,20}"))}}) {"Client IDs: числовые ID через запятую, до 32 значений."}
            data.put("client_ids",clients)
            val customId=raw.optString("custom_vk_client_id")
            val customSecret=raw.optString("custom_vk_client_secret")
            require(customId.isEmpty()&&customSecret.isEmpty()||customId.matches(Regex("[0-9]{1,20}"))&&customSecret.isNotBlank()&&customSecret.toByteArray(Charsets.UTF_8).size<=4096&&customSecret.none(Char::isISOControl)) {"Собственные реквизиты VK: укажите числовой Client ID и Client secret вместе либо оставьте оба поля пустыми."}
            data.put("custom_vk_client_id",customId).put("custom_vk_client_secret",customSecret)
        }
        return ServerImport.Server(source,names,type,host,peer.port,null,null,null,data)
    }
    fun privateConfig(profile: JSONObject, udpPort: Int, socksPort: Int, username: String, password: String, installation: String): JSONObject {
        if(Csqtt25Profile.isProfile(profile))return Csqtt25Profile.privateConfig(profile,udpPort,socksPort,username,password,installation)
        val parsed = fromJson(profile.toString(),profile).carrier!!; val type=parsed.getString("type")
        val accountMode=type=="csqtt"&&parsed.optString("vk_anon_path")=="auto_js"
        require(accountMode||parsed.getString("hash_source")=="manual"&&parsed.getJSONArray("hashes").length()>0) { "Сначала получите звонки для автоматического подключения QWDTT." }
        val peerPort = if(type=="qwdtt"&&parsed.optString("mode")=="raw") parsed.getInt("raw_port") else parsed.getInt("server_port")
        require(udpPort in 1..65535 && socksPort in 1..65535 && username.toByteArray().size in 1..255 && password.toByteArray().size in 1..255 && installation.isNotBlank())
        return JSONObject(parsed.toString()).put("peer",address(parsed.getString("server"),peerPort)).put("listen","127.0.0.1:$udpPort")
            .put("socks","127.0.0.1:$socksPort").put("socks_user",username).put("socks_pass",password)
            .put("device_id", parsed.optString("device_id").ifEmpty { installation })
            .apply {
                remove("vk_call_count") // Host-only call creation policy, not a native transport option.
                remove("extra_streams") // Editor limit; native receives the explicit worker count.
                remove(CarrierStreamDefaults.OVERRIDE)
                if(accountMode) {
                    val token=profile.optString("vk_runtime_token")
                    require(token.isNotBlank()&&token.length<=8192&&token.none(Char::isISOControl)) {"Войдите в VK Turn перед подключением Авто VK."}
                    put("vk_runtime_token",token)
                    put("hashes",JSONArray()) // Auto VK owns matching calls; never mix supplied hashes.
                }
            }
    }
    fun share(profile: JSONObject): String {
        // Preserve all2.5 local settings and its generation, not an old v2 link
        // which carries no unambiguous transport generation or TURN options.
        if(Csqtt25Profile.isProfile(profile))return Csqtt25Profile.fromJson("share-csqtt25",profile).carrier!!.toString()
        val data = fromJson(profile.toString(), profile).carrier!!; val type=data.getString("type")
        if(type=="csqtt"&&data.has("csqtt_generation"))return data.toString() // A legacy v2 URI has no unambiguous wire-family field.
        if(data.has(CarrierStreamDefaults.OVERRIDE))return data.toString() // Preserve explicit local count without inventing a provider URI parameter.
        val fields = linkedMapOf<String,String>()
        if(type=="qwdtt") fields["peer"]=address(data.getString("server"),data.getInt("server_port"))
        else { fields["v"]=if(type=="csqtt") "2" else "1";fields["host"]=data.getString("server");fields[if(type=="csqtt") "peer" else "dtls"]=data.getInt("server_port").toString() }
        if(type=="wdtt") { fields["wg"]=data.getInt("wg_port").toString();fields["local"]=data.getInt("local_port").toString() }
        fields["password"]=data.getString("password");fields["hashes"]=(0 until data.getJSONArray("hashes").length()).joinToString(if(type=="qwdtt")"\n"else",") { data.getJSONArray("hashes").getString(it) }
        if(data.getString("hash_source")=="auto") fields["hash_source"]="auto"
        for(key in listOf("name","mode","raw_port","workers","turn_tcp","obfs","go_dns","device_id","vk_anon_path","captcha_mode","fingerprint","vk_call_count","vkcalls_preflight","turn_stream_first","turn_sni","client_ids","extra_streams","rt_masque")) if(data.has(key)) fields[key]=data.get(key).toString()
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
