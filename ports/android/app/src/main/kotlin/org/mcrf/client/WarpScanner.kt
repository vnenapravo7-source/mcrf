package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Existing on-demand warpscout module, using its --request adapter (not NativeCarrier.start). */
class WarpScanner(context: Context, private val cancelled: () -> Boolean = { false }, private val progress: (String, Int, Int) -> Unit = { _,_,_ -> }) : WarpOperations.Backend {
    private val app = context.applicationContext
    override fun execute(request: JSONObject): JSONObject {
        require(!android.os.Looper.getMainLooper().isCurrentThread) { "Подбор WARP выполняется в фоне." }
        val checked = WarpProfile.request(request.getString("operation"), request.optString("account"), request.getString("protocol"), request.getString("country"), request.getBoolean("excludeDme"), request.getBoolean("extended"))
        val usage = EngineUpdateGate.acquireUsage(app)
            ?: throw IllegalStateException("Заверши обновление движка перед подбором WARP. Старый профиль сохранён.")
        // Includes executable lookup/version checks and all child shutdown, before allowing module mutation.
        return usage.use { executeWithModuleUsage(checked) }
    }
    private fun executeWithModuleUsage(checked: JSONObject): JSONObject {
        val binary = NativeCarrier.executable(app, "warpscout")
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val networks = manager.allNetworks.toList()
        val active = manager.activeNetwork
        val handle = WarpNetworkPolicy.select(networks.map { network ->
            val caps = manager.getNetworkCapabilities(network)
            WarpNetworkPolicy.Candidate(network.networkHandle,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true, network == active)
        })
        val physical = networks.first { it.networkHandle == handle }
        val root = File(app.cacheDir, "mcrf-warp-${UUID.randomUUID()}")
        require(root.mkdir()) { "Не удалось создать временную папку WARP." }
        var child: Process? = null
        var reader: Thread? = null
        try {
            Os.chmod(root.absolutePath, 0x1c0)
            val input = File(root, "request.json")
            require(input.createNewFile()); Os.chmod(input.absolutePath, 0x180)
            FileOutputStream(input).use { it.write(checked.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if(cancelled()) throw CancellationException("WARP отменён")
            val builder = ProcessBuilder(binary.absolutePath, "--request", input.absolutePath).directory(root).redirectErrorStream(true)
            // Required by transports/warp/network_android.go. Bind only the child, never the app process.
            builder.environment()["MCRF_NETWORK_HANDLE"] = handle.toString()
            val process = builder.start()
            child = process
            reader = Thread({
                try { process.inputStream.use { stream ->
                    val line = StringBuilder(); var dropped = false
                    while(true) {
                        val b = stream.read(); if(b < 0) break
                        if(b == 10) {
                            if(!dropped && line.startsWith("MCRF_PROGRESS ")) try {
                                val event = JSONObject(line.substring(14).trim())
                                val phase = WarpProgressPhase.parse(event.optString("phase"))
                                val done = event.optInt("completed"); val total = event.optInt("total")
                                if(phase != null && done >= 0 && total >= done) progress(phase, done, total)
                            } catch(_: Exception) { }
                            line.setLength(0); dropped = false
                        } else if(line.length < 4096 && !dropped) line.append(b.toChar()) else { line.setLength(0); dropped = true }
                    }
                } } catch(_: Exception) { }
            }, "mcrf-warp-progress").apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(310)
            while(!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("WARP отменён")
                val caps = manager.getNetworkCapabilities(physical)
                require(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) { "Физическая сеть изменилась. Повтори подбор WARP; старый профиль сохранён." }
                require(System.nanoTime() < deadline) { "Время подбора WARP истекло. Старый профиль сохранён." }
            }
            if(cancelled()) throw CancellationException("WARP отменён")
            require(process.exitValue() == 0) { "Модуль WARP завершился с ошибкой." }
            val result = File(root, "result.json")
            require(result.isFile && result.length() in 2..1048576) { "Модуль WARP не вернул корректный результат." }
            return JSONObject(result.readText(Charsets.UTF_8))
        } finally {
            child?.let { WarpChildProcess.stop(it, reader) }
            // Only known direct children of this invocation; never recurse or follow links.
            listOf("request.json", "result.json").forEach { File(root, it).delete() }
            root.delete()
        }
    }
}
