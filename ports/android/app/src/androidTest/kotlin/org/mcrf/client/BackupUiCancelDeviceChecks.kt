package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Coordinator-only real dialog cancellation. No document picker, provider or saved credentials. */
object BackupUiCancelDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val target = instrumentation.targetContext
        val accesses = AtomicInteger()
        val documentOpens = AtomicInteger()
        // MockContentResolver supports provider acquisition used by framework Settings;
        // ContentResolver.wrap does not. Only these two explicit authorities are registered.
        val documentProvider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? {
                check(uri.authority == "settings") { "Unexpected fixture provider query" }
                return target.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = error("Fixture provider writes forbidden")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture provider writes forbidden")
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture provider writes forbidden")
            override fun call(authority: String, method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
                check(authority == "settings" && method.startsWith("GET_")) { "Unexpected fixture provider call" }
                return target.contentResolver.call(authority, method, arg, extras)
            }
            override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
                check(uri == Uri.parse("content://org.mcrf.fixture.invalid/never-opened.enc"))
                documentOpens.incrementAndGet(); throw java.io.FileNotFoundException("Fixture document open forbidden")
            }
            override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor {
                check(uri == Uri.parse("content://org.mcrf.fixture.invalid/never-opened.enc"))
                documentOpens.incrementAndGet(); throw java.io.FileNotFoundException("Fixture document open forbidden")
            }
        }
        documentProvider.attachInfo(target, android.content.pm.ProviderInfo().apply {
            authority = "org.mcrf.fixture.invalid;settings"; exported = false
        })
        val documentResolver = android.test.mock.MockContentResolver(target).apply {
            addProvider("settings", documentProvider)
            addProvider("org.mcrf.fixture.invalid", documentProvider)
        }
        val fixture = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): android.content.ContentResolver = documentResolver
            override fun getFilesDir(): File { accesses.incrementAndGet(); return temporary }
            override fun getCacheDir(): File { accesses.incrementAndGet(); return temporary }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                accesses.incrementAndGet()
                error("Pre-confirmation must not open any saved preferences or credentials")
            }
        }
        val shown = mutableListOf<AlertDialog>()
        fun main(action: () -> Unit) {
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw it }
        }
        // The real host supplies a window token only. The fixture activity never runs onCreate,
        // so it cannot load user settings, start subscriptions or restore a pending generation.
        val host = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .putExtra("test_no_prompts", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var activity: MainActivity? = null
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (host.working() && System.nanoTime() < deadline) Thread.sleep(50)
            main {
                check(!host.active() && !host.working()) { "Host must be idle before cancellation fixture" }
                check(!host.isFinishing && !host.isDestroyed && host.window.decorView.isAttachedToWindow) {
                    "Host window must remain attached during cancellation fixture"
                }
                // View tokens may describe the ViewRootImpl window. The attached top-level
                // window's public LayoutParams carry the app token assigned by its host.
                val parameters = host.window.decorView.layoutParams as? android.view.WindowManager.LayoutParams
                    ?: error("Host decor lacks top-level window parameters")
                val applicationToken = requireNotNull(parameters.token) {
                    "Host window layout application token is unavailable"
                }
                val info = target.packageManager.getActivityInfo(host.componentName, 0)
                activity = instrumentation.newActivity(MainActivity::class.java, fixture,
                    applicationToken, target.applicationContext as Application,
                    Intent(target, MainActivity::class.java), info, "Backup cancellation fixture", null, null, null) as MainActivity
                // onCreate deliberately never runs on this isolated host. Its constructor
                // starts in loading=true, so explicitly model the already-loaded idle
                // state without touching saved preferences or starting real operations.
                MainActivity::class.java.getDeclaredField("loading").apply {isAccessible=true}.setBoolean(activity,false)
                check(!requireNotNull(activity).working()) {"Isolated cancellation host must start idle"}
                check((host.window.decorView.layoutParams as? android.view.WindowManager.LayoutParams)?.token == applicationToken &&
                    host.window.decorView.isAttachedToWindow && !host.isFinishing && !host.isDestroyed) {
                    "Host activity changed during fixture attachment"
                }
            }
            val isolated = requireNotNull(activity)
            val local = BackupUi(isolated) { shown.add(it) }
            val fakeUri = Uri.parse("content://org.mcrf.fixture.invalid/never-opened.enc")
            // Positive control: final ContentResolver stream APIs must reach the trap.
            try { documentResolver.openInputStream(fakeUri); error("Fixture input open accepted") }
            catch (_: java.io.FileNotFoundException) { }
            try { documentResolver.openOutputStream(fakeUri); error("Fixture output open accepted") }
            catch (_: java.io.FileNotFoundException) { }
            check(documentOpens.get() == 2) { "Document I/O trap did not observe both stream APIs" }
            documentOpens.set(0)
            passed.accept("Unregistered fixture resolver observes actual input/output opens; getter alone is allowed")
            fun fields(view: View): List<EditText> {
                val result = mutableListOf<EditText>()
                if (view is EditText) result.add(view)
                if (view is ViewGroup) repeat(view.childCount) { result.addAll(fields(view.getChildAt(it))) }
                return result
            }
            fun cancel(label: String, open: () -> Unit) {
                main {
                    val count = shown.size
                    open()
                    check(shown.size == count + 1) { "Expected one owned confirmation dialog" }
                    val dialog = shown.last()
                    check(dialog.isShowing)
                    fields(dialog.window!!.decorView).forEach { it.setText("Synthetic-cancel-password") }
                    check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                }
                instrumentation.waitForIdleSync()
                main { check(!shown.last().isShowing && !isolated.working()) }
                check(accesses.get() == 0 && documentOpens.get() == 0 && temporary.listFiles().orEmpty().isEmpty())
                passed.accept("$label cancellation closes actual dialog before storage, credential load or operation dispatch")
            }
            cancel("Local export") { local.export() }
            cancel("Local restore") { local.restore(fakeUri) }
            cancel("Reset scope selection") { local.resetDefaults() }
            for (index in 0..2) {
                main {
                    local.resetDefaults()
                    val selection = shown.last()
                    selection.listView.performItemClick(selection.listView.getChildAt(index), index, index.toLong())
                }
                instrumentation.waitForIdleSync()
                main { check(shown.last().isShowing); shown.last().getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                instrumentation.waitForIdleSync()
                main { check(!shown.last().isShowing && !isolated.working()) }
                check(accesses.get() == 0 && documentOpens.get() == 0 && temporary.listFiles().orEmpty().isEmpty())
                passed.accept("Reset scope $index second confirmation Cancel performs no storage/reset work")
            }
            main {
                val returned = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                // No external picker launch and no pending export file. request8 exercises the real
                // cancelled restore branch; a nonnull synthetic URI must still be ignored.
                returned.invoke(isolated, 8, Activity.RESULT_CANCELED, Intent().setData(fakeUri))
                check(!isolated.working() && accesses.get() == 0 && documentOpens.get() == 0)
            }
            passed.accept("Synthetic RESULT_CANCELED restore result ignores URI without document UI or storage access")
            check(temporary.listFiles().orEmpty().isEmpty())
            passed.accept("All cancellation fixtures leave the isolated directory empty")
        } finally {
            main { shown.forEach { it.dismiss() }; host.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}

