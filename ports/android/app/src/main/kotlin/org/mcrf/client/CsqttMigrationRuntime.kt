package org.mcrf.client

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** One persisted job, one profile per invocation, at most six sequential native
 * candidates. No per-profile executor queue and no UI-thread catalogue IO. */
internal object CsqttMigrationRuntime {
    private const val JOB=0x43535154
    private const val SPACING=30_000L
    private val running=AtomicBoolean()
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"csqtt-unified-migration").apply {isDaemon=true}}
    @Synchronized fun request(context:Context,delay:Long=0) {
        if(running.get())return
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(JOB)!=null)return
        schedule(context,delay)
    }
    private fun schedule(context:Context,delay:Long) {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(JOB,ComponentName(context,CsqttMigrationJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(delay.coerceAtLeast(0)).build())
    }
    private fun unsafe(context:Context):Boolean {
        if(McrfVpnService.hasActiveSession()||McrfVpnService.cleanupPending()||McrfVpnService.wifiPaused()||
            ProbeCoordinator.measurements!=null||SafeModeStore(context).enabled()||CaptivePortalMonitor.blocksAutomatic(context))return true
        val manager=context.getSystemService(ConnectivityManager::class.java)
        // Includes another app's VPN; never stop it or probe provider state through it.
        if(manager.allNetworks.any {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true})return true
        return DirectAccessCheck.physical(context)==null
    }
    fun start(service:JobService,params:JobParameters,stopped:AtomicBoolean):Boolean {
        if(!running.compareAndSet(false,true))return false
        worker.execute {
            val app=service.applicationContext
            var next:Long?=null
            try {next=run(app) {stopped.get()}}
            catch(_:Exception){next=System.currentTimeMillis()+CsqttEngineMigration.DEFER_MS}
            finally {
                synchronized(this) {
                    try {
                        try {if(!stopped.get())service.jobFinished(params,false)}
                        finally {
                            // Keep admission closed through completion and rearm:
                            // a new request must not be finished by this old owner.
                            next?.let {schedule(app,(it-System.currentTimeMillis()).coerceAtLeast(SPACING))}
                        }
                    } finally {running.set(false)}
                }
            }
        }
        return true
    }
    private fun run(context:Context,stopped:()->Boolean):Long? {
        val providerUntil=CsqttProviderCooldown.deadline(context)
        if(providerUntil>System.currentTimeMillis())return providerUntil
        if(stopped()||unsafe(context))return System.currentTimeMillis()+CsqttEngineMigration.DEFER_MS
        // A missing/untrusted replacement does not initialize the one-time cohort.
        // Startup, boot and package broadcasts will re-admit after installation.
        val usage=EngineUpdateGate.acquireUsage(context)?:return System.currentTimeMillis()+CsqttEngineMigration.DEFER_MS
        usage.use {
            val identity=try {BsEnginePackage.migrationIdentity(context)}catch(_:Exception){return null}
            val connections=Connections(context)
            val ticket=connections.csqttMigrationClaim(identity,System.currentTimeMillis())?:return connections.csqttMigrationNextAt()
            val network=DirectAccessCheck.physical(context)?.networkHandle
            val cancelToken=ProbeCoordinator.cancellationToken()
            // This callback is also invoked under the storage commit lock: only
            // in-memory cancellation reads belong here, never Binder/network IO.
            fun cancelled()=stopped()||Thread.currentThread().isInterrupted||
                McrfVpnService.hasActiveSession()||McrfVpnService.cleanupPending()||
                ProbeCoordinator.measurements!=null||ProbeCoordinator.cancellationToken()!=cancelToken||CsqttProviderCooldown.active()
            var checkedAt=Long.MIN_VALUE
            var stale=false
            fun retired():Boolean {
                if(cancelled()||stale)return true
                val now=android.os.SystemClock.elapsedRealtime()
                if(checkedAt==Long.MIN_VALUE||now-checkedAt>=250) {
                    checkedAt=now
                    stale=CsqttProviderCooldown.deadline(context)>System.currentTimeMillis()||unsafe(context)||DirectAccessCheck.physical(context)?.networkHandle!=network||
                        !connections.csqttMigrationCurrent(ticket)
                }
                return stale
            }
            val evidence=CsqttEngineMigration.probe(ticket.profile,::retired) {candidate,target->
                check(!retired()) {"CSQTT_MIGRATION_RETIRED"}
                val effective=if(target.generation=="2.5")CsqttSwgVersions.nativeImplementation(candidate)else target.generation
                require(effective==if(target.generation=="2.5")target.implementation else target.generation)
                // A concrete target bypasses AutoRuntime and its old cache completely.
                val carrier=NativeCarrier.startBackground(context,candidate,BooleanSupplier {retired()},15_000)
                var confirmed=false
                try {
                    check(!retired())
                    confirmed=CarrierSocksSwitch.probe(carrier,"https://www.gstatic.com/generate_204",4_000)>=0
                    carrier.checkFailure()
                    if(carrier.runtimeFailure()!=null)confirmed=false
                } finally {carrier.close()} // Retires only this probe's owned process tree.
                // A lazy handshake can emit a typed rejection only after SOCKS CONNECT.
                // Check retained typed events after retirement; never classify an
                // endpoint timeout as a protocol mismatch.
                carrier.checkFailure()
                if(!confirmed||retired())throw IOException("CSQTT_MIGRATION_NO_REMOTE_PROOF")
                CsqttEngineMigration.Evidence(target,effective,true)
            }
            if(!retired()&&!unsafe(context)&&DirectAccessCheck.physical(context)?.networkHandle==network) {
                val currentIdentity=BsEnginePackage.migrationIdentity(context)
                if(connections.csqttMigrationComplete(ticket,currentIdentity,evidence,::cancelled)) {
                    // Persistence changes the carrier's cache namespace. Do not
                    // publish a second, unguarded preference-cache write here.
                    ProbeCoordinator.presentationChanged()
                }
            }
            val cooldown=CsqttProviderCooldown.deadline(context)
            if(cooldown>System.currentTimeMillis())return cooldown
            return connections.csqttMigrationNextAt()
        }
    }
}

class CsqttMigrationJob:JobService() {
    private var stopped=AtomicBoolean()
    override fun onStartJob(params:JobParameters):Boolean {
        stopped=AtomicBoolean()
        return CsqttMigrationRuntime.start(this,params,stopped)
    }
    override fun onStopJob(params:JobParameters):Boolean {
        stopped.set(true)
        // The worker retires only its own candidate, then persists/rearms the due
        // retry. The claim was saved before native launch, including process death.
        return true
    }
}
