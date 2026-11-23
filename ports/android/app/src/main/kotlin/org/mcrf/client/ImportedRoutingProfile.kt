package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Data-only import. Parsing never changes the selected profile or accesses the network. */
internal object ImportedRoutingProfile {
    data class Parsed(val name:String,val settings:JSONObject)
    fun parse(raw:String):Parsed {
        val text=raw.trim();require(text.toByteArray(Charsets.UTF_8).size<=ServerImport.MAX_BYTES)
        if(!text.startsWith("{")&&!HappRoutingLinks.isRouting(text))return ClashRoutingProfile.parse(text)
        val document=if(HappRoutingLinks.isRouting(text)) {
            HappRoutingLinks.parse(text).document?:error("Ссылка отключения не является профилем маршрутизации.")
        } else {
            CompatibilityImportFormats.validateJsonDepth(text)
            val token=org.json.JSONTokener(text)
            (token.nextValue() as? JSONObject?:error("Ожидается документ маршрутизации Happ / Incy.")).also {require(token.nextClean()=='\u0000')}
        }
        if(document.has("rules")&&document.has("dns"))return ClashRoutingProfile.parse(text)
        val name=document.getString("Name").trim()
        require(name.isNotEmpty()&&name.length<=40&&name.none(Char::isISOControl)){"Название профиля: 1–40 символов."}
        val geo=GeoDataConfig.fromProvider(document)
        val remote=resolver(document,"Remote","RemoteDns")
        val domestic=resolver(document,"Domestic","DomesticDns")
        val advanced=AdvancedDnsConfig.defaults().put("enabled",true).put("direct",domestic)
            .put("fakeIp",flag(document,"FakeDNS",false))
        val hosts=StringBuilder()
        document.optJSONObject("DnsHosts")?.let {mapping->
            require(mapping.length()<=1024)
            mapping.keys().forEach {domain->
                val value=mapping.get(domain)
                val ips=if(value is JSONArray)(0 until value.length()).map(value::getString) else listOf(value as String)
                require(ips.size in 1..16)
                ips.forEach {ip->require(RouteRules.literal(ip)!=null);hosts.append(ip).append(' ').append(domain).append('\n')}
            }
        }
        advanced.put("answers",hosts.toString());AdvancedDnsConfig.validate(advanced)
        val strategy=document.optString("DomainStrategy","IPIfNonMatch")
        require(strategy == "IPIfNonMatch"){"Неизвестная стратегия доменов профиля."}
        return Parsed(name,JSONObject().put("rules",JSONArray()).put("geoData",geo)
            .put("defaultTarget",if(flag(document,"GlobalProxy",true))"proxy" else "direct")
            .put("globalDns",remote).put("advancedDns",advanced))
    }
    private fun flag(document:JSONObject,key:String,fallback:Boolean):Boolean {
        if(!document.has(key))return fallback
        return when(document.get(key).toString().lowercase()) {"true"->true;"false"->false;else->error("Неверный флаг $key.")}
    }
    private fun resolver(document:JSONObject,prefix:String,legacy:String):JSONObject {
        val protocol=document.optString(prefix+"DNSType","UDP")
        require(protocol in setOf("UDP","DoH","DoT","DoQ","DoH3")){"Неподдерживаемый протокол DNS: $protocol."}
        val address=if(protocol=="UDP")document.getString(legacy) else document.optString(prefix+"DNSDomain").ifBlank {document.getString(legacy)}
        val settings=JSONObject().put("provider","Свой").put("protocol",protocol).put("custom",address)
        document.optString(prefix+"DNSIP").takeIf {it.isNotBlank()}?.let {settings.put("endpointIp",it)}
        DnsConfig.build(settings,"d","b")
        return settings
    }
}
