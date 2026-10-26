package org.mcrf.client

import android.app.Application
import android.app.Instrumentation
import android.widget.Button
import java.io.File
import java.util.function.Consumer

/** Actual Android View/Handler only, detached main-process synthetic data.
 * No WebView, window, profile override, renderer crash, Binder, native or provider.
 * Requires debug/unshrunk target for private fixture reflection. */
object Post55OpenFluxOwnViewLifecycleDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName && !OpenFluxVerificationWebProfile.ready)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val views = mutableListOf<OpenFluxOwnPageView>()
        fun field(view: OpenFluxOwnPageView, name: String): Any? =
            OpenFluxOwnPageView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        var callback: ((String?) -> Unit)? = null
        var loads = 0; var offers = 0; var failures = 0
        fun create() = OpenFluxOwnPageView(base, { true }, { callback = it; loads++ },
            { _, _, done -> offers++; done(false) }, { failures++ }, {}).also { views.add(it) }
        lateinit var deferred: OpenFluxOwnPageView
        lateinit var rejected: OpenFluxOwnPageView
        lateinit var retired: OpenFluxOwnPageView
        try {
            instrumentation.runOnMainSync {
                deferred = create()
                verify(field(deferred, "browser") == null && field(deferred, "pendingHtml") == null && failures == 0,
                    "Own detached View begins paused without constructing a WebView or invoking failure")
                (field(deferred, "open") as Button).performClick()
                (field(deferred, "open") as Button).performClick()
                verify(loads == 1 && offers == 0 && callback != null,
                    "Synthetic native Open click admits one loader and never sends a structured reply")
                callback!!.invoke("<p>synthetic only</p>")
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                verify(field(deferred, "pendingHtml") == "<p>synthetic only</p>" && field(deferred, "browser") == null && failures == 0,
                    "Verified-text fixture callback while paused defers rendering and keeps network/browser uncreated")
                deferred.resumePage(); deferred.resumePage()
                verify(failures == 1 && field(deferred, "closed") == true && field(deferred, "pendingHtml") == null && field(deferred, "browser") == null,
                    "Real profile guard rejects deferred render once and invokes terminal failure callback without a WebView")
                callback = null; rejected = create()
                (field(rejected, "open") as Button).performClick()
                callback!!.invoke(null); callback!!.invoke("<p>late synthetic</p>")
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                verify(failures == 2 && field(rejected, "closed") == true && field(rejected, "pendingHtml") == null && offers == 0,
                    "Missing document invokes one terminal callback and late completion cannot revive the closed View")
                callback = null; retired = create()
                (field(retired, "open") as Button).performClick()
                callback!!.invoke("<p>queued synthetic</p>"); retired.close(); retired.close()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                retired.resumePage()
                verify(field(retired, "closed") == true && field(retired, "pendingHtml") == null && field(retired, "browser") == null && failures == 2 && offers == 0,
                    "Closing exact detached View discards queued document completion without terminal callback or reply")
            }
        } finally { instrumentation.runOnMainSync { views.forEach { it.close() } } }
    }
}
