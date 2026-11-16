package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Imports routing/DNS only. Proxy credentials, listeners and remote proxy providers are not executed. */
internal object ClashRoutingProfile {
    fun parse(text:String):ImportedRoutingProfile.Parsed {
        val root=RoutingYaml.parse(text);val rules=root["rules"] as? List<*>?:error("В Clash / Mihomo нет списка rules.")
        require(rules.size in 1..1024)
        val groups=(root["proxy-groups"] as? List<*>).orEmpty().map {it as Map<*,*>}.associateBy {it["name"] as String}
        val proxies=(root["proxies"] as? List<*>).orEmpty().map {it as Map<*,*>}.map {it["name"] as String}.toSet()
        fun target(name:String,seen:Set<String> = emptySet()):String? {
            require(name !in seen&&seen.size<=32){"Цикл в группах Clash."}
            return when(name.uppercase()) {
                "DIRECT"->"direct";"REJECT","REJECT-DROP"->"block";"PASS"->null
                else->{val g=groups[name]
                    if(g==null){require(name in proxies||name=="PROXY"){"Неизвестный выход Clash: $name."};"proxy"}
                    else if(g["type"]=="select") {
                        val first=(g["proxies"] as? List<*>)?.firstOrNull() as? String
                        if(first==null)"proxy" else target(first,seen+name)
                    } else {require(g["type"] in setOf("url-test","fallback","load-balance")){"Неизвестная группа Clash."};"proxy"}
                }
            }
        }
        val geo=GeoDataConfig.defaults();val providers=JSONArray();val providerTags=linkedMapOf<String,String>()
        (root["rule-providers"] as? Map<*,*>).orEmpty().forEach {(key,value)->
            require(key is String&&value is Map<*,*>&&providers.length()<128)
            val p=JSONObject(value);val tag="mcrf-import-${providers.length()}"
            val kind=p.optString("behavior","classical");require(kind in setOf("domain","ipcidr","classical"))
            val format=p.optString("format","yaml");require(format in setOf("yaml","text","mrs")&&(format!="mrs"||kind!="classical"))
            val item=JSONObject().put("tag",tag).put("behavior",kind).put("format",format)
            item.put("interval",p.optLong("interval",86400).coerceIn(300,604800))
            when(p.optString("type","http")) {
                "http"->{RemoteRoutingConfig.https(p.getString("url"));item.put("url",p.getString("url"))}
                "inline"->{val a=p.getJSONArray("payload");require(a.length() in 1..100000);item.put("payload",a)}
                else->error("Локальные file-провайдеры Clash нужно заменить HTTPS или inline-списками.")
            }
            providerTags[key]=tag;providers.put(item)
        }
        if(providers.length()>0)geo.put("ruleProviders",providers)
        val rows=JSONArray();var final="proxy";var matched=false
        rules.forEach {raw->
            require(raw is String&&!matched){"MATCH должен завершать список Clash."}
            val tokens=split(raw);require(tokens.size>=2)
            val noResolve=tokens.last()=="no-resolve";val last=if(noResolve)tokens.lastIndex-1 else tokens.lastIndex
            val type=tokens.first().uppercase();val destination=tokens[last];val selected=target(destination)
            if(type=="MATCH"||type=="FINAL") {require(last==1&&selected!=null);final=selected;matched=true}
            else if(selected!=null) {
                val body=tokens.subList(1,last).joinToString(",")
                val match=match(type,body,providerTags,geo)
                rows.put(JSONObject().put("id","clash-${rows.length()}").put("name",type).put("text","").put("mode","addresses")
                    .put("target",selected).put("nativeMatch",match).put("enabled",true).apply {if(destination.uppercase()=="REJECT-DROP")put("reject_method","drop")})
            }
        }
        val dns=root["dns"] as? Map<*,*>?:error("Профиль Clash / Mihomo должен содержать DNS.")
        fun resolver(key:String,fallback:String?=null):JSONObject {
            val list=dns[key] as? List<*> ?: fallback?.let {dns[it] as? List<*>}?:dns["nameserver"] as? List<*>?:error("Нет DNS $key.")
            require(list.isNotEmpty()&&list.size<=16)
            // Native DNS has one default server; preserve all alternatives in the custom DNS section below.
            return dnsResolver(list.first() as String,key!="nameserver")
        }
        require(!dns.containsKey("nameserver-policy")&&!dns.containsKey("fallback-filter")){"Профиль содержит отдельные политики DNS Clash; такой набор ещё не поддерживается без потери условий."}
        val advanced=AdvancedDnsConfig.defaults().put("enabled",true).put("direct",resolver("direct-nameserver","default-nameserver"))
        advanced.put("fakeIp",dns["enhanced-mode"]=="fake-ip")
        if(dns["ipv6"]==false)advanced.put("strategy","ipv4_only").put("remoteNoAAAA",true).put("directNoAAAA",true)
        val hosts=StringBuilder();(root["hosts"] as? Map<*,*>).orEmpty().forEach {(k,v)->
            val ips=if(v is List<*>)v else listOf(v)
            ips.forEach {ip->require(ip is String&&RouteRules.literal(ip)!=null);hosts.append(ip).append(' ').append(k).append('\n')}
        };advanced.put("answers",hosts.toString());AdvancedDnsConfig.validate(advanced)
        (root["geox-url"] as? Map<*,*>)?.let {urls->for(kind in listOf("geoip","geosite"))urls[kind]?.let {geo.put("${kind}Url",it as String).put("source","custom")}}
        val name=(root["profile-name"] as? String?:root["name"] as? String?:"Clash / Mihomo").trim().take(40)
        return ImportedRoutingProfile.Parsed(name,JSONObject().put("rules",rows).put("defaultTarget",final).put("geoData",geo)
            .put("globalDns",resolver("nameserver")).put("advancedDns",advanced))
    }
    internal fun dnsResolver(raw:String,direct:Boolean=false):JSONObject {
        val fragment=raw.substringAfter('#',"")
        require(fragment.isEmpty()||fragment.uppercase()==if(direct)"DIRECT"else"PROXY"){"Именованный маршрут DNS Clash требует явной привязки."}
        val address=raw.substringBefore('#')
        require(address.isNotBlank())
        val protocol=when(address.substringBefore("://","udp")){"https"->"DoH";"tls"->"DoT";"quic"->"DoQ";"h3"->"DoH3";"udp"->"UDP";else->error("Неподдерживаемый DNS Clash.")}
        return JSONObject().put("provider","Свой").put("protocol",protocol).put("custom",address).also {DnsConfig.build(it,"d","b")}
    }
    internal fun split(text:String):List<String> {
        val result=ArrayList<String>();var depth=0;var from=0
        text.forEachIndexed {i,c->when(c){'('->depth++;')'->{depth--;require(depth>=0)};','->if(depth==0){result.add(text.substring(from,i).trim());from=i+1}}}
        require(depth==0);result.add(text.substring(from).trim());return result
    }
    internal fun match(type:String,value:String,providers:Map<String,String>,geo:JSONObject?=null,depth:Int=0):JSONObject {
        require(depth<=16&&value.length<=262144){"Слишком сложное правило Clash."}
        if(type=="PROCESS-NAME"&&runCatching {RouteRules.validatePackage(value)}.isSuccess) {
            // Android's non-root owner lookup supplies package names; retain an
            // actual process-name match too when that metadata is available.
            return JSONObject().put("type","logical").put("mode","or").put("rules",JSONArray()
                .put(JSONObject().put("process_name",JSONArray().put(value)))
                .put(JSONObject().put("package_name",JSONArray().put(value))))
                .also {RemoteRoutingImports.validateMatch(it)}
        }
        if(type in setOf("AND","OR","NOT")) {
            require(value.startsWith('(')&&value.endsWith(')'))
            val children=(if(type=="NOT")listOf(value)else split(value.substring(1,value.length-1))).map {child->
                val inner=child.removeSurrounding("(",")");val p=inner.indexOf(',');require(p>0);match(inner.substring(0,p).uppercase(),inner.substring(p+1),providers,geo,depth+1)
            }
            require(children.isNotEmpty()&&(type!="NOT"||children.size==1))
            return JSONObject().put("type","logical").put("mode",if(type=="OR")"or" else "and").put("rules",JSONArray(children)).apply {if(type=="NOT")put("invert",true)}
        }
        val key=when(type) {
            "DOMAIN"->"domain";"DOMAIN-SUFFIX"->"domain_suffix";"DOMAIN-KEYWORD"->"domain_keyword";"DOMAIN-REGEX"->"domain_regex"
            "IP-CIDR","IP-CIDR6"->"ip_cidr";"SRC-IP-CIDR"->"source_ip_cidr";"DST-PORT"->"port";"SRC-PORT"->"source_port"
            "NETWORK"->"network";"PROCESS-NAME"->"process_name";"PROCESS-PATH"->"process_path";"PROCESS-PATH-REGEX"->"process_path_regex"
            // Mihomo explicitly matches Android package names for this condition:
            // MetaCubeX/Meta-Docs docs/config/rules/index.en.md#process-name-regex.
            "PROCESS-NAME-REGEX"->"package_name_regex";"PACKAGE-NAME"->"package_name"
            "RULE-SET"->"rule_set"
            "GEOIP","GEOSITE"->{require(geo!=null);val selectors=geo.getJSONArray("selectors");val index=selectors.length()
                selectors.put(JSONObject().put("kind",type.lowercase()).put("code",value.lowercase()).put("target","proxy").put("managedRule",true))
                return JSONObject().put("rule_set",JSONArray().put("mcrf-geo-$index"))}
            else->error("Неподдерживаемое условие Clash: $type. Профиль не изменён.")
        }
        val result=JSONObject().put(key,JSONArray().put(when(type){"RULE-SET"->providers[value]?:error("Нет rule-provider $value.");"NETWORK"->value.lowercase();"DST-PORT","SRC-PORT"->value.toInt();else->value}))
        RemoteRoutingImports.validateMatch(result);return result
    }
}
