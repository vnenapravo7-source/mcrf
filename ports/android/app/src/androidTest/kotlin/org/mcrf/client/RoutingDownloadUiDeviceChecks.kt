package org.mcrf.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Exercises the actual on-demand dialog and resolver. Clone only, no VPN or module. */
object RoutingDownloadUiDeviceChecks {
    @JvmStatic fun run(test: Instrumentation, passed: Consumer<String>) {
        val app = test.targetContext
        check(BuildConfig.DEBUG && !BuildConfig.OFFLINE_EDITION)
        check(app.packageName in setOf("org.mcrf.client.animationfixture10", "org.mcrf.client.onboardingfixture", "org.mcrf.client.ui20261120"))
        val asset = PresetAssets.catalogue(app).single { it.id == "5f05bab07edab203" }
        val geo = GeoDataConfig.defaults()
        val probe = UUID.randomUUID().toString()
        val geoKinds = listOf("geoip", "geosite")
        for (kind in geoKinds) geo.put("${kind}Url", geo.getString("${kind}Url") + "?mcrfSmoke=$probe")
        val selectors = JSONArray()
        for ((kind, code) in listOf("geoip" to "ru", "geosite" to "google")) selectors.put(JSONObject()
            .put("kind", kind).put("code", code).put("target", "direct"))
        geo.put("selectors", selectors)
        val rules = RouteRules.defaults().put("geoData", geo)
        rules.getJSONArray("rules").put(JSONObject().put("id", "download-smoke").put("name", "Smoke")
            .put("text", "").put("mode", "addresses").put("target", "direct").put("presetIds", JSONArray().put(asset.id)))
        val sourceNames = geoKinds.map { GeoDataConfig.sourceName(geo, it) }
        val geoDir = File(app.filesDir, "mcrf-geodata")
        val presetDir = File(app.filesDir, "routing-assets")
        val srs = File(presetDir, "${asset.id}-${asset.sha256}.srs")
        check(!srs.exists() && sourceNames.none { File(geoDir, it).exists() }) { "Fresh routing cache required" }
        for (folder in listOf(geoDir, presetDir)) check(folder.canonicalFile.parentFile == app.filesDir.canonicalFile)
        val originalGeo = geoDir.list()?.toSet().orEmpty()
        val originalPreset = presetDir.list()?.toSet().orEmpty()
        val geoDirExisted = geoDir.exists()
        val presetDirExisted = presetDir.exists()
        val activity = test.startActivitySync(Intent(app, MainActivity::class.java).putExtra("test_no_prompts", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val dialogField = MainActivity::class.java.getDeclaredField("routingDownloadDialog").apply { isAccessible = true }
        fun dialog() = dialogField.get(activity) as? AlertDialog
        fun main(block: () -> Unit) {
            val problem = AtomicReference<Throwable?>()
            val done = CountDownLatch(1)
            check(Handler(Looper.getMainLooper()).post { try { block() } catch (e: Throwable) { problem.set(e) } finally { done.countDown() } })
            check(done.await(12, TimeUnit.SECONDS)) { "Main queue timed out" }
            problem.get()?.let { throw it }
        }
        fun until(seconds: Long, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for routing download" }
                Thread.sleep(50)
            }
        }
        fun readUi(view: View): String = when (view) {
            is TextView -> view.text.toString()
            is ViewGroup -> (0 until view.childCount).joinToString("\n") { readUi(view.getChildAt(it)) }
            else -> ""
        }
        fun missing() = try { PresetAssets.requireAvailable(app, rules); error("Expected missing routing data") }
            catch (required: PresetAssets.DownloadRequired) { required }
        val continued = AtomicInteger()
        val cancelled = AtomicInteger()
        try {
            until(12) { var ready = false; main { ready = !activity.working() }; ready }
            val routingBefore = ProfileStore.snapshot(app)
            PresetAssets.requireAvailable(app, RouteRules.defaults())
            val first = missing()
            val names = listOf(asset.name, "GeoIP — IP-сети", "GeoSite — домены")
            check(first.names == names)
            main {
                activity.offerPresetDownload(first, { cancelled.incrementAndGet() }) { continued.incrementAndGet() }
                val shown = requireNotNull(dialog())
                activity.offerPresetDownload(first, { cancelled.incrementAndGet() }) { continued.incrementAndGet() }
                check(dialog() === shown && shown.isShowing)
                val text = readUi(requireNotNull(shown.window).decorView)
                check(names.all(text::contains))
                check(shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
            }
            until(4) { cancelled.get() == 2 }
            check(continued.get() == 0 && !srs.exists() && sourceNames.none { File(geoDir, it).exists() })
            check(!McrfVpnService.hasActiveSession())
            passed.accept("SRS + GeoIP + GeoSite use one device dialog. Duplicate prompt is suppressed and decline writes no data.")

            main { activity.offerPresetDownload(missing()) { continued.incrementAndGet() } }
            main { check(requireNotNull(dialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()) }
            val error = AtomicReference<String?>()
            until(65) {
                main { dialog()?.window?.decorView?.let { readUi(it) }?.takeIf { it.contains("Не удалось загрузить") }?.let(error::set) }
                check(error.get() == null) { "Public geodata download failed on device" }
                continued.get() == 1
            }
            check(srs.length() == asset.bytes && PresetAssets.sha256(srs.readBytes()) == asset.sha256)
            check(sourceNames.all { File(geoDir, it).length() > 0 })
            PresetAssets.requireAvailable(app, rules)
            val paths = PresetAssets.resolve(app, rules)
            check(paths.keys == setOf(asset.id, "geo-0", "geo-1"))
            for ((key, column) in listOf("geo-0" to "ip_cidr", "geo-1" to "domain_suffix")) {
                val compiled = JSONObject(File(paths.getValue(key)).readText()).getJSONArray("rules").getJSONObject(0)
                check(compiled.optJSONArray(column)?.length()?.let { it > 0 } == true)
            }
            check(ProfileStore.snapshot(app) == routingBefore && !McrfVpnService.hasActiveSession())
            passed.accept("A single approval downloads real public data, checks SRS SHA and DAT categories, resumes once and resolves from cache without changing profiles.")
        } finally {
            main { dialog()?.dismiss(); activity.finish() }
            for ((folder, before, existed) in listOf(Triple(geoDir, originalGeo, geoDirExisted), Triple(presetDir, originalPreset, presetDirExisted))) {
                for (entry in folder.listFiles().orEmpty().filter { it.name !in before }) {
                    check(entry.canonicalFile.parentFile == folder.canonicalFile && entry.isFile)
                    check(entry.delete())
                }
                if (!existed && folder.exists()) check(folder.delete())
            }
        }
        passed.accept("Disposable clone routing cache restored to its initial state.")
    }
}
