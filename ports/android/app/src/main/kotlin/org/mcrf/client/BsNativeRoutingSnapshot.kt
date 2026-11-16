package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Preparation only. Starting native BS still requires the DNS/metadata runtime
 * capability. Never makes an incompatible document appear IP-only. */
internal object BsNativeRoutingSnapshot {
    class Egress(val serverId:String,val port:Int,private val username:String,private val password:String) {
        val tag=RouteServerPlan.tag(serverId)
        init {
            require(port in 1024..65535)
            require(listOf(username,password).all {value->value.length in 16..255&&value.all {it in '!'..'~'}})
        }
        internal fun binding()=JSONObject().put("tag",tag).put("port",port).put("username",username).put("password",password)
        override fun toString()="BsNativeEgress(redacted)"
    }
    /** files are bytes from the exact local paths of the fully prepared
     * document. No network/global-directory lookup or previous-profile fallback. */
    fun captureFiles(config:JSONObject,tunnelTag:String,files:Map<String,ByteArray>,certificates:JSONObject?=null,
                     egresses:List<Egress> = emptyList(),mainEgressId:String?=null):Captured {
        val declarations=config.getJSONObject("route").optJSONArray("rule_set")?:JSONArray()
        require(declarations.length()<=256)
        val sets=linkedMapOf<String,JSONObject>()
        var decodedBytes=0L
        repeat(declarations.length()){index->
            val entry=declarations.getJSONObject(index);val tag=entry.getString("tag")
            require(entry.optString("type")=="local"&&tag !in sets) {"БС: списки должны быть подготовлены локально и иметь разные теги."}
            require(entry.keys().asSequence().all {it in setOf("type","tag","format","path")}) {"БС: особые параметры списка нельзя пропустить."}
            val bytes=requireNotNull(files[tag]) {"БС: нет файла геосписка $tag."}.copyOf()
            require(bytes.size in 1..24*1024*1024)
            val decoded=when(entry.optString("format")) {
                "binary"->BsRuleSetBinary.decode(bytes)
                "source"->{
                    val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    CompatibilityImportFormats.validateJsonDepth(text);JSONObject(text)
                }
                else->throw IllegalArgumentException("БС: формат геосписка должен быть задан явно.")
            }
            decodedBytes+=decoded.toString().toByteArray(Charsets.UTF_8).size
            require(decodedBytes<=16*1024*1024) {"БС: общий снимок геосписков слишком большой."}
            sets[tag]=decoded
        }
        require(files.keys==sets.keys) {"БС: файлы другого профиля не подставляются."}
        return capture(config,tunnelTag,sets,certificates,egresses,mainEgressId)
    }
    class Captured internal constructor(
        private val policyText:String, private val dnsText:String?,
        private val stepsText:String, private val excludedText:String, private val routeOptionsText:String,
        private val dialersText:String, private val tunText:String,private val listenersText:String,private val certificatesText:String?,
        private val bindingsText:String?
    ) {
        fun policy()=JSONObject(policyText)
        fun dns()=dnsText?.let(::JSONObject)
        fun controls()=JSONArray(stepsText)
        fun excludedPackages()=JSONArray(excludedText)
        fun routeOptions()=JSONObject(routeOptionsText)
        fun dnsDialers()=JSONObject(dialersText)
        fun tunInbounds()=JSONArray(tunText)
        fun listeners()=JSONArray(listenersText)
        fun certificates()=certificatesText?.let(::JSONObject)
        fun bundle()=JSONObject().put("schema",1).put("policy",policy())
            .put("dns",dns()?:JSONObject.NULL).put("controls",controls())
            .put("routeOptions",routeOptions()).put("tunInbounds",tunInbounds())
            .put("dnsDialers",dnsDialers()).apply {
                // Older native runtimes reject this explicit requirement. It
                // must never disappear while the TUN portion starts anyway.
                val listeners=listeners();if(listeners.length()>0)put("listeners",listeners)
                certificates()?.let {put("certificates",it)}
                bindingsText?.let {put("egressBindings",JSONArray(it))}
            }
        override fun toString()="BsNativeRoutingSnapshot(redacted)"
    }

    /** Private same-UID worker transport of an already captured document.
     * Full native admission remains mandatory before this document can run. */
    fun fromBundle(bundle:JSONObject):Captured {
        require(bundle.keys().asSequence().all {it in setOf("schema","policy","dns","controls","routeOptions","tunInbounds","dnsDialers","listeners","certificates","egressBindings")}&&bundle.opt("schema")==1)
        require(bundle.has("dns")&&(bundle.isNull("dns")||bundle.opt("dns") is JSONObject))
        require(!bundle.has("listeners")||bundle.opt("listeners") is JSONArray)
        require(!bundle.has("certificates")||bundle.opt("certificates") is JSONObject)
        require(!bundle.has("egressBindings")||bundle.opt("egressBindings") is JSONArray)
        val text=bundle.toString();require(text.toByteArray(Charsets.UTF_8).size<=24*1024*1024)
        CompatibilityImportFormats.validateJsonDepth(text)
        val tun=bundle.getJSONArray("tunInbounds")
        val policy=bundle.getJSONObject("policy")
        val listeners=bundle.optJSONArray("listeners")?:JSONArray()
        require(tun.length()==1||(tun.length()==0&&policy.optInt("schema")==2&&listeners.length()>0))
        val exclusions=JSONArray();tun.optJSONObject(0)?.optJSONArray("exclude_package")?.let {items->repeat(items.length()){exclusions.put(items.getString(it))}}
        return Captured(policy.toString(),bundle.optJSONObject("dns")?.toString(),bundle.getJSONArray("controls").toString(),exclusions.toString(),bundle.getJSONObject("routeOptions").toString(),bundle.getJSONObject("dnsDialers").toString(),tun.toString(),listeners.toString(),bundle.optJSONObject("certificates")?.toString(),bundle.optJSONArray("egressBindings")?.toString())
    }

    /** config is the SAME fully compiled route/DNS document as the current
     * backend. decodedSets are explicit per-document private snapshots, never
     * a lookup in the global geo directory or a URL fetch. */
    fun capture(config:JSONObject, tunnelTag:String, decodedSets:Map<String,JSONObject>,certificates:JSONObject?=null,
                egresses:List<Egress> = emptyList(),mainEgressId:String?=null):Captured {
        require(!config.has("ntp")||config.optJSONObject("ntp")?.length()==0) {"БС: NTP требует нативной поддержки."}
        require(!config.has("services")||config.optJSONArray("services")?.length()==0) {"БС: дополнительные службы требуют нативной поддержки."}
        require(!config.has("experimental")||config.optJSONObject("experimental")?.length()==0) {"БС: API и экспериментальные службы требуют нативной поддержки."}
        require(!config.has("endpoints")||config.optJSONArray("endpoints")?.length()==0) {"БС: дополнительные протокольные endpoints требуют своего нативного пула."}
        require(!config.has("certificate")||certificates!=null) {"БС: нужен снимок выбранного хранилища CA."}
        val certificateText=certificates?.let {value->
            require(value.keys().asSequence().toSet()==setOf("store","roots")&&value.getString("store") in setOf("system","mozilla"))
            require(value.getString("store")==config.optJSONObject("certificate")?.optString("store","system").orEmpty().ifEmpty {"system"})
            require(value.getJSONArray("roots").length() in 1..1024);value.toString().also {require(it.toByteArray(Charsets.UTF_8).size<=3*1024*1024)}
        }
        require(tunnelTag.isNotBlank())
        val route=config.getJSONObject("route")
        require(route.keys().asSequence().all {it in setOf("rules","final","rule_set","auto_detect_interface","find_process","default_domain_resolver")}) {
            "БС: неподдерживаемые параметры маршрутизации нельзя пропустить."
        }
        val originalRules=route.optJSONArray("rules")?:JSONArray()
        fun refers(value:Any?,tag:String):Boolean=when(value){
            is JSONObject->value.keys().asSequence().any {key->refers(value.opt(key),tag)}
            is JSONArray->(0 until value.length()).any {refers(value.opt(it),tag)}
            is String->value==tag
            else->false
        }
        fun generatedLoopback(outbound:JSONObject):Boolean {
            val tag=outbound.getString("tag")
            if(!tag.matches(Regex("mcrf-loopback(?:-[1-9][0-9]*)?"))||
                outbound.keys().asSequence().toSet()!=setOf("type","tag","inet4_bind_address","inet6_bind_address")||
                outbound.optString("inet4_bind_address")!="127.0.0.1"||outbound.optString("inet6_bind_address")!="::1")return false
            val references=(0 until originalRules.length()).filter {refers(originalRules.opt(it),tag)}
            if(references.size!=1)return false
            val first=originalRules.optJSONObject(references.single())?:return false
            if(first.keys().asSequence().toSet()!=setOf("ip_cidr","action","outbound")||first.optString("action")!="route"||first.optString("outbound")!=tag||
                first.optJSONArray("ip_cidr")?.toString()!=JSONArray().put("127.0.0.0/8").put("::1/128").toString())return false
            if(route.optString("final")==tag||refers(config.opt("dns"),tag)||refers(config.opt("endpoints"),tag))return false
            val others=config.getJSONArray("outbounds")
            repeat(others.length()){i->val other=JSONObject(others.getJSONObject(i).toString());if(other.optString("tag")!=tag){other.remove("tag");if(refers(other,tag))return false}}
            return true
        }
        val named=egresses.associateBy(Egress::tag)
        val mainEgress=mainEgressId?.let {requireNotNull(named[RouteServerPlan.tag(it)]) {"БС: отсутствует основной транспорт маршрутов."}}
        require(egresses.size<=32&&named.size==egresses.size&&egresses.map(Egress::port).distinct().size==egresses.size)
        require(tunnelTag !in named||tunnelTag==mainEgress?.tag)
        val targets=hashMapOf(tunnelTag to (mainEgress?.tag?:"proxy"))
        val outboundTags=hashSetOf<String>()
        val outbounds=config.getJSONArray("outbounds")
        repeat(outbounds.length()) {i->val outbound=outbounds.getJSONObject(i)
            val tag=outbound.getString("tag")
            require(tag.isNotBlank()&&outboundTags.add(tag)) {"БС: повторяющаяся цель маршрутизации."}
            require(tag!=tunnelTag||outbound.optString("type")!="direct")
            if(tag in named) {
                require(outbound.optString("type") !in setOf("direct","block","selector","urltest")) {"БС: выход правила не соответствует транспорту."}
                targets[tag]=tag
            }else if(tag!=tunnelTag)when(outbound.optString("type")) {
                "direct","block"->{
                    require(outbound.keys().asSequence().all {it in setOf("type","tag")}||outbound.optString("type")=="direct"&&generatedLoopback(outbound)) {"БС: особые параметры прямого выхода требуют нативной поддержки."}
                    targets[tag]=if(outbound.getString("type")=="direct")"direct" else "block"
                }
            }
        }
        require(tunnelTag in outboundTags) {"БС: выбранная цель отсутствует в документе."}
        require(named.keys.all {it==mainEgress?.tag||it in outboundTags}) {"БС: для движка правила отсутствует выход."}
        fun target(tag:String)=requireNotNull(targets[tag]) {"БС: маршрут с другим сервером требует собственного нативного пула."}
        val rules=JSONArray();val controls=JSONArray()
        val original=route.optJSONArray("rules")?:JSONArray()
        require(original.length()<=4096)
        repeat(original.length()) {i->
            val value=JSONObject(original.getJSONObject(i).toString())
            when(val action=value.optString("action","route")) {
                "sniff","hijack-dns","resolve","route-options"->{
                    // Order and conditional controls matter: retain the entire
                    // operation for admission by the future native pipeline.
                    controls.put(JSONObject().put("beforeRule",rules.length()).put("operation",value))
                }
                "route","reject"->{
                    require(action!="reject"||!value.has("outbound")) {"БС: блокирующий маршрут не должен содержать другой выход."}
                    val selected=if(action=="reject")"block" else target(value.getString("outbound"))
                    value.remove("action");value.remove("outbound")
                    // Reject methods have observable behavior; do not collapse
                    // reset/drop/custom options into an ordinary block verdict.
                    require(!value.has("method")&&!value.has("reject_method")&&!value.has("no_drop")) {"БС: особый способ блокировки требует нативной поддержки."}
                    rules.put(JSONObject().put("match",value).put("target",selected))
                }
                else->throw IllegalArgumentException("БС: действие маршрута $action пока не поддерживается.")
            }
        }
        val sets=JSONObject();val declared=route.optJSONArray("rule_set")?:JSONArray();val tags=hashSetOf<String>()
        require(declared.length()<=256)
        repeat(declared.length()) {i->val declaration=declared.getJSONObject(i);val tag=declaration.getString("tag")
            require(tag.isNotBlank()&&tags.add(tag)) {"БС: повторяющийся список маршрутизации."}
            val captured=requireNotNull(decodedSets[tag]) {"БС: нет снимка геосписка $tag; стандартный список не подставляется."}
            val version=captured.get("version")
            require(version is Number&&version.toString() in setOf("1","2","3","4","5")&&captured.optJSONArray("rules")!=null)
            require(captured.keys().asSequence().all {it in setOf("version","rules")})
            sets.put(tag,JSONObject(captured.toString()))
        }
        require(decodedSets.keys==tags) {"БС: геосписки должны принадлежать только этому документу."}
        val policy=JSONObject().put("schema",if(named.isEmpty())1 else 2).put("defaultTarget",target(route.getString("final"))).put("rules",rules)
        if(named.isNotEmpty())policy.put("egressTags",JSONArray(named.keys.toList()))
        if(sets.length()>0)policy.put("ruleSets",sets)
        val dns=config.optJSONObject("dns")?.toString()
        require(!config.has("dns")||dns!=null) {"БС: некорректный DNS-документ."}
        val exclusions=linkedSetOf<String>();val tunInbounds=JSONArray();val listeners=JSONArray()
        config.optJSONArray("inbounds")?.let {inbounds->repeat(inbounds.length()){i->
            val inbound=inbounds.getJSONObject(i)
            if(inbound.optString("type")=="tun"){
                // Preserve include_package, addresses, route exclusions and
                // every other TUN option for the runtime's separate admission.
                // Capturing only exclusions would silently broaden a profile.
                tunInbounds.put(JSONObject(inbound.toString()))
                inbound.optJSONArray("exclude_package")?.let {packages->repeat(packages.length()) {exclusions+=packages.getString(it)}}
            }else listeners.put(JSONObject(inbound.toString()))
        }}
        val text=policy.toString();val steps=controls.toString();val excluded=JSONArray(exclusions.toList()).toString()
        require(text.toByteArray(Charsets.UTF_8).size<=16*1024*1024&&
            (dns?.toByteArray(Charsets.UTF_8)?.size?:0)<=1024*1024&&steps.toByteArray(Charsets.UTF_8).size<=1024*1024)
        val options=JSONObject(route.toString()).apply {remove("rules");remove("final");remove("rule_set")}
        val tunText=tunInbounds.toString();require(tunText.toByteArray(Charsets.UTF_8).size<=1024*1024)
        val listenersText=listeners.toString();require(listenersText.toByteArray(Charsets.UTF_8).size<=1024*1024)
        val bindings=egresses.takeIf {it.isNotEmpty()}?.let {JSONArray(it.map(Egress::binding)).toString()}
        return Captured(text,dns,steps,excluded,options.toString(),JSONObject(targets.toMap()).toString(),tunText,listenersText,certificateText,bindings)
    }
}
