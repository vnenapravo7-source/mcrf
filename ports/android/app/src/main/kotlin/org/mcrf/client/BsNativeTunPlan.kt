package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Checks a native projection against the same captured document before Android
 * opens a VPN. Preparation only: this class never starts an engine or changes settings. */
internal object BsNativeTunPlan {
    class Plan internal constructor(val mtu:Int,addresses:List<String>,routes:List<String>,dns:List<String>,included:List<String>,excluded:List<String>) {
        val addresses=freeze(addresses);val routes=freeze(routes);val dns=freeze(dns)
        val includedApps=freeze(included);val excludedApps=freeze(excluded)
        override fun toString()="BsNativeTunPlan(redacted)"
    }
    private fun freeze(values:List<String>):List<String> = Collections.unmodifiableList(ArrayList(values))
    private val nativeFields=setOf("schema","mtu","addresses","routes","excludedRoutes","includedApps","excludedApps","hostMustBeInsideVpn","requiresVpnNetworkHandle")
    private val sourceFields=setOf("type","tag","address","mtu","stack","auto_route","strict_route","dns_mode","route_address","route_exclude_address","include_package","exclude_package","interface_name","platform","gso","endpoint_independent_nat")
    private fun strings(row:JSONObject,key:String,max:Int,optional:Boolean=false):List<String> {
        if(optional&&!row.has(key))return emptyList()
        val array=row.getJSONArray(key);require(array.length()<=max){"БС: слишком большой план TUN."}
        return (0 until array.length()).map {index->val value=array.get(index);require(value is String&&value.isNotEmpty()&&value.length<=255){"БС: некорректная строка TUN."};value}.also {require(it.distinct().size==it.size){"БС: повторяющиеся параметры TUN."}}
    }
    private fun bool(row:JSONObject,key:String,default:Boolean):Boolean {
        if(!row.has(key))return default
        val value=row.get(key);require(value is Boolean){"БС: некорректный переключатель TUN."};return value
    }
    private fun number(row:JSONObject,key:String,default:Int?=null):Int {
        if(!row.has(key))return requireNotNull(default)
        val value=row.get(key);require(value is Int||value is Long){"БС: некорректное число TUN."}
        val long=(value as Number).toLong();require(long in 0..Int.MAX_VALUE.toLong());return long.toInt()
    }
    private fun cidrs(values:List<String>,network:Boolean)=values.map {ClientTunCidr.parse(it,network).toString()}
    fun parse(native:JSONObject,capturedInbounds:JSONArray,hostPackage:String):Plan {
        require(capturedInbounds.length()==1){"БС: нужен один захваченный TUN."}
        RouteRules.validatePackage(hostPackage)
        val source=capturedInbounds.getJSONObject(0)
        require(source.keys().asSequence().all {it in sourceFields}&&source.opt("type")=="tun"){"БС: неподдерживаемые параметры TUN."}
        require(native.keys().asSequence().toSet()==nativeFields&&native.opt("schema")==1&&native.opt("hostMustBeInsideVpn")==true&&native.opt("requiresVpnNetworkHandle")==true){"БС: несовместимый план TUN."}
        require(bool(source,"auto_route",true)&&bool(source,"strict_route",true)&&!bool(source,"gso",false)&&!bool(source,"endpoint_independent_nat",false)){"БС: режим TUN пока не поддерживается."}
        require(!source.has("platform")||source.getJSONObject("platform").length()==0){"БС: системный HTTP-прокси пока не поддерживается."}
        require(!source.has("interface_name")||source.opt("interface_name")==""){"БС: имя интерфейса задаёт Android."}
        require(!source.has("stack")||source.opt("stack") in setOf("system","gvisor","mixed"))
        require(!source.has("dns_mode")||source.opt("dns_mode")=="hijack")
        val addresses=cidrs(strings(source,"address",16),false);require(addresses.isNotEmpty())
        val families=addresses.map {ClientTunCidr.parse(it,false).bits}.toSet()
        addresses.forEach {value->val host=value.substringBefore('/');val ip=java.net.InetAddress.getByName(host);require(!ip.isAnyLocalAddress&&!ip.isLoopbackAddress&&!ip.isMulticastAddress)}
        val mtu=number(source,"mtu",1500);require(mtu in (if(128 in families)1280 else 576)..9000&&number(native,"mtu")==mtu)
        val sourceRoutes=if(source.has("route_address"))strings(source,"route_address",8192).also {require(it.isNotEmpty())} else buildList {if(32 in families)add("0.0.0.0/0");if(128 in families)add("::/0")}
        val routes=cidrs(sourceRoutes,true);val excludedRoutes=cidrs(strings(source,"route_exclude_address",8192,true),true)
        (routes+excludedRoutes).forEach {require(ClientTunCidr.parse(it).bits in families){"БС: маршрут без адреса соответствующего семейства."}}
        val included=strings(source,"include_package",512,true);val excluded=strings(source,"exclude_package",512,true)
        require(included.isEmpty()||excluded.isEmpty()){ "БС: конфликт списков приложений." }
        (included+excluded).forEach(RouteRules::validatePackage)
        require(hostPackage !in excluded){"БС: управляющее приложение должно быть внутри VPN."}
        require(cidrs(strings(native,"addresses",16),false)==addresses&&cidrs(strings(native,"routes",8192),true)==routes&&cidrs(strings(native,"excludedRoutes",8192),true)==excludedRoutes&&strings(native,"includedApps",512)==included&&strings(native,"excludedApps",512)==excluded){"БС: движок изменил адреса, маршруты или приложения."}
        // Reserved DNS aliases are internal service routes, never real upstream
        // addresses. Keep them captured even when a broad user exclusion covers them.
        val dns=buildList {if(32 in families)add("198.19.255.53");if(128 in families)add("fdfe:dcba:9876:dd54::53")}
        val aliases=dns.map {ClientTunCidr.parse("$it/${if(':' in it)128 else 32}")}
        val exclusions=ClientTunCidr.excluding(excludedRoutes.map {ClientTunCidr.parse(it)},aliases)
        val projected=ClientTunCidr.excluding(routes.map {ClientTunCidr.parse(it)}+aliases,exclusions).map {it.toString()}
        require(projected.size<=8192)
        return Plan(mtu,addresses,projected,dns,if(included.isEmpty())emptyList()else(included+hostPackage).distinct(),excluded)
    }
}
