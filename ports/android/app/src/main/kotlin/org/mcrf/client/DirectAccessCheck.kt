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
    fun check(context:Context,includeYoutube:Boolean=false,cancelled:()->Boolean={false}):Result {
        fun stopped()=Thread.currentThread().isInterrupted||cancelled()
        if(stopped())return Result(Outcome.CANCELLED,Outcome.CANCELLED,if(includeYoutube)Outcome.CANCELLED else null)
        val network=try{physical(context)}catch(_:SecurityException){return Result(Outcome.RESTRICTED,Outcome.RESTRICTED)}
            ?:return Result(Outcome.NO_NETWORK,Outcome.NO_NETWORK,if(includeYoutube)Outcome.NO_NETWORK else null)
        fun response(host:String,route:Network?):Outcome {
            if(stopped())return Outcome.CANCELLED
            if(route==null)return Outcome.NO_NETWORK
            var connection:HttpURLConnection?=null
            return try {
                val opened=route.openConnection(URL("https://$host/"),java.net.Proxy.NO_PROXY) as HttpURLConnection
                connection=opened
                opened.apply {connectTimeout=2500;readTimeout=2500;instanceFollowRedirects=false;requestMethod="HEAD";useCaches=false}
                val code=opened.responseCode
                if(stopped())Outcome.CANCELLED else if(code in 100..599)Outcome.RESPONSE else Outcome.NO_RESPONSE
            }catch(_:SecurityException){Outcome.RESTRICTED}
            catch(_:Exception){if(stopped())Outcome.CANCELLED else Outcome.NO_RESPONSE}
            finally {connection?.disconnect()}
        }
        val vpn=if(includeYoutube){
            val manager=context.getSystemService(ConnectivityManager::class.java)
            manager.allNetworks.firstOrNull {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}
        }else null
        val result=Result(response("google.com",network),response("gitlab.com",network),if(includeYoutube)response("youtube.com",vpn)else null)
        return try{if(stopped()||physical(context)!=network)Result(Outcome.CANCELLED,Outcome.CANCELLED,if(includeYoutube)Outcome.CANCELLED else null)else result}
            catch(_:SecurityException){Result(Outcome.RESTRICTED,Outcome.RESTRICTED)}
    }
}
