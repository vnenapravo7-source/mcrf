package org.mcrf.client

/** Probe exactly the effective profile, never the old link behind an edit.
 * The current Steer native port accepts URL profiles, not full JSON dispatchers. */
internal object SteerProbePlan {
    private val protocols=setOf("vless","vmess","trojan","ss","shadowsocks","socks","socks5","http","hysteria2")
    fun supports(entry:ServerImport.Server):Boolean=entry.protocol in protocols&&entry.carrier==null&&
        entry.configuration==null&&entry.endpoint==null&&entry.outbound!=null
    fun profile(entry:ServerImport.Server):String {
        require(supports(entry)) {"Этот протокол пока нельзя проверить собственным движком Steer."}
        return SteerEffectiveProfile.profile(entry)
    }
    fun request(entry:ServerImport.Server):org.json.JSONObject {
        val value=profile(entry)
        return org.json.JSONObject().put("id",entry.id).put("profile",value).put("host",entry.host)
            .put("profileSha256",ServerImport.read(value).single().id)
    }
    /** Catalogue identity stays stable across edits, native profile identity
     * does not. Bind both explicitly instead of requiring old URI == new URI. */
    fun captured(item:org.json.JSONObject):ServerImport.Server {
        require(item.keys().asSequence().toSet()==setOf("id","profile","host","profileSha256")&&
            item.getString("id").matches(Regex("[0-9a-f]{64}"))) {"STEER_PROBE_PROFILE_IDENTITY"}
        val parsed=ServerImport.read(item.getString("profile")).single()
        require(parsed.id==item.getString("profileSha256")&&parsed.host==item.getString("host")&&supports(parsed)) {"STEER_PROBE_PROFILE_IDENTITY"}
        return parsed
    }
    fun fingerprint(entry:ServerImport.Server):String=SteerProbeSpec.requestSha(entry.original,
        entry.outbound?.toString().orEmpty(),entry.host+":"+entry.port)
}
