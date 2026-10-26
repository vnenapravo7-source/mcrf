package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/** Google/GitLab on the physical network; optional legacy VPN-network check.
 * The live monitor uses the selected core outbound for YouTube because our UID can be TUN-excluded.
 * No provider/account data, permissive TLS, redirects or response-body logging. */
object DirectAccessCheck {
    const val CONNECT_TIMEOUT_MS=2500
    const val READ_TIMEOUT_MS=2500
    const val BUDGET_MS=5000L
    private val checks=java.util.concurrent.ThreadPoolExecutor(3,3,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(3),{task->Thread(task,"mcrf-direct-check").apply {isDaemon=true}})
    enum class Outcome { RESPONSE, NO_RESPONSE, NO_NETWORK, CANCELLED, RESTRICTED }
    data class Result(val google:Outcome,val gitlab:Outcome,val youtube:Outcome?=null){
        val directComplete get()=google in setOf(Outcome.RESPONSE,Outcome.NO_RESPONSE)&&gitlab in setOf(Outcome.RESPONSE,Outcome.NO_RESPONSE)
        val anyInternetResponse get()=google==Outcome.RESPONSE||gitlab==Outcome.RESPONSE
        val bothUnavailable get()=google==Outcome.NO_RESPONSE&&gitlab==Outcome.NO_RESPONSE
        fun shouldSwitch(enabled:Boolean,hasServers:Boolean,inVpnSection:Boolean)=
            enabled&&hasServers&&inVpnSection&&directComplete&&(bothUnavailable||youtube==Outcome.NO_RESPONSE)
        fun shouldReturn(enabled:Boolean,inVpnSection:Boolean)=
            enabled&&!inVpnSection&&directComplete&&anyInternetResponse
    }
    fun physical(context:Context):Network? {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val networks=manager.allNetworks.filter {network->manager.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }==true}
        return manager.activeNetwork?.takeIf {it in networks}?:networks.maxByOrNull {network->
            val caps=manager.getNetworkCapabilities(network)
            (if(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)100 else 0)+
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true->20
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->10
                    else->0
                }
        }
    }
    fun check(context:Context,includeYoutube:Boolean=false,cancelled:()->Boolean={false},sites:List<String> = BsDetectionSites.load {key,fallback->context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString(key,fallback)?:fallback}):Result {
        require(sites.size==3)
        fun stopped()=Thread.currentThread().isInterrupted||cancelled()
        if(stopped())return Result(Outcome.CANCELLED,Outcome.CANCELLED,if(includeYoutube)Outcome.CANCELLED else null)
        val network=try{physical(context)}catch(_:SecurityException){return Result(Outcome.RESTRICTED,Outcome.RESTRICTED)}
            ?:return Result(Outcome.NO_NETWORK,Outcome.NO_NETWORK,if(includeYoutube)Outcome.NO_NETWORK else null)
        val openedConnections=java.util.concurrent.ConcurrentLinkedQueue<HttpURLConnection>()
        val batchStopped=java.util.concurrent.atomic.AtomicBoolean()
        fun response(host:String,route:Network?):Outcome {
            if(stopped())return Outcome.CANCELLED
            if(route==null)return Outcome.NO_NETWORK
            var connection:HttpURLConnection?=null
            return try {
                val opened=route.openConnection(URL("https://$host/"),java.net.Proxy.NO_PROXY) as HttpURLConnection
                connection=opened
                openedConnections.add(opened)
                if(batchStopped.get()||stopped())return Outcome.CANCELLED
                opened.apply {connectTimeout=CONNECT_TIMEOUT_MS;readTimeout=READ_TIMEOUT_MS;instanceFollowRedirects=false;requestMethod="HEAD";useCaches=false}
                val code=opened.responseCode
                if(batchStopped.get()||stopped())Outcome.CANCELLED else if(code in 100..599)Outcome.RESPONSE else Outcome.NO_RESPONSE
            }catch(_:SecurityException){Outcome.RESTRICTED}
            catch(_:Exception){if(stopped())Outcome.CANCELLED else Outcome.NO_RESPONSE}
            finally {connection?.let {openedConnections.remove(it);it.disconnect()}}
        }
        val vpn=if(includeYoutube){
            val manager=context.getSystemService(ConnectivityManager::class.java)
            manager.allNetworks.firstOrNull {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}
        }else null
        val hosts=sites.take(if(includeYoutube)3 else 2).map(BsDetectionSites::normalize)
        val values=ConnectTiming.measure(context,"direct_check") {
            AccessProbeBatch(checks,BUDGET_MS).run(hosts.size,::stopped,{
                batchStopped.set(true);openedConnections.forEach {runCatching {it.disconnect()}}
            }){i->when(response(hosts[i],if(i==2)vpn else network)){
                Outcome.RESPONSE->AccessProbeBatch.State.RESPONSE
                Outcome.NO_RESPONSE->AccessProbeBatch.State.NO_RESPONSE
                Outcome.CANCELLED->AccessProbeBatch.State.CANCELLED
                else->AccessProbeBatch.State.UNAVAILABLE
            }}
        }.map {when(it){
            AccessProbeBatch.State.RESPONSE->Outcome.RESPONSE
            AccessProbeBatch.State.NO_RESPONSE->Outcome.NO_RESPONSE
            AccessProbeBatch.State.CANCELLED->Outcome.CANCELLED
            AccessProbeBatch.State.UNAVAILABLE->Outcome.RESTRICTED
        }}
        val result=Result(values[0],values[1],if(includeYoutube)values[2]else null)
        return try{if(stopped()||physical(context)!=network)Result(Outcome.CANCELLED,Outcome.CANCELLED,if(includeYoutube)Outcome.CANCELLED else null)else result}
            catch(_:SecurityException){Result(Outcome.RESTRICTED,Outcome.RESTRICTED)}
    }
}
