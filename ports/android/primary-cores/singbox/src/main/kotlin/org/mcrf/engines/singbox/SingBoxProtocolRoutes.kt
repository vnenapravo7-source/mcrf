package org.mcrf.engines.singbox

import android.content.Context
import android.content.pm.PackageManager
import io.nekohasekai.libbox.Libbox

/** Exact optional native schema, never inferred from module/app version. */
internal object SingBoxProtocolRoutes {
    const val METADATA="org.mcrf.engine.protocol-route-dispatcher"
    const val CAPABILITY="owned_numeric_protocol_socks_v1"
    fun advertised(context:Context):Boolean {
        val info=try{context.packageManager.getApplicationInfo("org.mcrf.engines.singbox",PackageManager.GET_META_DATA)}
            catch(_:PackageManager.NameNotFoundException){return false}
        val value=info.metaData?.get(METADATA)?:return false
        require(value is String&&value==CAPABILITY){"PROTOCOL_ROUTE_UNKNOWN_CAPABILITY"}
        val schema=try{Libbox::class.java.getMethod("mcrfNumericSocksSchema").invoke(null)}
            catch(_:Exception){throw IllegalStateException("PROTOCOL_ROUTE_NATIVE_INTERFACE")}
        require(schema is Int&&schema==1){"PROTOCOL_ROUTE_NATIVE_SCHEMA"}
        return true
    }
}
