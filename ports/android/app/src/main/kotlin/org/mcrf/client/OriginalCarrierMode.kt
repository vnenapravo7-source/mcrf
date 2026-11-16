package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Decide before launching a child. A failure after selection never retries
 * through another protocol, generation, provider attempt or SOCKS transport. */
internal object OriginalCarrierMode {
    const val PRIMARY_METADATA="org.mcrf.engine.original-carrier-bridge"
    const val BS_METADATA="org.mcrf.engines.original_packet"
    const val ENVIRONMENT="MCRF_ORIGINAL_PACKET_CONTROL"
    const val READY="MCRF_ORIGINAL_PACKET_READY"
    fun available(context:Context,profile:JSONObject):Boolean {
        if(profile.optBoolean("probe_only") || CsqttAutoDetection.negotiates(profile))return false
        val kind=EngineModules.carrierKind(profile)
        if(kind !in CsqttGenerations.engines)return false
        val primary=EngineUpdatePrimary.installation(context,"sing-box")
        val marker=primary.metaData?.get(PRIMARY_METADATA)?:return false
        require(marker==OriginalCarrierPacketSpec.CAPABILITY){"ORIGINAL_CARRIER_CAPABILITY"}
        val bridge=NativeCarrier.installation(context,kind)
        return bridge.packageName==BsEngineContract.PACKAGE && bridge.metaData?.getInt(BS_METADATA)==1
    }
}
