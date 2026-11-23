package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Manual catalogue entry only. The router owns ingress, the selected module owns transport. */
internal object StandaloneRouterSelection {
    private val optionalTypes=setOf("xray","vless","vmess","trojan","http","socks")

    fun candidate(entry:ServerImport.Server?):Boolean = entry!=null&&entry.carrier==null&&
        entry.configuration==null&&entry.endpoint==null&&
        (CompactProtocolPolicy.eligible(entry)||entry.outbound?.optString("type") in optionalTypes)

    fun hint(context:Context,entry:ServerImport.Server?,vpn:Boolean):Boolean =
        entry!=null&&ClientPrimarySelection.selected(context)==null&&BsNativeContract.standaloneHint(vpn)&&
            candidate(entry)&&(CompactProtocolPolicy.eligible(entry)||StandaloneOptionalRouter.hint(context,entry))

    fun preflight(context:Context,entry:ServerImport.Server,source:JSONObject,state:JSONObject,
                  cancelled:java.util.function.BooleanSupplier):OptionalCoreConfig.Request? {
        val request=request(context,entry,source,state)?:return null
        val usage=EngineUpdateGate.acquireUsage(context)?:throw IllegalArgumentException(
            "Дождитесь завершения обновления движка.")
        usage.use {
            BsNativeContract.requireBundledRouter(context)
            if(EnginePackages.missing(context,setOf(request.kind)).isNotEmpty())throw ClientOptionalCoreRequired(request.kind)
            if(request.kind!=CompactProtocolPolicy.KIND)StandaloneOptionalRouter.requirePhysical(context,request)
            val physical=DirectAccessCheck.physical(context)?:throw IllegalArgumentException("Нет физической сети для подключения.")
            val owns=java.util.function.BooleanSupplier {cancelled.asBoolean||DirectAccessCheck.physical(context)!=physical}
            OptionalCoreProcess.check(context,request.copy(physicalNetworkHandle=physical.networkHandle),owns)
        }
        return request
    }

    internal fun owns(source:JSONObject,entry:ServerImport.Server):Boolean {
        if(!candidate(entry)||source.has("mcrf_carrier")||source.has("mcrf_bs")||
            (source.optJSONArray("endpoints")?.length()?:0)>0)return false
        val tag=ServerImport.tag(entry)
        if(source.optJSONObject("route")?.optString("final")!=tag)return false
        val expected=JSONObject(requireNotNull(entry.outbound).toString()).put("tag",tag)
        if(!expected.has("domain_resolver"))expected.put("domain_resolver",
            JSONObject().put("server","bootstrap").put("strategy","prefer_ipv4"))
        val outputs=source.optJSONArray("outbounds")?:return false
        val nodes=(0 until outputs.length()).map(outputs::optJSONObject).filter {it?.optString("tag")==tag}
        return nodes.singleOrNull()?.toString()==expected.toString()
    }

    fun request(context:Context,entry:ServerImport.Server,source:JSONObject,state:JSONObject):OptionalCoreConfig.Request? {
        if(!candidate(entry))return null
        require(owns(source,entry)){"NATIVE_ROUTER_STANDALONE_OUTBOUND_IDENTITY"}
        return if(CompactProtocolPolicy.eligible(entry))CompactProtocolSession.standaloneRequest(context,entry,state)
        else StandaloneOptionalRouter.request(context,source,ServerImport.tag(entry))
    }
}
