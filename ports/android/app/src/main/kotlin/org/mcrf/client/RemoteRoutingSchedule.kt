package org.mcrf.client

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RemoteRoutingNetwork {
    /** Also used by retained GeoIP/GeoSite downloads, independently of the retired URL manager. */
    fun open(context:Context,url:URL):HttpURLConnection {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.allNetworks.firstOrNull {n -> manager.getNetworkCapabilities(n)?.let {it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
            ?:error("Нет доступной сети вне VPN для обновления баз.")
        return network.openConnection(url) as HttpURLConnection
    }
}

object RemoteRoutingSchedule {
    const val JOB_ID=0x4d435244
    /** Call on every startup/upgrade; cancel the persisted job without rewriting encrypted legacy data. */
    @JvmStatic fun cancelRetired(context:Context) {context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)}
    @Suppress("UNUSED_PARAMETER") fun sync(context:Context,state:JSONObject):Boolean {cancelRetired(context);return true}
}

/** Inert compatibility service until main removes the manifest entry. No downloads, writes or retry. */
class RemoteRoutingJob:JobService() {
    override fun onStartJob(params:JobParameters):Boolean {RemoteRoutingSchedule.cancelRetired(this);return false}
    override fun onStopJob(params:JobParameters):Boolean=false
}