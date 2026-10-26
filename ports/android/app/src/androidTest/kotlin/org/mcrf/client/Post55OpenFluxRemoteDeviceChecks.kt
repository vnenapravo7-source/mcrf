package org.mcrf.client

import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** New final-batch mode only. Actual private Activity/Service in separate app processes.
 * No provider page is opened, no cookies read/submitted, no main app/VPN/process is killed.
 * WebView initialization can do platform work; this is NOT proof of zero network traffic
 * or cookie-profile isolation. FLAG_SECURE remains intact; no screenshots/page inspection. */
object Post55OpenFluxRemoteDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val manager = requireNotNull(base.getSystemService(ActivityManager::class.java))
        val registry = OpenFluxVerificationRequests.registry
        val child = UUID.randomUUID().toString().replace("-", "")
        val ticket = UUID.randomUUID().toString().replace("-", "")
        val commands = CopyOnWriteArrayList<String>()
        val request = requireNotNull(registry.register(child, ticket,
            requireNotNull(OpenFluxVerificationPolicy.externalPage("https://captcha.invalid/never-opened", "", false, "")),
            { true }, { bytes -> commands.add(bytes.toString(Charsets.US_ASCII)); true }))
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { message }
                SystemClock.sleep(100)
            }
        }
        // Query only tasks of this isolated test UID and exactly our opaque request.
        // Never finish another task, even on a failed launch or cleanup.
        fun ownedTasks() = manager.appTasks.mapNotNull { task ->
            // Task can disappear between enumeration and getTaskInfo. Permission
            // failures are NOT swallowed or treated as proof of cleanup.
            val info = try { task.taskInfo } catch (_: IllegalArgumentException) { return@mapNotNull null }
            val intent = info.baseIntent
            if (intent.component?.className == OpenFluxVerificationActivity::class.java.name &&
                intent.getStringExtra("request") == request.id) task to info else null
        }
        try {
            verify(!OpenFluxVerificationWebProfile.ready && !request.opened.get() && !registry.browserOwnedBy(request.id),
                "Main process has not selected the browser profile or claimed the synthetic request")
            instrumentation.runOnMainSync {
                base.startActivity(OpenFluxVerificationUi.intent(base, request.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            }
            // startActivitySync cannot be used for a different process. Observe the
            // actual registry claim instead: production Binder rejects same PID and
            // wrong UID BEFORE accepting ATTACH, so an opened flag alone is not proof.
            await("Private remote Activity did not acquire the request; do not bypass Android launch restrictions") {
                registry.browserOwnedBy(request.id) && ownedTasks().any { it.second.topActivity != null }
            }
            verify(registry.browserOwnedBy(request.id) && request.opened.get(),
                "Actual separate-process Activity acquired the same-UID cross-PID private Binder request")
            verify(commands.isEmpty() && !request.submitted.get() && registry.get(request.id) === request,
                "Remote window waits for manual action without submitting a native response")
            verify(registry.cancel(request.id) && commands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n") &&
                registry.get(request.id) == null && !request.submit("cookie=synthetic-late"),
                "Retiring the exact fixture sends one cancel and rejects a late reply")
            await("Remote window did not finish after the main process retired its request") {
                ownedTasks().none { it.second.topActivity != null }
            }
            verify(!registry.browserOwnedBy(request.id) && ownedTasks().none { it.second.topActivity != null },
                "Actual remote Activity task finishes after observing request retirement")
        } finally {
            registry.cancel(request.id)
            OpenFluxVerificationUi.cancel(base, request.id)
            instrumentation.runOnMainSync { ownedTasks().forEach { (task, _) ->
                try { task.finishAndRemoveTask() } catch (_: IllegalArgumentException) { /* Already removed; await still checks. */ }
            } }
            await("Owned OpenFlux fixture Activity cleanup could not be confirmed") {
                ownedTasks().none { it.second.topActivity != null }
            }
            registry.closeChild(child)
        }
    }
}
