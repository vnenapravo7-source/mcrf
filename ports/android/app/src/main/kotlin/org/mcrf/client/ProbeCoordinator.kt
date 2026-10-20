package org.mcrf.client

import android.content.Context
import android.content.Intent
import android.os.*
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ProbeCoordinator {
    private val cancellation = java.util.concurrent.atomic.AtomicLong()
    private val startLock = Any()
    private val measureLock = Any()
    private val observers=java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun observe(observer: () -> Unit) {observers.add(observer)}
    fun unobserve(observer: () -> Unit) {observers.remove(observer)}
    private fun changed() {observers.forEach {it()}}
    fun cancellationToken() = cancellation.get()
    @Volatile var progress = ""
        private set
    @Volatile var measurements: ProbeProgress? = null
        private set
    @Volatile var diagnostic=""
        private set
    @Volatile var nativeDiagnostic=""
        private set
    @Volatile var providerStage=""
        private set
    fun cancel(context: Context) = synchronized(startLock) {
        cancellation.incrementAndGet(); context.stopService(Intent(context, ProbeService::class.java)); Unit
    }
    /** Wait for the other process's actual child/core cleanup before an ordinary TUN can capture its UID. */
    fun awaitQuiescent(context: Context) {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Ожидание замера не должно блокировать интерфейс." }
        val closed=CountDownLatch(1)
        val receiver=object:ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {if(resultCode==0)closed.countDown()}
        }
        synchronized(startLock) {
            cancellation.incrementAndGet()
            context.startService(Intent(context,ProbeService::class.java).setAction(ProbeService.QUIESCE).putExtra("result",receiver))
        }
        check(closed.await(20,TimeUnit.SECONDS)) { "Замер ещё останавливается. Повторите подключение после его завершения." }
    }
    /** Caller uses the client worker; the probe core itself lives in a separate Android process. */
    @JvmOverloads fun measure(context: Context, entries: List<ServerImport.Server>, testUrl: String = "https://www.gstatic.com/generate_204", expectedCancellation: Long = cancellationToken(), providerDiagnostic:Boolean=false,providerVersion:Int=1): Map<String, Long> = synchronized(measureLock) {
        providerStage=""
        fun ensureNotCancelled() {
            if (cancellation.get() != expectedCancellation) throw java.util.concurrent.CancellationException("Замер отменён. Предыдущие результаты сохранены.")
            require(!McrfVpnService.cleanupPending()&&McrfVpnService.status!="Подключение…"&&!McrfVpnService.status.startsWith("Переключаем движок")) {"Дождитесь завершения подключения или остановки перед замером серверов."}
        }
        ensureNotCancelled()
        require(!EngineUpdates.installationPending(context)&&!EngineUpdateScripts.mutationPending()){"Завершите обновление движка перед замером серверов."}
        require(entries.isNotEmpty()) { "Нет серверов для замера." }
        require(providerDiagnostic||providerVersion==1)
        if(providerDiagnostic){ClientOpenFluxProviderGate.requireApproved(entries);ClientOpenFluxProviderGate.verify(context,providerVersion)}
        require(entries.size <= 256) { "За один замер допускается до 256 серверов." }
        require(entries.all { it.configuration == null && (!it.needsEngine() || it.carrier != null) }) { "Отдельные JSON-профили не входят в групповой замер." }
        require(entries.none { it.carrier != null } || !McrfVpnService.status.startsWith("VPN подключён") || McrfVpnService.nativeVpnExclusion) {
            "Для замера дополнительных движков отключи обычный VPN MCRF: иначе проверка пройдёт через его выход, а не текущую сеть."
        }
        val carriers = JSONArray()
        val bridges = entries.map { entry -> if (entry.carrier == null) entry else {
            carriers.put(JSONObject().put("id",entry.id).put("tag",ServerImport.tag(entry)).put("profile",entry.carrier))
            ServerImport.Server(entry.original,entry.name,"socks","127.0.0.1",1,
                JSONObject().put("type","socks").put("version","5").put("server","127.0.0.1").put("server_port",1),null,null)
        } }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val generated = ServerImport.build(bridges, "", true, 60, 50, "Cloudflare")
        val outputs = generated.getJSONArray("outbounds")
        repeat(outputs.length()) { i -> val node = outputs.getJSONObject(i)
            if (node.optString("type") == "urltest") { node.put("type", "selector"); listOf("url", "interval", "tolerance").forEach(node::remove) }
        }
        val config = ProfileConfig.build(generated.toString(), false, port).apply { put("inbounds", JSONArray()) }
        val extraSettings=OptionalCoreStore(context).load()
        val tuningSettings=CoreTuningStore(context).load()
        val extraKinds=OptionalCoreProbe.needed(config,extraSettings)
        require(extraKinds.isEmpty()||!McrfVpnService.status.startsWith("VPN подключён")||McrfVpnService.nativeVpnExclusion) {
            "Для замера дополнительных ядер отключите обычный VPN MCRF: проверка должна пройти через выбранный сервер, а не через активное VPN-подключение."
        }
        val request = JSONObject().put("config", config).put("extraSettings",extraSettings.json()).put("transportSettings",tuningSettings.json()).put("carriers",carriers).put("url",testUrl).put("servers", JSONArray(entries.map { JSONObject().put("id", it.id).put("tag", ServerImport.tag(it)) }))
        if(providerDiagnostic)request.put("providerDiagnostic",true).put("providerDiagnosticVersion",providerVersion).put("providerDiagnosticOriginalSha",ClientOpenFluxProviderGate.APPROVED_ORIGINAL_SHA)
        val file = File(context.cacheDir, "mcrf-probe-input-${UUID.randomUUID()}.json")
        check(file.createNewFile()); Os.chmod(file.absolutePath, 0x180); file.writeText(request.toString(), Charsets.UTF_8)
        val latch = CountDownLatch(1); var result: Map<String, Long>? = null; var failure: String? = null
        var progressRejected=false
        val replies=ProbeReplyWindow()
        fun receive(code:Int,data:Bundle?) {
                if(progressRejected)return
                if (cancellation.get() != expectedCancellation) {
                    if(code!=2){failure="Замер отменён. Предыдущие результаты сохранены.";replies.close();latch.countDown()}
                    return
                }
                if(code==2) {
                    val id=data?.getString("id")
                    if(id!=null && data.containsKey("delay")) {
                        try {measurements=measurements?.record(id,data.getLong("delay"))}
                        catch(_:IllegalArgumentException){
                            progressRejected=true
                            failure="Некорректный результат замера. Предыдущие результаты сохранены."
                            replies.close();latch.countDown();return
                        }
                        progress=measurements?.label().orEmpty()
                        changed()
                    }
                    return
                }
                if(BuildConfig.DEBUG)providerStage=data?.getString("providerStage","").orEmpty()
                if (code == 0 && data != null) { diagnostic=data.getString("diagnostic","");nativeDiagnostic=data.getString("nativeStage","");val values = data.getBundle("delays") ?: Bundle(); result = values.keySet().associateWith { values.getLong(it, -1) } }
                else failure = data?.getString("error") ?: "Не удалось выполнить замер."
                replies.close()
                latch.countDown()
        }
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code:Int,data:Bundle?){replies.deliver {receive(code,data)}}
        }
        try {
            synchronized(startLock) {
                ensureNotCancelled()
                measurements=ProbeProgress(entries.map { it.id }.toSet())
                progress=measurements!!.label()
                diagnostic=""
                nativeDiagnostic=""
                providerStage=""
                changed()
                context.startForegroundService(Intent(context, ProbeService::class.java).putExtra("input", file.absolutePath).putExtra("result", receiver))
            }
            val timeout = 15_000L + ((entries.size + 3) / 4) * 12_000L + carriers.length()*90_000L
            require(latch.await(timeout, TimeUnit.MILLISECONDS)) { "Время замера истекло." }
            ensureNotCancelled()
            require(failure == null) { failure.orEmpty() }
            require(result?.keys == measurements?.ids) { "Получен неполный результат замера. Предыдущие результаты сохранены." }
            require(result?.values?.all {it>=-1}==true) {"Некорректная задержка замера. Предыдущие результаты сохранены."}
            return result ?: throw IllegalArgumentException("Не получен результат замера.")
        } finally {
            replies.close()
            progress=""
            measurements=null
            changed()
            file.delete()
            if (result == null) context.stopService(Intent(context, ProbeService::class.java))
        }
    }
}
