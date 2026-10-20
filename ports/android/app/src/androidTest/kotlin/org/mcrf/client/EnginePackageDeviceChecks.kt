package org.mcrf.client

import android.app.Instrumentation
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.function.Consumer

object EnginePackageDeviceChecks {
    @JvmStatic fun run(test: Instrumentation, passed: Consumer<String>) {
        val context = test.targetContext
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val plugin = context.packageManager.getApplicationInfo("org.mcrf.engines.openflux", 0)
        val file = File(context.cacheDir, "mcrf-engine-${UUID.randomUUID()}.apk")
        try {
            File(plugin.sourceDir).copyTo(file); android.system.Os.chmod(file.absolutePath, 0x180)
            EnginePackages.verifyArchive(context, file,"openflux"); EnginePackages.verifyRequirements(context, file, setOf("openflux"))
            verify(true, "Engine APK package, matching signer, interface, ABI and OpenFlux bridge checked on Android")
            val uri = Uri.parse("content://${context.packageName}.engine-package/${file.name}")
            context.contentResolver.openInputStream(uri)!!.use { verify(it.read() == 'P'.code && it.read() == 'K'.code, "Installer URI serves only the intended read-only APK") }
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)!!.use {
                verify(it.moveToFirst() && it.getLong(0) == file.length(), "Installer reports the exact downloaded byte size")
            }
            var refused = false
            try { context.contentResolver.openFileDescriptor(uri, "w")?.close() } catch (_: Exception) { refused = true }
            verify(refused, "Installer URI refuses writes")
            refused = false
            try { context.contentResolver.openInputStream(Uri.parse("content://${context.packageName}.engine-package/profile.enc"))?.close() } catch (_: Exception) { refused = true }
            verify(refused, "Installer provider cannot expose encrypted profiles or arbitrary paths")
            refused = false
            try { EnginePackages.verifyArchive(context, File(context.applicationInfo.sourceDir)) } catch (_: Exception) { refused = true }
            verify(refused, "Same signer but wrong APK package is rejected")
        } finally { file.delete() }

        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        Thread.sleep(800); test.waitForIdleSync()
        val before = ProfileStore.snapshot(context)
        val original = Loft.dark
        try {
            for (dark in listOf(false, true)) {
                val bridge = JSONObject().put("type", "openflux").put("name", "Isolated UI fixture").put("secret", "fixture encryption secret")
                    .put("negotiate", true).put("transports", JSONArray().put(JSONObject().put("type", "direct").put("dial", "127.0.0.1:24444")))
                val pending = EngineImportUi.State(OpenFluxProfile.share(bridge)).apply {
                    phase = "download-ready"; missing = setOf("openflux"); plan = ImportPlan.prepare(source) { error("no network") }
                    asset = EnginePackage("arm64-v8a", "fixture-not-published", java.net.URI("https://github.com/fixture"), 12345678, "0".repeat(64),"openflux")
                    assets=listOf(requireNotNull(asset))
                }
                var ui: EngineImportUi? = null; var window: AlertDialog? = null
                test.runOnMainSync {
                    Loft.apply(dark); ui = EngineImportUi(activity); ui!!.attach(pending)
                    window = EngineImportUi::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(ui) as AlertDialog
                }
                Thread.sleep(350); test.waitForIdleSync()
                test.runOnMainSync {
                    val root = requireNotNull(window!!.window).decorView
                    fun labels(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
                        (if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList())
                    verify(window!!.getButton(AlertDialog.BUTTON_POSITIVE).text == "Скачать и добавить" && labels(root).any { it.text.contains("12345678 байт") }, "One dialog shows required engine, actual bytes and Download + accept ($dark)")
                    verify(labels(root).filterIsInstance<EditText>().all { it.visibility == View.GONE }, "Long pasted link is hidden at the download confirmation ($dark)")
                    root.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(320), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(activity.dp(480), View.MeasureSpec.AT_MOST))
                    root.layout(0,0,root.measuredWidth,root.measuredHeight)
                    verify(root.height <= activity.dp(480) && window!!.getButton(AlertDialog.BUTTON_POSITIVE).width > 0, "Engine confirmation fits a 320dp short-screen dialog ($dark)")
                    window!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                verify(pending.cancelled.get() && ui!!.state == null, "Cancel discards the uncommitted import ($dark)")
            }
            verify(ProfileStore.snapshot(context) == before, "Engine UI tests never alter the user's catalogue, routes or profile")
        } finally { test.runOnMainSync { Loft.apply(original); activity.finish() } }
    }
}
