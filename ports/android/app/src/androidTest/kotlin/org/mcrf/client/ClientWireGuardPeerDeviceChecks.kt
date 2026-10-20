package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.function.Consumer

/** Private generated test peers only. Never imports into or measures the user's catalogue. */
object ClientWireGuardPeerDeviceChecks {
    @JvmStatic fun run(context:Context,name:String,passed:Consumer<String>) {
        check(BuildConfig.DEBUG)
        require(name.matches(Regex("mcrf-wg-input-[0-9a-f-]{36}\\.json")))
        val input=File(context.cacheDir,name)
        require(input.canonicalFile.parentFile==context.cacheDir.canonicalFile && !java.nio.file.Files.isSymbolicLink(input.toPath()))
        val before=ProfileStore.snapshot(context)
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        try {
            val cases=JSONObject(input.readText(Charsets.UTF_8)).getJSONArray("cases")
            require(cases.length()==3)
            val versions=mutableSetOf<String>()
            repeat(cases.length()) {index ->
                val item=cases.getJSONObject(index);val version=item.getString("version")
                require(version in setOf("wg","awg1","awg2") && versions.add(version))
                val entry=ServerImport.read(item.getString("config")).single()
                require(entry.carrier==null && entry.configuration==null)
                for(key in listOf("url","url_ipv6")) {
                    val url=item.getString(key);val parsed=java.net.URI(url)
                    require(parsed.scheme=="http" && parsed.port==8080 && parsed.rawUserInfo==null && parsed.rawQuery==null && parsed.rawFragment==null)
                    require(parsed.host in setOf("10.77.0.1","[fd77::1]","fd77::1") && parsed.path.matches(Regex("/fixture/[0-9a-f]{32}")))
                    val values=ProbeCoordinator.measure(context,listOf(entry),url,ProbeCoordinator.cancellationToken())
                    check(values.size==1 && values[entry.id]!=null && values.getValue(entry.id)>=0) {"$version ${if(key=="url") "IPv4" else "IPv6"} private tunnel HTTP probe did not complete: ${ProbeCoordinator.diagnostic}"}
                    passed.accept("$version actual Android libbox ${if(key=="url") "IPv4" else "IPv6"} tunnel HTTP probe succeeded")
                }
            }
            check(versions==setOf("wg","awg1","awg2"))
        } finally {
            ProbeCoordinator.awaitQuiescent(context)
            check(input.delete() || !input.exists()) {"Private WireGuard input cleanup failed"}
        }
        check(ProfileStore.snapshot(context)==before && context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences)
        passed.accept("Private WG/AWG probes preserve user catalogue, routes and preferences and remove generated input")
    }
}
