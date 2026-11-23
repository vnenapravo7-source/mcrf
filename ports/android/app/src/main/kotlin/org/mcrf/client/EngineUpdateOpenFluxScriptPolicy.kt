package org.mcrf.client

import org.json.JSONObject

/** Production protocol is separate from the historical candidate/TOFU updater. */
internal object EngineUpdateOpenFluxScriptPolicy {
    const val OWNER="flux_official"
    const val CAPABILITY=2
    const val CLI="official-pinned-v1"
    const val CAPABILITY_KEY="org.mcrf.engines.openflux.scriptUpdater"
    const val CLI_KEY="org.mcrf.engines.openflux.scriptUpdateCli"
    val ids=OpenFluxProfile.OFFICIAL_SCRIPTS
    private val digest=Regex("[0-9a-f]{64}")
    private val version=Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    fun request(action:String,id:String="",sha:String=""):JSONObject {
        require(action in setOf("status","check","apply","rollback"))
        if(action=="status"){require(id.isEmpty()&&sha.isEmpty());return JSONObject().put("action",action)}
        require(id in ids)
        require(if(action=="check")sha.isEmpty()else digest.matches(sha))
        return JSONObject().put("action",action).put("id",id).apply {if(sha.isNotEmpty())put("expectedSha256",sha)}
    }
    data class Receipt(val id:String,val version:String,val sha:String,val currentVersion:String,val currentSha:String,
                       val previousVersion:String="",val previousSha:String="") {
        fun json()=JSONObject().put("id",id).put("version",version).put("sha256",sha)
            .put("currentVersion",currentVersion).put("currentSha256",currentSha)
            .put("previousVersion",previousVersion).put("previousSha256",previousSha)
    }
    fun receipt(data:JSONObject,abi:String):Receipt {
        require(data.getString("coreCommit")==OpenFluxProfile.FRESH_COMMIT&&data.getString("abi")==abi)
        require(data.getInt("wire")==1&&data.getInt("api")==1&&data.getBoolean("runtimePreflight")&&!data.getBoolean("runtimeAccepted"))
        return selection(data)
    }
    fun selection(data:JSONObject):Receipt {
        val r=Receipt(data.getString("id"),data.getString("version"),data.getString("sha256"),data.getString("currentVersion"),data.getString("currentSha256"),data.optString("previousVersion"),data.optString("previousSha256"))
        require(r.id in ids&&version.matches(r.version)&&version.matches(r.currentVersion)&&digest.matches(r.sha)&&digest.matches(r.currentSha))
        require(if(r.previousSha.isEmpty())r.previousVersion.isEmpty()else digest.matches(r.previousSha)&&version.matches(r.previousVersion))
        return r
    }
    fun inventory(data:JSONObject,abi:String):List<Receipt> {
        require(data.getString("status")=="verified_inventory"&&data.getInt("interface")==1&&data.getInt("scriptUpdater")==CAPABILITY&&data.getString("cli")==CLI)
        require(data.getString("coreCommit")==OpenFluxProfile.FRESH_COMMIT&&data.getString("abi")==abi&&!data.getBoolean("runtimeAccepted"))
        val packages=data.getJSONArray("packages");require(packages.length()==ids.size)
        return (0 until packages.length()).map {i->
            val row=packages.getJSONObject(i);require(row.getString("status")=="verified_current")
            receipt(row,abi).also {require(it.sha==it.currentSha&&it.version==it.currentVersion)}
        }.also {rows->require(rows.map{it.id}.toSet()==ids.toSet())}
    }
    fun verifiedResult(action:String,id:String,expected:String,before:String,data:JSONObject,abi:String):Receipt {
        val result=receipt(data,abi);require(result.id==id&&result.currentSha==before)
        require(data.getString("status") in if(action=="check")setOf("verified_candidate")else setOf("up_to_date","installed_for_next_session"))
        if(action!="check")require(result.sha==expected)
        return result
    }
    /** Lost stdout/process death cannot turn another digest into this operation's receipt. */
    fun reconcile(current:Receipt,id:String,before:String,expected:String):Boolean {
        require(current.id==id&&current.sha==current.currentSha&&current.version==current.currentVersion&&digest.matches(before)&&digest.matches(expected))
        require(current.sha==before||current.sha==expected){"Выбран другой пакет; требуется проверка состояния."}
        return current.sha==expected&&expected!=before
    }
}
