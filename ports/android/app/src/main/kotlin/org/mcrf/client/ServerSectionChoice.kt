package org.mcrf.client

import org.json.JSONObject

/** Local pool overrides, independent of protocol/credentials and subscription identity.
 * Store inside connections.enc so edits, refreshes and backups retain the choice. */
internal object ServerSectionChoice {
    const val KEY="serverSections"
    // Protocol/profile discriminators, never display names. CSQTT generations use
    // type=csqtt in profiles and distinct kinds in engine metadata.
    private val fixedKinds=SwgProfile.TYPES+CsqttGenerations.engines+
        setOf(S3BypassProfile.KIND,"s3-bypass","fedarisha","openflux")
    fun canMove(entry:ServerImport.Server):Boolean {
        fun fixed(node:JSONObject?)=node!=null&&(node.optString("type") in fixedKinds||
            node.optString("protocol") in fixedKinds||S3BypassProfile.isProfile(node))
        return entry.protocol !in fixedKinds&&!fixed(entry.carrier)&&!fixed(entry.outbound)&&
            !fixed(entry.endpoint)&&!fixed(entry.configuration)
    }
    fun actionLabel(entry:ServerImport.Server):String? =
        if(!canMove(entry))null else if(entry.whitelists)"VPN"else"БС"

    fun assignments(document:JSONObject):Map<String,Boolean> {
        if(document.has(KEY))require(document.get(KEY) is JSONObject){"Некорректное назначение сервера."}
        val overrides=document.optJSONObject(KEY)?:return emptyMap()
        require(overrides.length()<=1000){"Слишком много назначений серверов."}
        return overrides.keys().asSequence().associate {id->
            val value=overrides.get(id)
            require(id.matches(Regex("[a-f0-9]{64}"))&&value is String&&value in setOf("bs","vpn")){"Некорректное назначение сервера."}
            id to (overrides.getString(id)=="bs")
        }
    }
    fun whitelistIds(document:JSONObject):Set<String> = assignments(document).filterValues {it}.keys
    fun belongs(entry:ServerImport.Server,whitelists:Boolean,overrides:Map<String,Boolean>):Boolean =
        apply(entry,overrides).whitelists==whitelists

    fun apply(entry:ServerImport.Server,overrides:Map<String,Boolean>):ServerImport.Server {
        val target=overrides[entry.id]?:return entry
        // Accept historical no-op BS marks without allowing an injected VPN mark
        // to reclassify a fixed engine during load/restore/import.
        require(canMove(entry)||target==entry.whitelists){"Тип сервера этого движка менять нельзя."}
        return if(entry.whitelists==target)entry else ServerImport.Server(
            entry.original,entry.name,entry.protocol,entry.host,entry.port,entry.outbound,
            entry.endpoint,entry.configuration,entry.carrier,entry.profileDns,target)
    }

    /** Pure transaction preparation; caller writes the whole encrypted catalogue atomically. */
    fun move(document:JSONObject,id:String,whitelists:Boolean=true):JSONObject {
        val entries=ConnectionCatalogue(document.toString()).entries()
        val entry=requireNotNull(entries.firstOrNull {it.id==id}){"Подключение уже удалено. Обнови список."}
        require(canMove(entry)){"Тип сервера этого движка менять нельзя."}
        val existing=entries.mapTo(hashSetOf()){it.id}
        val choices=(assignments(document)+(id to whitelists)).filterKeys {it in existing}
        return JSONObject(document.toString()).put(KEY,JSONObject().apply {
            choices.toSortedMap().forEach {(key,bs)->put(key,if(bs)"bs"else"vpn")}
        }).also {
            ConnectionCatalogue(it.toString()).serialize()
        }
    }
}
