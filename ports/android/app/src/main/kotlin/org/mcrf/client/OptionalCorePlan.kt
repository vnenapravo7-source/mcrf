package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Prepares every outbound without dropping selectors, tags, DNS or route priority. */
class OptionalCorePlan(source: JSONObject, settings: OptionalCoreSettings, core: CoreTuningSettings? = null) {
    data class Entry(val tag: String,val request: OptionalCoreConfig.Request)
    private val original=JSONObject(source.toString())
    private val planned: List<Entry>
    val notes: List<String>
    init {
        val outputs=original.optJSONArray("outbounds") ?: JSONArray()
        val tags=hashSetOf<String>();val entries=mutableListOf<Entry>();val limitations=mutableListOf<String>()
        repeat(outputs.length()) { index ->
            val output=outputs.getJSONObject(index);val tag=output.optString("tag")
            require(tag.isNotBlank() && tags.add(tag)) { "Для optional core нужны разные непустые теги выходов." }
            val dns:(JSONObject)->JSONObject?={OptionalServerDns.capture(original,it)}
            val prepared=core?.let {OptionalCoreTuning.prepare(output,settings,it,dns)}
            prepared?.notes?.let(limitations::addAll)
            val request=if(core==null)OptionalCoreConfig.prepare(output,settings,dns) else prepared?.request
            request?.let { entries.add(Entry(tag,it)) }
        }
        planned=entries
        notes=limitations.distinct()
    }
    /** Snapshot copies prevent native-check/start callbacks changing this plan. */
    val requiresWorkers: Boolean get()=planned.isNotEmpty()
    fun entries(): List<Entry> = planned.map { Entry(it.tag,OptionalCoreConfig.Request(it.request.kind,JSONObject(it.request.outbound.toString()),it.request.certificateStore,it.request.serverDns?.let {dns->JSONObject(dns.toString())})) }
    fun preflight(cancelled: BooleanSupplier,check: (Entry)->Unit) {
        entries().forEach { entry ->
            require(!cancelled.asBoolean) { "Проверка optional core отменена." };check(entry)
            require(!cancelled.asBoolean) { "Проверка optional core отменена." }
        }
    }
    fun replace(bridges: Map<String,JSONObject>): JSONObject {
        require(bridges.keys==planned.map {it.tag}.toSet()) { "Не все optional core выходы запущены." }
        val config=JSONObject(original.toString());val outputs=config.optJSONArray("outbounds") ?: JSONArray()
        repeat(outputs.length()) { index ->
            val tag=outputs.getJSONObject(index).getString("tag")
            bridges[tag]?.let { bridge ->
                require(bridge.optString("tag")==tag && bridge.optString("type")=="socks") { "Bridge должен сохранить тег исходного выхода." }
                outputs.put(index,JSONObject(bridge.toString()))
            }
        }
        return config
    }
}
