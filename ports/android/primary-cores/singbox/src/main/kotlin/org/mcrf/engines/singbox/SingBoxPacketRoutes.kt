package org.mcrf.engines.singbox

import android.content.Context
import android.content.pm.PackageManager
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/** Optional private role, not a public profile feature or protocol fallback.
 * Old/public modules without the exact marker never invoke the additive JNI
 * API. Reflection keeps the ordinary adapter compatible with their old AAR.
 * Every fd is borrowed for the whole synchronous attach, duplicated in native.
 */
internal class SingBoxPacketRoutes(private val context:Context) {
    private val lock=java.lang.Object()
    private var generation=0L
    private var settingsSha:String?=null
    private var pending=0
    private var sealed=false
    private var closed=false
    private var closeStarted=false
    private var closeFailed=false
    private val retired=CountDownLatch(1)
    val advertised:Boolean by lazy {
        val metadata=try {context.packageManager.getApplicationInfo("org.mcrf.engines.singbox",PackageManager.GET_META_DATA).metaData}
            catch(_:PackageManager.NameNotFoundException){null}
        val marker=metadata?.get(METADATA)
        if(marker==null)false else {
            require(marker is String&&marker==CAPABILITY){"PACKET_ROUTE_UNKNOWN_CAPABILITY"}
            val value=invoke(method("mcrfPacketPipeSchema"))
            require(value is Int&&value==1){"PACKET_ROUTE_NATIVE_SCHEMA"};true
        }
    }
    private fun method(name:String,vararg types:Class<*>):Method=try {Libbox::class.java.getMethod(name,*types)}
        catch(_:Exception){throw IllegalStateException("PACKET_ROUTE_NATIVE_INTERFACE")}
    private fun invoke(method:Method,vararg arguments:Any?):Any?=try {method.invoke(null,*arguments)}
        catch(_:Exception){throw IllegalStateException("PACKET_ROUTE_NATIVE_REJECTED")}
    private fun sha(settings:String)=MessageDigest.getInstance("SHA-256").digest(settings.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt() and 255)}

    fun attach(settings:String,source:String,borrowedFd:Int,ownerPid:Int,gen:Long,stopping:()->Boolean) {
        require(advertised&&gen>0&&borrowedFd>=0&&ownerPid>0&&source.toByteArray(Charsets.UTF_8).size<=1024){"PACKET_ROUTE_INPUT"}
        val spec=JSONObject(source)
        require(spec.keys().asSequence().toSet()==setOf("schema","tag","nonce","mtu","addresses")&&spec.opt("schema")==1){"PACKET_ROUTE_SCHEMA"}
        val tag=spec.opt("tag");val nonce=spec.opt("nonce");val mtu=spec.opt("mtu");val addresses=spec.opt("addresses")
        require(tag is String&&tag.matches(Regex("[A-Za-z0-9._-]{1,96}"))&&nonce is String&&nonce.matches(Regex("[0-9a-f]{32}"))&&
            mtu is Int&&mtu in 1280..9000&&addresses is String&&addresses.length in 3..512){"PACKET_ROUTE_INPUT"}
        val fingerprint=sha(settings)
        synchronized(lock) {
            check(!closed&&!sealed&&!stopping()&&(generation==0L||generation==gen)&&
                (settingsSha==null||settingsSha==fingerprint)){"PACKET_ROUTE_LIFETIME"}
            generation=gen;settingsSha=fingerprint;pending++
        }
        try {
            check(!stopping()){"PACKET_ROUTE_STOPPING"}
            invoke(method("mcrfAttachPacketPipe",Long::class.javaPrimitiveType!!,String::class.java,String::class.java,
                Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!,String::class.java),
                gen,tag,nonce,borrowedFd,ownerPid,mtu,addresses)
            synchronized(lock){check(!closed&&!stopping()){"PACKET_ROUTE_STOPPING"}}
        }finally{synchronized(lock){pending--;lock.notifyAll()}}
    }
    /** Validation can repeat; only actual run seals Java admission. Native
     * runtime ownership is claimed by outbound.Start, not by CheckConfig. */
    fun seal(settings:String,gen:Long) {
        synchronized(lock) {
            check(!closed&&!sealed&&pending==0&&(generation==0L||generation==gen)&&
                (settingsSha==null||settingsSha==sha(settings))){"PACKET_ROUTE_CONFIG_CHANGED"}
            sealed=true
        }
    }
    fun fail(gen:Long,tag:String,nonce:String) {
        synchronized(lock){check(!closed&&generation==gen&&gen>0){"PACKET_ROUTE_LIFETIME"}}
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,96}"))&&nonce.matches(Regex("[0-9a-f]{32}"))){"PACKET_ROUTE_INPUT"}
        invoke(method("mcrfFailPacketPipe",Long::class.javaPrimitiveType!!,String::class.java,String::class.java),gen,tag,nonce)
    }
    /** Nonblocking from STOP/Binder. The exact cleanup owner survives failures;
     * close() is a separate off-UI wait and never replaces actual worker death. */
    fun requestStop() {
        val gen=synchronized(lock) {
            closed=true
            if(closeStarted)return
            closeStarted=true
            if(generation==0L){retired.countDown();return}
            generation
        }
        Thread({
            var interrupted=false
            try {
                try {invoke(method("mcrfClosePacketPipes",Long::class.javaPrimitiveType!!),gen)}
                catch(_:Exception){synchronized(lock){closeFailed=true}}
                synchronized(lock){while(pending>0){try {lock.wait(1000)}catch(_:InterruptedException){interrupted=true}}}
            }finally{retired.countDown();if(interrupted)Thread.currentThread().interrupt()}
        },"mcrf-packet-route-close").apply {isDaemon=true;start()}
    }
    fun close() {
        requestStop()
        var interrupted=false
        try {while(true){try {retired.await();break}catch(_:InterruptedException){interrupted=true}}}
        finally{if(interrupted)Thread.currentThread().interrupt()}
        synchronized(lock){check(!closeFailed){"PACKET_ROUTE_CLOSE_PENDING_WORKER_DEATH_REQUIRED"}}
    }
    override fun toString()="SingBoxPacketRoutes(redacted)"
    companion object {
        const val METADATA="org.mcrf.engine.packet-route-bridge"
        const val CAPABILITY="owned_pipe_stack_v1"
    }
}
