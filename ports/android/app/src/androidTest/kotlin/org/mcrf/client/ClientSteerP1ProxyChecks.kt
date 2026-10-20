package org.mcrf.client

import android.content.*
import android.net.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual DIFFERENT-UID frontend requests through the real owned Steer VPN.
 * Package-constrained targets are DISJOINT for helper/main: a worker UID guess
 * cannot accidentally pass. No settings, shell, extra VPN or direct peer access.
 * Same exact independently audited peer and outer real STOP barrier still owed.
 */
object ClientSteerP1ProxyChecks {
    private const val HELPER="org.mcrf.p1owner"
    private class Session(val id:String,val kind:String,val family:Int){val ready=CompletableFuture<Bundle>();val closed=CompletableFuture<Unit>()}
    private val cases=listOf("HTTP_CONNECT" to 4,"HTTP_CONNECT" to 6,"HTTP_ABSOLUTE" to 4,"HTTP_ABSOLUTE" to 6,
        "SOCKS_CONNECT" to 4,"SOCKS_CONNECT" to 6,"SOCKS_DNS" to 4,"SOCKS_DNS" to 6,
        "HTTP_BAD_AUTH" to 4,"SOCKS_BAD_AUTH" to 4,"SOCKS_DENIED" to 4,"SOCKS_CONNECT" to 4)
    @JvmStatic fun run(context:Context,network:Network,helperSha:String,nonce:String,live:()->Unit):JSONArray {
        check(BuildConfig.DEBUG&&Looper.myLooper()!=Looper.getMainLooper()&&nonce.matches(Regex("[0-9a-f]{32}")))
        check(ClientP1OwnerFixtureScope.packages(context,helperSha)==listOf(context.packageName,HELPER,"$HELPER.peer"));live()
        val port=McrfVpnService.localProxyPort;check(port in 1024..65535)
        val pm=context.packageManager;val uid=pm.getApplicationInfo(HELPER,0).uid;check(uid!=android.os.Process.myUid())
        val thread=HandlerThread("p1-proxy-replies").apply {start()};val current=AtomicReference<Session?>();val connected=CompletableFuture<Messenger>()
        val reply=Messenger(Handler(thread.looper){message->val session=current.get();val data=message.data
            if(session!=null&&message.sendingUid==uid&&data.getString("nonce")==nonce&&data.getString("session")==session.id){
                when(message.what){3->session.ready.complete(Bundle(data));4->session.closed.complete(Unit);5->session.ready.completeExceptionally(IllegalStateException("P1_PROXY_HELPER_IO_FAILED"))}
            };true})
        val connection=object:ServiceConnection{
            override fun onServiceConnected(name:ComponentName,binder:IBinder){connected.complete(Messenger(binder))}
            override fun onServiceDisconnected(name:ComponentName){current.get()?.ready?.completeExceptionally(IllegalStateException("P1_PROXY_HELPER_DIED"));current.get()?.closed?.completeExceptionally(IllegalStateException("P1_PROXY_HELPER_DIED"))}
        }
        var bound=false;var remote:Messenger?=null;val evidence=JSONArray()
        fun finish(session:Session){
            requireNotNull(remote).send(Message.obtain(null,2).apply {data=Bundle().apply {putString("nonce",nonce);putString("session",session.id)};replyTo=reply})
            session.closed.get(4,TimeUnit.SECONDS);current.compareAndSet(session,null);live()
        }
        try{
            // Main MUST NOT gain helper-only targets, even though its own health
            // target is permitted. Positive traffic follows each negative below.
            for(host in listOf("198.18.0.3","fd77::3")){
                live();var denied=false
                try{Socket().use {socket->network.bindSocket(socket);socket.soTimeout=1500;socket.connect(InetSocketAddress(InetAddresses.parseNumericAddress(host),80),1500)
                    socket.getOutputStream().write("GET /owned/$nonce HTTP/1.1\r\nHost: ${if(':' in host)"[$host]"else host}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));socket.getOutputStream().flush()
                    denied=socket.getInputStream().read()==-1
                }}catch(_:java.io.IOException){denied=true}
                check(denied){"P1_PROXY_MAIN_IMPERSONATED_HELPER"}
            }
            val info=pm.getServiceInfo(ComponentName(HELPER,"$HELPER.ProxyProbeService"),0)
            check(info.enabled&&info.exported&&info.permission=="org.mcrf.client.permission.P1_OWNER")
            bound=context.bindService(Intent().setComponent(ComponentName(HELPER,info.name)),connection,Context.BIND_AUTO_CREATE);check(bound);remote=connected.get(5,TimeUnit.SECONDS)
            for((index,entry)in cases.withIndex()){
                live();check(pm.getApplicationInfo(HELPER,0).uid==uid)
                val session=Session(UUID.randomUUID().toString().replace("-",""),entry.first,entry.second);check(current.compareAndSet(null,session))
                var failed:Throwable?=null
                try{
                    requireNotNull(remote).send(Message.obtain(null,1).apply {data=Bundle().apply {putString("nonce",nonce);putString("session",session.id);putString("kind",session.kind);putInt("family",session.family);putInt("port",port)};replyTo=reply})
                    val result=session.ready.get(12,TimeUnit.SECONDS);live()
                    check(result.keySet()==setOf("nonce","session","kind","family","uid")&&result.getString("kind")==session.kind&&result.getInt("family")==session.family&&result.getInt("uid")==uid)
                    check(pm.getPackagesForUid(uid)?.toSet()==setOf(HELPER,"$HELPER.peer")){"P1_PROXY_SHARED_UID_UNION_CHANGED"}
                    evidence.put(JSONObject().put("step",index+1).put("kind",session.kind).put("family",session.family).put("uid",uid).put("sharedUidUnion",JSONArray().put(HELPER).put("$HELPER.peer")))
                }catch(error:Throwable){failed=error;throw error}
                finally{try{finish(session)}catch(error:Throwable){if(failed!=null)failed.addSuppressed(error)else throw error}}
            }
            check(ClientP1OwnerFixtureScope.packages(context,helperSha)==listOf(context.packageName,HELPER,"$HELPER.peer"));validateEvidence(evidence);return evidence
        }finally{
            val session=current.get();if(session!=null&&remote!=null)try{finish(session)}finally{if(bound)context.unbindService(connection);thread.quitSafely()}
            else {if(bound)context.unbindService(connection);thread.quitSafely()}
        }
    }
    @JvmStatic fun validateEvidence(value:JSONArray){
        require(value.length()==cases.size);var uid:Int?=null
        repeat(cases.size){index->val row=value.getJSONObject(index);val expected=cases[index]
            require(row.keys().asSequence().toSet()==setOf("step","kind","family","uid","sharedUidUnion")&&row.opt("step") is Int&&row.getInt("step")==index+1&&
                row.opt("kind")==expected.first&&row.opt("family") is Int&&row.getInt("family")==expected.second&&row.opt("uid") is Int&&row.getInt("uid")>=0)
            val packages=row.getJSONArray("sharedUidUnion");require(packages.length()==2&&packages.opt(0)==HELPER&&packages.opt(1)=="$HELPER.peer")
            if(uid==null)uid=row.getInt("uid")else require(uid==row.getInt("uid"))
        }
    }
}
