package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/** Backend choice for WG/AWG leaves the primary-core preference untouched. */
object WgAddonPolicy {
    const val KIND = "wg-awg"
    enum class Backend { SING_BOX, COMPACT_WG, NEED_PRIMARY, NEED_SING_BOX, NEED_COMPACT_WG }

    fun isWireGuard(protocol: String) = protocol in setOf("wg","wireguard","awg")

    internal fun standaloneProfile(entry:ServerImport.Server?,vpn:Boolean,automatic:Boolean):Boolean =
        vpn&&!automatic&&entry!=null&&isWireGuard(entry.protocol)&&entry.carrier==null&&entry.configuration==null

    internal fun standalonePreferred(compactPresent:Boolean,singBoxPresent:Boolean):Boolean =
        compactPresent||!singBoxPresent

    /** UI package hint. The actual start verifies the APK off the UI thread. */
    internal fun standaloneHint(context:Context):Boolean {
        fun present(kind:String)=try {
            context.packageManager.getApplicationInfo(EngineModules.packageName(kind),0);true
        }catch(_:PackageManager.NameNotFoundException){false}
        return standalonePreferred(present(KIND),present("sing-box"))
    }

    internal fun prefersStandalone(context:Context):Boolean {
        try {EngineUpdatePacket.installation(context);return true}
        catch(_:PackageManager.NameNotFoundException){}
        return try {EngineUpdatePrimary.installation(context,"sing-box");false}
        catch(_:PackageManager.NameNotFoundException){true}
    }

    /** Follow the active selector, not every unselected server stored in the same configuration. */
    fun usesWireGuard(source: JSONObject): Boolean {
        val nodes = linkedMapOf<String,JSONObject>()
        for(key in listOf("outbounds","endpoints")) source.optJSONArray(key)?.let {array->
            require(array.length()<=4096) {"WG_CONFIG_LIMIT"}
            repeat(array.length()) {index->val node=array.optJSONObject(index)?:return@repeat
                node.optString("tag").takeIf(String::isNotEmpty)?.let {tag->require(tag !in nodes) {"WG_DUPLICATE_TAG"};nodes[tag]=node;require(nodes.size<=4096)}}
        }
        val root=source.optJSONObject("route")?.optString("final").orEmpty().ifEmpty {
            source.optJSONArray("outbounds")?.optJSONObject(0)?.optString("tag").orEmpty()
        }
        val queued=hashSetOf<String>();val pending=java.util.ArrayDeque<String>()
        fun enqueue(tag:String) {if(tag in nodes && queued.add(tag))pending.add(tag)}
        enqueue(root)
        while(pending.isNotEmpty()) {
            val node=nodes.getValue(pending.removeFirst())
            if(node.optString("type")=="wireguard")return true
            enqueue(node.optString("detour"))
            val members=node.optJSONArray("outbounds")?:continue
            require(members.length()<=4096) {"WG_CONFIG_LIMIT"}
            if(node.optString("type")=="selector")enqueue(node.optString("default").ifEmpty {members.optString(0)})
            else repeat(members.length()) {enqueue(members.optString(it))}
        }
        return false
    }

    /** Installed means identity/ABI verified, not merely a similarly named package. */
    fun backend(primary: String?, singBoxInstalled: Boolean, compactInstalled: Boolean): Backend {
        require(primary == null || primary in setOf("singbox","steer")) {"WG_PRIMARY_CHOICE"}
        if(singBoxInstalled) return Backend.SING_BOX
        return when(primary) {
            "singbox" -> Backend.NEED_SING_BOX
            "steer" -> if(compactInstalled) Backend.COMPACT_WG else Backend.NEED_COMPACT_WG
            else -> Backend.NEED_PRIMARY
        }
    }

    fun offerDownload(primary: String?, singBoxInstalled: Boolean, compactInstalled: Boolean, protocols: Collection<String>) =
        protocols.any(::isWireGuard) && backend(primary,singBoxInstalled,compactInstalled) == Backend.NEED_COMPACT_WG

}
