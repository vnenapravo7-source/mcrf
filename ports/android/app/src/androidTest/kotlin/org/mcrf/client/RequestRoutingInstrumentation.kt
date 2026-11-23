package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Real Android VM coverage for the imported compression/parser dependencies and APK archive. */
class RequestRoutingInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart() {
        val report=Bundle()
        try {
            check(BuildConfig.DEBUG)
            val domains=context.assets.open("routing/roscom-steam.mrs").use {MihomoMrs.decode(it.readBytes(),"domain")}
            check(domains.toString().contains("steampowered.com"))
            val ips=context.assets.open("routing/metacubex-telegram-ip.mrs").use {MihomoMrs.decode(it.readBytes(),"ipcidr")}
            check(ips.getJSONArray("rules").length()>0)
            val yaml=context.assets.open("routing/roscomvpn-mihomo.yaml").bufferedReader().use {it.readText()}
            val parsed=ImportedRoutingProfile.parse(yaml)
            check(ImportedRuleProviders.rows(parsed.settings.getJSONObject("geoData")).size>20)
            providerCache()
            geodataCache()
            val saved=VersionArchive.capture(targetContext,targetContext.packageName)
            check(VersionArchive.verify(targetContext,saved).length()==saved.bytes)
            report.putString("result","PASS: Android MRS domain/IP, safe YAML, profile DAT/provider fallback/isolation, installed APK signer/hash archive")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable) {
            report.putString("failure",e.javaClass.name+": "+e.message)
            android.util.Log.e("McrfRequestRouting","Device regression failed",e)
            finish(Activity.RESULT_CANCELED,report)
        }
    }
    private fun geodataCache() {
        val folder=File(targetContext.cacheDir,"mcrf-dat-test-${java.util.UUID.randomUUID()}");check(folder.mkdir())
        val scoped=object:android.content.ContextWrapper(targetContext){override fun getFilesDir()=folder}
        try {
            val geo=GeoDataConfig.defaults().put("source","custom").put("cacheNamespace","profile-"+"c".repeat(32))
                .put("geoipUrl","https://127.0.0.1:9/geoip.dat").put("geositeUrl","https://127.0.0.1:10/geosite.dat")
            val selectors=org.json.JSONArray()
            val standard=GeoDataConfig.defaults()
            for(kind in listOf("geoip","geosite")) {
                selectors.put(org.json.JSONObject().put("kind",kind).put("code","private").put("target","direct"))
                val bytes=context.assets.open("routing/loyalsoldier-private-$kind.dat").use {it.readBytes()}
                GeoDataConfig.file(scoped,geo,kind).writeBytes(bytes)
                GeoDataConfig.file(scoped,standard,kind).writeBytes(bytes)
            }
            geo.put("selectors",selectors)
            GeoDataConfig.withSourceWriteLock(scoped,geo,"geoip") {
                val changed=org.json.JSONObject(geo.toString()).put("geoipUrl","https://127.0.0.1:12/another.dat")
                var busy=false
                try {GeoDataConfig.withSourceWriteLock(scoped,changed,"geoip"){error("Concurrent URL writer entered shared last DAT")}}
                catch(e:IllegalStateException){busy=e.message=="Геобаза уже загружается или обновляется."}
                check(busy){"Different URLs raced on shared profile last DAT"}
                changed.put("cacheNamespace","profile-"+"e".repeat(32))
                GeoDataConfig.withSourceWriteLock(scoped,changed,"geoip"){} // Unrelated profile remains independent.
            }
            for(kind in listOf("geoip","geosite")) {
                val original=GeoDataConfig.file(scoped,geo,kind).readBytes()
                val same=GeoDataConfig.download(scoped,geo,kind,force=true)
                check(same.readBytes().contentEquals(original)){"Failed refresh damaged usable DAT"}
                check(GeoDataConfig.download(scoped,geo,kind,force=true,refreshDeadlineNanos=System.nanoTime()-1).readBytes().contentEquals(original)){"Expired refresh budget bypassed usable DAT fallback"}
                geo.put("${kind}Url","https://127.0.0.1:11/changed-$kind.dat")
                val moved=GeoDataConfig.download(scoped,geo,kind,force=true)
                check(moved!=same&&moved.readBytes().contentEquals(original)){"Changed URL did not use profile-owned last DAT"}
                check(GeoDataConfig.file(scoped,standard,kind).readBytes().contentEquals(original)){"Profile DAT replaced standard dataset"}
                val other=org.json.JSONObject(geo.toString()).put("cacheNamespace","profile-"+"d".repeat(32))
                var rejected=false
                try {GeoDataConfig.download(scoped,other,kind,force=true)}catch(_:Exception){rejected=true}
                check(rejected){"Profile borrowed another profile's DAT"}
                val missing=org.json.JSONObject(geo.toString()).put("selectors",org.json.JSONArray().put(org.json.JSONObject().put("kind",kind).put("code","not-in-dataset").put("target","direct")))
                rejected=false
                try {GeoDataConfig.download(scoped,missing,kind,force=true)}catch(_:Exception){rejected=true}
                check(rejected){"Missing category accepted cached DAT"}
                check(moved.readBytes().contentEquals(original)){"Rejected category replaced valid cache"}
            }
            val state=RouteRules.defaults().put("geoData",geo)
            val paths=GeoDataConfig.resolve(scoped,state)
            check(paths.keys.containsAll(listOf("geo-0","geo-1")))
            check(org.json.JSONObject(File(paths.getValue("geo-0")).readText()).getJSONArray("rules").getJSONObject(0).has("ip_cidr"))
            check(org.json.JSONObject(File(paths.getValue("geo-1")).readText()).getJSONArray("rules").length()>0)
        }finally {check(folder.canonicalFile.parentFile==targetContext.cacheDir.canonicalFile);folder.deleteRecursively()}
    }
    private fun providerCache() {
        val folder=File(targetContext.cacheDir,"mcrf-provider-test-${java.util.UUID.randomUUID()}")
        check(folder.mkdir())
        val scoped=object:android.content.ContextWrapper(targetContext){override fun getFilesDir()=folder}
        try {
            val ns="profile-"+"a".repeat(32)
            val p=org.json.JSONObject().put("tag","mcrf-import-0").put("behavior","domain").put("format","text").put("url","https://127.0.0.1:9/rules").put("interval",300)
            val geo=GeoDataConfig.defaults().put("cacheNamespace",ns).put("ruleProviders",org.json.JSONArray().put(p))
            val root=File(folder,"mcrf-routing-providers");check(root.mkdir())
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(p.toString().toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
            val document="""{"version":3,"rules":[{"domain":["cache.example"]}]}"""
            File(root,"$ns-$hash.json").writeText(document)
            java.nio.channels.FileChannel.open(File(root,"$ns.lock").toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE).use {channel->channel.lock().use {
                var busy=false
                try {ImportedRuleProviders.resolve(scoped,geo)}catch(e:IllegalStateException){busy=e.message=="Списки этого профиля уже обновляются."}
                check(busy){"Provider cache ignored another writer's file lock"}
            }}
            val first=ImportedRuleProviders.resolve(scoped,geo).getValue("mcrf-import-0")
            check(File(first).readText()==document){"Fresh cache did not remain usable"}
            p.put("url","https://127.0.0.1:10/changed-url")
            val fallback=ImportedRuleProviders.resolve(scoped,geo,System.nanoTime()-1).getValue("mcrf-import-0")
            check(first==fallback){"Changed URL failed to reuse the profile-owned last valid copy"}
            geo.put("cacheNamespace","profile-"+"b".repeat(32))
            var rejected=false
            try {ImportedRuleProviders.resolve(scoped,geo)}catch(_:Exception){rejected=true}
            check(rejected){"A different profile reused another profile's rules"}
        }finally {check(folder.canonicalFile.parentFile==targetContext.cacheDir.canonicalFile);folder.deleteRecursively()}
    }
}
