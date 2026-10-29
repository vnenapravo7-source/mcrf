package org.mcrf.client

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** Converts the SWG panel's public, self-contained WDTT/CSQTT targets into app import links. */
internal object SwgInviteSubscription {
    private const val MAX_BYTES=1024*1024

    fun address(value:String):String {
        val uri=URI(value.trim())
        val fragment=uri.rawFragment.orEmpty()
        val key=runCatching {Base64.getUrlDecoder().decode(fragment.padEnd((fragment.length+3)/4*4,'='))}.getOrNull()
        require(uri.scheme=="https"&&uri.host in setOf("vnenapravo7.ru","outright7.site")&&uri.rawUserInfo==null&&uri.port in setOf(-1,443)&&
            uri.rawQuery==null&&Regex("/wdtt/sub/[A-Za-z0-9_-]{16,256}").matches(uri.rawPath.orEmpty())&&key?.size==32){"Некорректная ссылка набора обхода."}
        return uri.toString()
    }

    fun fetch(value:String,open:(URI)->HttpsURLConnection={it.toURL().openConnection() as HttpsURLConnection}):String {
        val source=URI(address(value));val token=source.rawPath.substringAfterLast('/')
        val endpoint=URI(source.scheme,source.rawAuthority,source.rawPath.substringBeforeLast('/')+"/api/"+token,null,null)
        val connection=open(endpoint)
        try {
            connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=15_000;connection.readTimeout=20_000
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("User-Agent","MCRF-Android")
            require(connection.responseCode==200){"Набор обхода временно недоступен."}
            require(connection.contentLengthLong<=MAX_BYTES){"Набор обхода слишком большой."}
            val raw=connection.inputStream.use {ImportIO.read(it)};require(raw.size<=MAX_BYTES){"Набор обхода слишком большой."}
            return links(JSONObject(String(raw,Charsets.UTF_8)))
        } finally {connection.disconnect()}
    }

    internal fun links(response:JSONObject):String {
        require(response.optBoolean("ok")){"Набор обхода не готов."}
        val data=response.getJSONObject("data");val vk=mutableListOf<String>()
        data.optJSONArray("vk_links")?.let {array->for(i in 0 until array.length())stripVk(array.optString(i)).takeIf {it.isNotBlank()}?.let(vk::add)}
        val result=mutableListOf<String>();val peers=data.getJSONArray("peers")
        for(i in 0 until peers.length()){
            val targets=peers.getJSONObject(i).optJSONArray("targets")?:continue
            for(j in 0 until targets.length()){
                val target=targets.getJSONObject(j)
                target.optJSONObject("wdtt")?.let {wd->
                    val hashes=hashes(wd.optString("vk_hash"),vk)
                    if(wd.optString("fork")=="qwdtt")result+=qwdtt(wd,hashes) else result+=wdtt(wd,hashes)
                }
                target.optJSONObject("csqtt")?.let {cs->result+=csqtt(cs,hashes(cs.optString("vk_hash"),vk))}
            }
        }
        require(result.isNotEmpty()&&result.size<=100){"В наборе обхода нет подключений."}
        return org.json.JSONArray().apply {result.distinct().forEach {item->put(if(item.startsWith('{'))JSONObject(item)else item)}}.toString()
    }

    private fun hashes(primary:String,links:List<String>)=(listOf(stripVk(primary))+links).filter {it.isNotBlank()}.distinct()
    private fun stripVk(value:String)=value.trim().substringAfterLast('/').substringBefore('?')
    private fun enc(value:Any?)=URLEncoder.encode(value?.toString().orEmpty(),Charsets.UTF_8.name()).replace("+","%20")
    private fun qwdtt(w:JSONObject,hashes:List<String>):String=buildString {
        append("qwdtt://config?peer=").append(enc(w.getString("endpoint_host")+":"+w.optInt("dtls_port",56000)))
        if(hashes.isNotEmpty())append("&hashes=").append(enc(hashes.joinToString(",")))
        append("&port=").append(enc(w.optInt("tun_port",9000))).append("&pass=").append(enc(w.getString("password")))
    }
    private fun wdtt(w:JSONObject,hashes:List<String>):String=buildString {
        append("wdtt://connect?v=1&host=").append(enc(w.getString("endpoint_host"))).append("&dtls=").append(enc(w.optInt("dtls_port",56000)))
        append("&wg=").append(enc(w.optInt("wg_port",56001))).append("&local=").append(enc(w.optInt("tun_port",9000))).append("&password=").append(enc(w.getString("password")))
        if(hashes.isNotEmpty())append("&hashes=").append(enc(hashes.joinToString(",")))
    }
    private fun csqtt(c:JSONObject,hashes:List<String>):String {
        if(c.optString("version").startsWith("2.5"))return JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
            .put("csqtt_implementation","swg-panel-2.5.1").put("server",c.getString("endpoint_host")).put("server_port",c.getInt("port"))
            .put("password",c.getString("password")).put("hashes",org.json.JSONArray(hashes.take(6)))
            .put("workers",(hashes.size.coerceAtLeast(1)*10).coerceAtMost(20)).toString()
        return buildString {
        append("csqtt://connect?v=2&host=").append(enc(c.getString("endpoint_host"))).append("&peer=").append(c.getInt("port")).append("&password=").append(enc(c.getString("password")))
        if(hashes.isNotEmpty())append("&hashes=").append(hashes.take(6).joinToString("+"){enc(it)})
        }
    }
}
