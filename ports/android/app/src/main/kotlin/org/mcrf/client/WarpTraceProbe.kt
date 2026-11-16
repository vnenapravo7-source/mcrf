package org.mcrf.client

import android.content.Context
import android.content.Intent
import android.os.*
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Own trace service/session only. Does not call or cancel the shared catalogue probe. */
class WarpTraceProbe(context: Context): WarpController.TraceProbe {
    private val app=context.applicationContext
    override fun fetch(candidateConfig: String, cancelled: () -> Boolean): String {
        require(Looper.myLooper()!=Looper.getMainLooper()) {"Проверка WARP выполняется в фоне."}
        while(!lease.tryLock(100,TimeUnit.MILLISECONDS)) if(cancelled()) throw java.util.concurrent.CancellationException()
        val id=UUID.randomUUID().toString()
        var input: File?=null;var started=false;var completed=false
        val latch=CountDownLatch(1);val result=AtomicReference<String?>();val failure=AtomicReference<String?>()
        fun cancelOwn() {
            if(started && !completed) try {app.startService(Intent(app,WarpTraceService::class.java).setAction(WarpTraceService.CANCEL).putExtra("requestId",id))} catch(_: Exception) { }
        }
        try {
            if(cancelled()) throw java.util.concurrent.CancellationException()
            val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
            val user=UUID.randomUUID().toString();val password=UUID.randomUUID().toString()+UUID.randomUUID().toString()
            val request=WarpProbePlan.request(candidateConfig,port,user,password).put("requestId",id)
            val bytes=request.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=131072)
            val file=File(app.cacheDir,"mcrf-warp-trace-input-$id.json");check(file.createNewFile());input=file
            Os.chmod(file.absolutePath,0x180);FileOutputStream(file).use {it.write(bytes);it.fd.sync()}
            val receiver=object: ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    if(data?.getString("requestId")!=id) return
                    val trace=data.getString("trace")
                    if(code==0 && trace!=null && trace.toByteArray(Charsets.UTF_8).size<=8192) result.set(trace)
                    else failure.set("Проверка выбранного туннеля WARP не завершилась.")
                    latch.countDown()
                }
            }
            app.startForegroundService(Intent(app,WarpTraceService::class.java).putExtra("requestId",id).putExtra("input",file.absolutePath).putExtra("result",receiver))
            started=true
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
            while(!latch.await(100,TimeUnit.MILLISECONDS)) {
                val aborted=cancelled();val expired=System.nanoTime()>=deadline
                if(aborted || expired) {cancelOwn();WarpProbeDeadline.check(aborted,expired)}
            }
            completed=true
            if(cancelled()) throw java.util.concurrent.CancellationException()
            require(failure.get()==null) {failure.get().orEmpty()}
            return result.get() ?: throw IllegalArgumentException("Не получен HTTPS trace WARP.")
        } catch(error: InterruptedException) {cancelOwn();Thread.currentThread().interrupt();throw java.util.concurrent.CancellationException("Проверка WARP отменена.")}
        finally {
            cancelOwn()
            if(started && !completed) try {latch.await(5,TimeUnit.SECONDS)} catch(_: InterruptedException) {Thread.currentThread().interrupt()}
            input?.delete();lease.unlock()
        }
    }
    companion object {private val lease=ReentrantLock()}
}
