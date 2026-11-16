package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

/** WARP is identified by its peer key, never by a user-editable display name. */
object WarpProfile {
    const val PEER_KEY="bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    data class Trace(val country:String,val node:String)
    fun isWarp(entry:ServerImport.Server):Boolean=entry.protocol in setOf("wireguard","awg")&&entry.endpoint?.optJSONArray("peers")?.let {it.length()==1&&it.getJSONObject(0).optString("public_key")==PEER_KEY}==true
    fun account(source:String):JSONObject {
        require(source.toByteArray(Charsets.UTF_8).size<=32768){"Аккаунт WARP больше 32 КБ."}
        val result=JSONObject(source)
        require(result.optString("peer_public_key")==PEER_KEY){"Это не аккаунт Cloudflare WARP."}
        val privateKey=result.getString("private_key")
        val privateBytes=Base64.getDecoder().decode(privateKey)
        require(privateBytes.size==32&&privateBytes.any {it!=0.toByte()}&&Base64.getEncoder().encodeToString(privateBytes)==privateKey){"Некорректный ключ WARP."}
        var addresses=0
        for(key in listOf("ipv4","ipv6")) {
            val value=result.optString(key);if(value.isEmpty())continue
            val address=RouteRules.literal(value) ?: throw IllegalArgumentException("Некорректный адрес WARP.")
            require((address is java.net.Inet4Address)==(key=="ipv4")&&!address.isAnyLocalAddress&&!address.isLoopbackAddress){"Некорректный адрес WARP."};addresses++
        }
        require(addresses>0){"Аккаунт WARP не содержит адреса."}
        require(result.optString("id").length<=256&&result.optString("token").length<=4096){"Некорректные данные аккаунта WARP."}
        result.optJSONArray("reserved")?.let {values ->require(values.length()==3&&(0..2).all {i -> val value=values.get(i);value is Number&&value.toDouble()==value.toInt().toDouble()&&value.toInt() in 0..255}) {"Некорректные reserved-байты WARP."}}
        require(!result.has("reserved")||result.optJSONArray("reserved")!=null){"Некорректные reserved-байты WARP."}
        // WarpScout registers an optional auxiliary identity even without -through.
        // Its presence is account metadata, not a request to enable a nested tunnel.
        require(!result.has("outer")||result.isNull("outer")||result.optJSONObject("outer")!=null){"Некорректные дополнительные данные аккаунта WARP."}
        return result
    }
    fun fromEntry(entry:ServerImport.Server):String {
        require(isWarp(entry)){"Выбери профиль WARP."}
        val endpoint=entry.endpoint!!;val value=JSONObject().put("id","").put("token","").put("private_key",endpoint.getString("private_key")).put("peer_public_key",PEER_KEY)
        val addresses=endpoint.getJSONArray("address")
        repeat(addresses.length()) {val address=addresses.getString(it).substringBefore('/');value.put(if(address.contains(':'))"ipv6" else "ipv4",address)}
        endpoint.getJSONArray("peers").getJSONObject(0).optJSONArray("reserved")?.let {value.put("reserved",JSONArray(it.toString()))}
        return account(value.toString()).toString()
    }
    fun country(value:String):String {require(value in setOf("any","foreign")||value in Locale.getISOCountries().toSet()){ "Выбери страну выхода WARP." };return value}
    fun request(operation:String,account:String,protocol:String,country:String,excludeDme:Boolean,extended:Boolean):JSONObject {
        require(excludeDme){"Узел DME исключён во всех режимах."}
        require(operation in setOf("register","scan")&&protocol in setOf("auto","wg","awg"))
        country(country);if(operation=="scan")account(account)
        return JSONObject().put("operation",operation).put("account",account).put("protocol",protocol).put("country",country).put("excludeDme",excludeDme).put("extended",extended)
    }
    fun trace(source:String,country:String,excludeDme:Boolean):Trace {
        require(excludeDme){"Узел DME исключён во всех режимах."}
        country(country);require(source.toByteArray(Charsets.UTF_8).size<=8192){"Ответ проверки WARP слишком большой."}
        val fields=linkedMapOf<String,String>()
        source.lineSequence().forEach {line ->val split=line.trim().split('=',limit=2);if(split.size==2&&split[0] in setOf("warp","loc","colo")){require(fields.put(split[0],split[1])==null){"Неоднозначный ответ проверки WARP."}}}
        require(fields["warp"] in setOf("on","plus")){"Выход не подтвердил WARP. Старый профиль сохранён."}
        val region=fields["loc"].orEmpty();val node=fields["colo"].orEmpty()
        require(region in Locale.getISOCountries().toSet()&&node.matches(Regex("[A-Z]{3}"))){"Выход не сообщил страну и узел."}
        require(country=="any"||country=="foreign"&&region!="RU"||country==region){"Выход не соответствует выбранной стране. Старый профиль сохранён."}
        require(!excludeDme||node!="DME"){"Узел DME исключён. Старый профиль сохранён."}
        return Trace(region,node)
    }
    fun prepare(config:String,rawAccount:String,trace:Trace?=null):ServerImport.Server {
        val account=account(rawAccount);WireGuardProfile.parse(config);val entry=ServerImport.read(config).single();require(isWarp(entry)){"Сканер вернул не WARP."}
        require(entry.endpoint!!.getString("private_key")==account.getString("private_key")){"Сканер изменил ключ аккаунта. Старый профиль сохранён."}
        val addresses=listOf("ipv4","ipv6").mapNotNull {key->account.optString(key).takeIf {it.isNotEmpty()}?.let {it+if(key=="ipv4")"/32" else "/128"}}
        val named=Regex("(?im)^\\s*Name\\s*=").containsMatchIn(config)
        val name=if(trace!=null)"WARP · ${trace.country} · ${trace.node} · ${entry.protocol.uppercase(Locale.ROOT)}" else if(named)entry.name else "WARP · ${if(entry.protocol=="awg")"AWG" else "WireGuard"}"
        var prepared=config.replace(Regex("(?im)^\\s*Address\\s*=.*$"),"Address = ${addresses.joinToString(", ")}")
        prepared=prepared.replace(Regex("(?im)^\\s*Name\\s*=.*(?:\\r?\\n|$)"),"").replaceFirst(Regex("(?im)^\\[Interface\\]"),"[Interface]\nName = $name")
        account.optJSONArray("reserved")?.let {values ->
            prepared=prepared.replace(Regex("(?im)^\\s*Reserved\\s*=.*(?:\\r?\\n|$)"),"").replaceFirst(Regex("(?im)^\\[Peer\\]"),"[Peer]\nReserved = ${(0..2).joinToString(", ") {values.getInt(it).toString()}}")
        }
        return ServerImport.read(prepared).single()
    }
    fun initial(rawAccount:String):ServerImport.Server {
        val account=account(rawAccount)
        val ipv4=account.optString("ipv4")
        val address=if(ipv4.isNotEmpty())"$ipv4/32" else "${account.getString("ipv6")}/128"
        return prepare("[Interface]\nPrivateKey = ${account.getString("private_key")}\nAddress = $address\nMTU = 1280\n[Peer]\nPublicKey = $PEER_KEY\nEndpoint = engage.cloudflareclient.com:2408\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n",rawAccount)
    }
}
