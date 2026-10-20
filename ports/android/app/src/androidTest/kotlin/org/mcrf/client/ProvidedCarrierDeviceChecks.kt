package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.function.Consumer

/** Private user-supplied profiles. They are never APK assets, logs, or test constants. */
object ProvidedCarrierDeviceChecks {
    @JvmStatic fun run(context: Context, name: String, passed: Consumer<String>) {
        check(BuildConfig.DEBUG)
        require(name.matches(Regex("mcrf-provider-input-[0-9a-f-]{36}\\.json")))
        val file = File(context.cacheDir, name)
        require(file.canonicalFile.parentFile == context.cacheDir.canonicalFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
        val inputs = file.inputStream().use { JSONObject(String(ImportIO.read(it), Charsets.UTF_8)) }
        require(!McrfVpnService.status.startsWith("VPN подключён") && !McrfVpnService.status.startsWith("Прокси "))
        val catalogue = Connections(context).apply { load() }
        val protocols = listOf("openflux", "wdtt", "qwdtt", "csqtt").filter(inputs::has)
        // Parsing is local and atomic: no VK calls or one-use binding happens here.
        val entries = protocols.map { type -> ServerImport.read(inputs.getString(type)).single().also { require(it.protocol == type) } }
        for (entry in entries) {
            catalogue.add(entry.original)
            passed.accept("IMPORTED ${entry.protocol.uppercase()} into MCRF encrypted catalogue")
        }
        entries.firstOrNull { it.protocol == "qwdtt" }?.let { require(it.carrier!!.getJSONArray("hashes").length() == inputs.optInt("qwdttCalls", 24)); passed.accept("QWDTT complete call pool retained with original worker count") }
        for (entry in entries) {
            val measured = ProbeCoordinator.measure(context, listOf(entry), inputs.optString("probeUrl", "https://www.gstatic.com/generate_204"))
            catalogue.recordProbes(measured)
            passed.accept(if ((measured[entry.id] ?: -1L) >= 0) "TRAFFIC PASS ${entry.protocol.uppercase()} real outbound HTTP probe"
                else "TRAFFIC NOT VERIFIED ${entry.protocol.uppercase()} · ${ProbeCoordinator.diagnostic.ifBlank { "connection did not complete" }}")
        }
        file.delete()
        check(context.cacheDir.listFiles()?.none { it.name.startsWith("mcrf-carrier-") || it.name.startsWith("mcrf-probe-") } == true)
        passed.accept("Provided connections retained encrypted; temporary runtime removed")
    }
}
