package org.mcrf.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.system.Os
import org.mcrf.engine.spi.v1.Engine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.app.Notification as AndroidNotification

/** :warpTrace orchestrates the installed sing-box module in :primaryWarp; no TUN or VPN reload. */
class WarpTraceService: ClientPrimaryHostPlatform() {
    private val worker=Executors.newSingleThreadExecutor()
    private val session=WarpTraceSession()
    private val coreLock=Any()
    private var core: ClientPrimaryWorker?=null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent==null) {stopSelf(startId);return START_NOT_STICKY}
        val id=intent.getStringExtra("requestId").orEmpty()
        if(intent.action==CANCEL) {
            session.cancel(id)
            if(session.cancelled(id) && session.activeId()==id) requestOwnedStop()
            if(session.activeId()==null) stopSelf(startId)
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION") val receiver=intent.getParcelableExtra<ResultReceiver>("result")
        fun reject() {receiver?.send(1,Bundle().apply {putString("requestId",id);putString("error","Проверка WARP недоступна. Старый профиль сохранён.")})}
        if(!id.matches(Regex("[0-9a-f-]{36}")) || runCatching {UUID.fromString(id).toString()!=id}.getOrDefault(true)) {reject();if(session.activeId()==null)stopSelf(startId);return START_NOT_STICKY}
        if(ClientPrimaryRetirement.hasPending() || !processActive.compareAndSet(false,true)) {reject();return START_NOT_STICKY}
        if(!session.begin(id)) {processActive.set(false);reject();return START_NOT_STICKY}
        try {
            require(android.app.Application.getProcessName()==packageName+":warpTrace") {"Trace requires its isolated process."}
            require(!EngineUpdates.installationPending(this) && !EngineUpdateScripts.mutationPending())
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("warp-trace","Проверка WARP",NotificationManager.IMPORTANCE_LOW))
            val notice=AndroidNotification.Builder(this,"warp-trace").setSmallIcon(R.drawable.status_icon).setContentTitle("MCRF · проверка WARP")
                .setContentText("Проверяется отдельный туннель; активное соединение не переключается").setOngoing(true).build()
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION,notice)
        } catch(_: Exception) {session.finish(id);processActive.set(false);reject();stopSelf(startId);return START_NOT_STICKY}
        val path=intent.getStringExtra("input")
        worker.execute {
            var input: File?=null;var runtime: File?=null;var trace: String?=null
            try {
                val file=File(path ?: throw IOException("Missing input"))
                require(file.parentFile?.canonicalFile==cacheDir.canonicalFile && file.name=="mcrf-warp-trace-input-$id.json" && !java.nio.file.Files.isSymbolicLink(file.toPath()))
                input=file
                val bytes=ByteArrayOutputStream()
                file.inputStream().use {stream -> val buffer=ByteArray(4096);while(true) {val count=stream.read(buffer);if(count<0)break;require(bytes.size()+count<=131072);bytes.write(buffer,0,count)}}
                val request=JSONObject(bytes.toString("UTF-8"))
                require(request.getString("requestId")==id && request.getString("operation")==WarpProbePlan.OPERATION)
                checkActive(id)
                val config=request.getJSONObject("config")
                // Reconstruct the candidate from native endpoint and discard arbitrary supplied routing/listeners.
                val endpoints=config.getJSONArray("endpoints");require(endpoints.length()==1)
                val node=JSONObject(endpoints.getJSONObject(0).toString());node.remove("domain_resolver")
                val profile=WireGuardProfile.fromEndpoint(node)
                val port=request.getInt("tracePort");val user=request.getString("traceUser");val password=request.getString("tracePassword")
                val isolated=WarpProbePlan.configuration(profile.conf(),port,user,password)
                val folder=File(cacheDir,"mcrf-warp-trace-$id");require(folder.mkdir());runtime=folder;Os.chmod(folder.absolutePath,0x1c0)
                val generation=System.nanoTime().and(Long.MAX_VALUE).coerceAtLeast(1)
                val platform=forRun(generation,{session.cancelled(id)},
                    tun={throw IOException("WARP trace never acquires TUN")},traffic={_,_->})
                val server=try {
                    ClientPrimaryWorker.connect(applicationContext,"singbox",generation,platform,ClientPrimaryWarpWorkerService::class.java)
                        .also {synchronized(coreLock) {core=it}}
                } catch(pending: ClientPrimaryWorker.CleanupPending) {
                    // Authentication/start may fail before connect returns, with native cleanup still pending.
                    synchronized(coreLock) {core=pending.worker}
                    throw pending
                }
                checkActive(id)
                val payload=ClientPrimarySelection.envelope(isolated,false,false)
                val profileJson=isolated.toString()
                require(server.validate(profileJson,payload,Engine.Mode.PROXY).supported) {"WARP primary core rejected candidate"}
                checkActive(id)
                server.start(profileJson,payload,Engine.Mode.PROXY)
                checkActive(id)
                trace=WarpTraceTransport.fetch(port,user,password) {session.cancelled(id) || Thread.currentThread().isInterrupted}
                checkActive(id)
            } catch(_: Exception) {trace=null}
            finally {
                val owned=synchronized(coreLock) {core}
                var response:Bundle?=null;var resultCode=1
                val retired=ClientPrimaryRetirement(owned,
                    requestStop={owned?.requestStop()},
                    retireCallbacks={closePlatform(keepTun=true)},
                    closeOwner={it.close()},
                    dependents=listOf(
                        {closePlatform()},
                        {runtime?.let {deleteRuntime(it,it)};Unit},
                        {input?.let {if(it.exists()&&!it.delete())throw IOException("Trace input cleanup pending")};Unit}),
                    completed={
                        // No completion, STOP acknowledgement or new admission before actual death and dependent cleanup.
                        val aborted=session.cancelled(id)
                        response=Bundle().apply {putString("requestId",id);if(trace!=null&&!aborted)putString("trace",trace) else putString("error","HTTPS trace через выбранный WARP не получен. Старый профиль сохранён.")}
                        resultCode=if(trace!=null&&!aborted)0 else if(aborted)2 else 1
                        try {stopForeground(STOP_FOREGROUND_REMOVE)} catch(_: Exception) { }
                        session.finish(id)
                        synchronized(coreLock) {if(core===owned)core=null}
                        processActive.set(false) // Last, after every potentially throwing completion step.
                    })
                var interrupted=Thread.interrupted()
                try {
                    while(!retired.attempt()) {
                        try {Thread.sleep(retired.retryDelayMillis())} catch(_: InterruptedException) {interrupted=true}
                    }
                    // Publish only after retirement removed its pending barrier. IPC/stop errors
                    // are noncritical and cannot turn acknowledged cleanup back into a pending run.
                    try {receiver?.send(resultCode,response)} catch(_: Exception) { }
                    try {stopSelf(startId)} catch(_: Exception) { }
                } finally {if(interrupted)Thread.currentThread().interrupt()}
            }
        }
        return START_NOT_STICKY
    }
    private fun checkActive(id: String) {if(session.cancelled(id) || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()}
    private fun requestOwnedStop() {val owned=synchronized(coreLock) {core};owned?.requestStop()}
    private fun deleteRuntime(root: File,item: File) {
        require(root.parentFile?.canonicalFile==cacheDir.canonicalFile && root.name.matches(Regex("mcrf-warp-trace-[0-9a-f-]{36}")))
        if(java.nio.file.Files.isSymbolicLink(item.toPath())) {java.nio.file.Files.delete(item.toPath());return}
        val base=root.canonicalPath;val path=item.canonicalPath;require(path==base || path.startsWith(base+File.separator))
        item.listFiles()?.forEach {deleteRuntime(root,it)};if(!item.delete()&&item.exists())throw IOException("Trace runtime cleanup failed")
    }
    override fun onDestroy() {session.close();requestOwnedStop();worker.shutdown();super.onDestroy()}
    companion object {
        const val CANCEL="org.mcrf.client.WARP_TRACE_CANCEL";private const val NOTIFICATION=23
        private val processActive=AtomicBoolean()
    }
}
