package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** A transport helper, not permission to replace the selected primary core.
 * The helper has one original outbound and authenticated loopback ingress;
 * Steer keeps TUN/DNS/routing ownership. Runtime ownership/probe wiring is
 * separate and must not infer readiness or consent from this pure plan. */
internal object CompactProtocolPolicy {
    const val KIND="protocols"
    const val REPOSITORY="Leadaxe/sing-box-lx"
    const val COMMIT="3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d"
    val types=setOf("tuic","anytls","naive","ssh")
    private val forbidden=setOf("detour","bind_interface","inet4_bind_address","inet6_bind_address","protect_path","routing_mark","netns",
        "certificate_path","client_certificate_path","client_key_path","private_key_path","known_hosts_path","agent_path","agent_socket","command","network_strategy")
    class UnsafeProfile(message:String):SecurityException(message)
    fun eligible(entry:ServerImport.Server):Boolean=entry.protocol in types&&entry.carrier==null&&entry.configuration==null&&entry.endpoint==null&&entry.outbound?.optString("type")==entry.protocol
    fun compatible(kind:String?,repository:String?,commit:String?,contract:Int):Boolean=
        kind==KIND&&repository==REPOSITORY&&commit==COMMIT&&contract==1
    fun request(entry:ServerImport.Server,serverDns:JSONObject?=null):OptionalCoreConfig.Request {
        require(eligible(entry)){"Для этого профиля нет точного компактного протокольного модуля."}
        val out=JSONObject(requireNotNull(entry.outbound).toString())
        require(out.toString().toByteArray(Charsets.UTF_8).size<=65536){"Профиль протокольного модуля больше 64 КБ."}
        val pending=java.util.ArrayDeque<Pair<Any,Int>>();pending.add(out to 0);var visited=0
        while(pending.isNotEmpty()){
            val (value,depth)=pending.removeLast()
            require(depth<=32&&++visited<=4096){"Слишком сложный профиль протокольного модуля."}
            when(value){
                is JSONObject->value.keys().forEach {key->
                    if(key.lowercase(java.util.Locale.ROOT) in forbidden)throw UnsafeProfile("Протокольный модуль не принимает пути к файлам, команды и обход сетевой политики: $key.")
                    value.opt(key)?.takeIf {it is JSONObject||it is JSONArray}?.let {pending.add(it to depth+1)}
                }
                is JSONArray->repeat(value.length()){value.opt(it)?.takeIf {it is JSONObject||it is JSONArray}?.let {pending.add(it to depth+1)}}
            }
        }
        if(entry.protocol=="ssh"){
            val keys=when(val raw=out.opt("host_key")){is String->listOf(raw);is JSONArray->(0 until raw.length()).map {raw.getString(it)};else->emptyList()}
            if(keys.isEmpty()||keys.any {it.isBlank()||it.any {ch->ch.isISOControl()}})
                throw UnsafeProfile("Для SSH в компактном модуле укажи открытый ключ сервера (host_key). Проверка ключа не отключается автоматически.")
        }
        require(!out.has("domain_resolver")||serverDns!=null){"DNS адреса сервера нужно передать модулю вместе с профилем."}
        require(RouteRules.literal(entry.host)!=null||serverDns!=null){"Для доменного адреса протокольного сервера нужен выбранный DNS; системная подмена не выполняется."}
        out.remove("tag");out.remove("domain_resolver")
        return OptionalCoreConfig.Request(KIND,out,serverDns=serverDns?.let {JSONObject(it.toString())})
    }
    /** Fresh endpoint supplied by a verified owned helper, never from an import.
     * Logical catalogue identity is retained; the URI passed to native Steer is
     * generated from this effective SOCKS node, not from entry.original. */
    fun effective(entry:ServerImport.Server,port:Int,user:String,password:String):ServerImport.Server {
        require(eligible(entry)&&port in 1024..65535)
        require(user.matches(Regex("[0-9a-f-]{32,64}"))&&password.matches(Regex("[0-9a-f-]{32,64}")))
        val out=JSONObject().put("type","socks").put("server","127.0.0.1").put("server_port",port).put("version","5")
            .put("username",user).put("password",password)
        return ServerImport.Server(original=entry.original,name=entry.name,protocol="socks",host="127.0.0.1",port=port,
            outbound=out,endpoint=null,configuration=null,carrier=null,profileDns=entry.profileDns.toList())
    }
}
