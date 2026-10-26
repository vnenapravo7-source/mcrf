package org.mcrf.client

import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** Actual private own Activity/Service cross-process admission, unopened page only.
 * No automatic Open/Send, JS, provider, cookies, native process or screen capture.
 * Finishing own task is NOT a remote process-death/renderer-death/kill test. */
object Post55OpenFluxInlineRemoteDeviceChecks {
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
        val request = requireNotNull(registry.registerInline(child, OpenFluxDocumentWire.Pending(ticket),
            { true }, { bytes -> commands.add(bytes.toString(Charsets.US_ASCII)); true }))
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { message }; SystemClock.sleep(100)
            }
        }
        fun ownedTasks() = manager.appTasks.mapNotNull { task ->
            val info = try { task.taskInfo } catch (_: IllegalArgumentException) { return@mapNotNull null }
            val intent = info.baseIntent
            if (intent.component?.className == OpenFluxInlineVerificationActivity::class.java.name &&
                intent.getStringExtra("request") == request.id) task to info else null
        }
        try {
            val entry = base.packageManager.getActivityInfo(ComponentName(base, OpenFluxInlineVerificationActivity::class.java), 0)
            verify(!entry.exported && entry.processName == base.packageName + ":openfluxVerification",
                "Installed private own Activity is nonexported in the dedicated profile process")
            verify(!OpenFluxVerificationWebProfile.ready && !request.opened.get() && !registry.browserOwnedBy(request.id) && commands.isEmpty(),
                "Own fixture begins without claiming browser or selecting its profile in main process")
            instrumentation.runOnMainSync {
                base.startActivity(Intent(base, OpenFluxInlineVerificationActivity::class.java)
                    .setData(android.net.Uri.parse("mcrf-openflux-own-fixture://request/${request.id}"))
                    .putExtra("request", request.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            }
            await("Own private Activity did not acquire request; do not bypass Android launch restrictions") {
                registry.browserOwnedBy(request.id) && ownedTasks().any { it.second.topActivity != null }
            }
            verify(registry.browserOwnedBy(request.id) && request.opened.get() && request.inline,
                "Actual same-UID cross-PID own Binder ATTACH acquired the exact inline request")
            verify(commands.isEmpty() && !request.submitted.get() && registry.get(request.id) === request,
                "Own remote window does not READ or submit before manual Open/Send")
            instrumentation.runOnMainSync { ownedTasks().forEach { (task, _) ->
                try { task.finishAndRemoveTask() } catch (_: IllegalArgumentException) { /* Already removed; await checks. */ }
            } }
            await("Exact own Activity did not retire its request after finishing") {
                registry.get(request.id) == null && !registry.browserOwnedBy(request.id) &&
                    commands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n")
            }
            verify(commands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n") && !request.submitOwn("{\"answer\":true}"),
                "Finishing exact own task sends one scoped cancel and rejects late structured submission")
            await("Exact own task did not finish") { ownedTasks().none { it.second.topActivity != null } }
            verify(ownedTasks().none { it.second.topActivity != null } && !request.requestDocumentAfterUserOpen() &&
                commands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n"),
                "Retired own Activity cannot reopen/read the old ticket or send another native command")
        } finally {
            registry.cancel(request.id); OpenFluxVerificationUi.cancel(base, request.id)
            instrumentation.runOnMainSync { ownedTasks().forEach { (task, _) ->
                try { task.finishAndRemoveTask() } catch (_: IllegalArgumentException) { /* Already removed; await checks. */ }
            } }
            await("Owned inline fixture cleanup could not be confirmed") { ownedTasks().none { it.second.topActivity != null } }
            registry.closeChild(child)
        }
    }
}
