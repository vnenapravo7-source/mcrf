package org.mcrf.client

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Real imported profile + actual DAT/MRS source rules. Only unique owned cache files are removed. */
internal class OwnedImportedRoutingPlan private constructor(private val app:Context):AutoCloseable {
    private val namespace="profile-"+java.util.UUID.randomUUID().toString().replace("-","")
    private val roots=listOf("mcrf-geodata","mcrf-routing-providers").map {File(app.filesDir,it)}
    private var paths=emptyMap<String,String>()
    lateinit var state:JSONObject;private set
    fun source(address:String,port:Int,tag:String)=OwnedDirectVpnPlan.build(address,port,tag,false,false,true,state,paths)
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {
        fun prepare(app:Context,fixtures:Context,tag:String,kind:String):OwnedImportedRoutingPlan {
            require(kind in setOf("geoip","geosite"))
            val owned=OwnedImportedRoutingPlan(app)
            try {
                val host=OwnedDirectVpnPlan.hostname(tag)
                val document=JSONObject().put("Name","Owned imported $kind").put("GlobalProxy",true)
                    .put("RemoteDns","127.0.0.1:9").put("DomesticDns","127.0.0.1:9")
                    .put("Geoipurl","https://127.0.0.1:9/geoip.dat").put("Geositeurl","https://127.0.0.1:10/geosite.dat")
                    .put("DnsHosts",JSONObject().put(host,"198.18.0.2"))
                    .put(if(kind=="geoip")"DirectIp" else "DirectSites",JSONArray().put("$kind:private"))
                val parsed=ImportedRoutingProfile.parse(document.toString())
                owned.state=RouteRules.defaults().put("rules",JSONArray())
                parsed.settings.keys().forEach {key->owned.state.put(key,parsed.settings.get(key))}
                val geo=owned.state.getJSONObject("geoData").put("cacheNamespace",owned.namespace)
                val bytes=fixtures.assets.open("routing/loyalsoldier-private-$kind.dat").use {it.readBytes()}
                GeoDataConfig.validateDownload(bytes,kind,geo)
                GeoDataConfig.file(app,geo,kind).writeBytes(bytes)
                val provider=JSONObject().put("tag","mcrf-import-0").put("behavior","domain").put("format","mrs")
                    .put("url","https://127.0.0.1:11/steam.mrs").put("interval",300)
                geo.put("ruleProviders",JSONArray().put(provider))
                val root=owned.roots[1];check(root.isDirectory||root.mkdir())
                val converted=fixtures.assets.open("routing/roscom-steam.mrs").use {MihomoMrs.decode(it.readBytes(),"domain")}
                File(root,"${owned.namespace}-${owned.sha(provider.toString())}.json").writeText(converted.toString())
                owned.state.getJSONArray("rules").put(JSONObject().put("id","owned-mrs-block").put("name","Owned Steam MRS")
                    .put("text","").put("mode","addresses").put("target","block").put("enabled",true)
                    .put("nativeMatch",JSONObject().put("rule_set",JSONArray().put("mcrf-import-0"))))
                owned.paths=GeoDataConfig.resolve(app,owned.state)
                check(owned.paths.keys.containsAll(listOf("geo-0","mcrf-import-0")))
                return owned
            }catch(e:Throwable){owned.close();throw e}
        }
    }
    override fun close() {
        paths.values.map(::File).forEach {file->
            require(roots.any {it.canonicalFile==file.canonicalFile.parentFile}&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
            check(!file.exists()||file.delete())
        }
        roots.forEach {root->root.listFiles().orEmpty().filter {it.name.startsWith(namespace+"-")||it.name=="$namespace.lock"}.forEach {file->
            require(file.canonicalFile.parentFile==root.canonicalFile&&!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.isFile)
            check(file.delete())
        }}
    }
}
