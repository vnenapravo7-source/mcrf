package org.mcrf.engines.singbox

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import java.lang.reflect.Method
import java.security.MessageDigest

/** Pending, module-owned original-IP endpoints. This role is distinct from
 * raw acceleration: ordinary native routing/sniffing and DNS remain active.
 * A successful attach means ownership and native proof, never VPN readiness. */
internal class SingBoxOriginalCarrierPackets(private val context: Context) {
    private data class Pending(val tag:String,val nonce:String,val local:String,val mtu:Int,
        val peerPid:Int,val generation:Long,val fd:ParcelFileDescriptor)
    private val lock=Any()
    private val endpoints=LinkedHashMap<String,Pending>()
    private var settingsSha:String?=null
    private var generation=0L
    private var sealed=false
    private var closed=false
    val selected:Boolean get()=synchronized(lock){generation>0}
    val advertised:Boolean by lazy {
        val metadata=try {context.packageManager.getApplicationInfo("org.mcrf.engines.singbox",PackageManager.GET_META_DATA).metaData}
            catch(_:PackageManager.NameNotFoundException){null}
        val marker=metadata?.get(METADATA)
        if(marker==null)false else {
            require(marker is String && marker==CAPABILITY){"ORIGINAL_CARRIER_CAPABILITY"}
            require(call(static("mcrfOriginalCarrierPacketSchema"),null)==1){"ORIGINAL_CARRIER_SCHEMA"}
            static("checkMcrfOriginalCarrierPacketEndpoint",*PROOF_TYPES)
            CommandServer::class.java.getMethod("registerMcrfOriginalCarrierPacketEndpoint",String::class.java,*PROOF_TYPES)
            true
        }
    }
    private fun static(name:String,vararg types:Class<*>):Method=Libbox::class.java.getMethod(name,*types)
    private fun call(method:Method,receiver:Any?,vararg args:Any?):Any?=try {method.invoke(receiver,*args)}
        catch(_:ReflectiveOperationException){throw IllegalStateException("ORIGINAL_CARRIER_NATIVE_REJECTED")}
    private fun sha(settings:String)=MessageDigest.getInstance("SHA-256").digest(settings.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt() and 255)}

    fun attach(settings:String,source:String,borrowedFd:Int,peerPid:Int,gen:Long,stopping:()->Boolean) {
        require(advertised && gen>0 && borrowedFd>=0 && peerPid>0 && source.toByteArray(Charsets.UTF_8).size<=512){"ORIGINAL_CARRIER_INPUT"}
        val spec=JSONObject(source)
        require(spec.keys().asSequence().toSet()==setOf("schema","tag","nonce","local","mtu") && spec.opt("schema")==1){"ORIGINAL_CARRIER_SCHEMA"}
        val tag=spec.opt("tag");val nonce=spec.opt("nonce");val local=spec.opt("local");val mtu=spec.opt("mtu")
        require(tag is String && tag.matches(Regex("[A-Za-z0-9._-]{1,96}")) && nonce is String && nonce.matches(Regex("[0-9a-f]{32}")) &&
            local is String && local.length in 7..15 && mtu is Int && mtu in 576..1984){"ORIGINAL_CARRIER_INPUT"}
        val config=JSONObject(SingBoxRuntimeConfig.parse(settings).nativeJson())
        val outbounds=config.optJSONArray("outbounds")
        val matches=(0 until (outbounds?.length() ?: 0)).map {outbounds!!.getJSONObject(it)}.filter {it.optString("tag")==tag}
        require(matches.size==1 && matches.single().optString("type")=="socks" &&
            !matches.single().has("detour") && !matches.single().has("udp_over_tcp")){"ORIGINAL_CARRIER_OUTBOUND"}
        val fingerprint=sha(settings)
        val owned=ParcelFileDescriptor.fromFd(borrowedFd)
        var retained=false
        try {
            // Native duplicates solely to prove socket type/UID/creator identity;
            // this Java duplicate remains the ACK's durable ownership token.
            call(static("checkMcrfOriginalCarrierPacketEndpoint",*PROOF_TYPES),null,
                owned.fd,nonce,gen,peerPid,local,mtu)
            synchronized(lock) {
                check(!closed && !sealed && !stopping() && endpoints.size<64 && !endpoints.containsKey(tag) &&
                    endpoints.values.none {it.nonce==nonce} && (generation==0L || generation==gen) &&
                    (settingsSha==null || settingsSha==fingerprint)){"ORIGINAL_CARRIER_LIFETIME"}
                generation=gen;settingsSha=fingerprint
                endpoints[tag]=Pending(tag,nonce,local,mtu,peerPid,gen,owned);retained=true
            }
        } finally {if(!retained)owned.close()}
    }
    fun seal(settings:String,gen:Long) {
        synchronized(lock) {
            check(!closed && !sealed && (generation==0L || generation==gen) &&
                (settingsSha==null || settingsSha==sha(settings))){"ORIGINAL_CARRIER_CONFIG_CHANGED"}
            sealed=true
        }
    }
    fun register(server:CommandServer) {
        synchronized(lock) {
            check(sealed && !closed){"ORIGINAL_CARRIER_LIFETIME"}
            try {
                if(endpoints.isNotEmpty()) {
                    val register=CommandServer::class.java.getMethod("registerMcrfOriginalCarrierPacketEndpoint",String::class.java,*PROOF_TYPES)
                    endpoints.values.forEach {p->call(register,server,p.tag,p.fd.fd,p.nonce,p.generation,p.peerPid,p.local,p.mtu)}
                }
            } finally {closePending()}
        }
    }
    private fun closePending() {
        val pending=endpoints.values.toList();endpoints.clear()
        var failure:Exception?=null
        pending.forEach {try {it.fd.close()}catch(e:Exception){if(failure==null)failure=e else failure!!.addSuppressed(e)}}
        failure?.let {throw it}
    }
    fun close() {synchronized(lock){closed=true;closePending()}}
    override fun toString()="SingBoxOriginalCarrierPackets(redacted)"
    companion object {
        const val METADATA="org.mcrf.engine.original-carrier-bridge"
        const val CAPABILITY="owned_original_ip_stack_v1"
        private val PROOF_TYPES=arrayOf(Int::class.javaPrimitiveType!!,String::class.java,Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,String::class.java,Int::class.javaPrimitiveType!!)
    }
}
