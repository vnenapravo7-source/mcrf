package org.mcrf.client

import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Actual private HTTP Activity admission/finish, before manual Open only.
 * Captured native sink, no native process, HTML, Open/Send clicks or screenshots.
 * Task removal is NOT process death, renderer death or blocked-RPC cancellation. */
object Post55OpenFluxHttpRemoteDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName)
        check(!RemoteServerOperationGate.busy())
        // Refuse before registering or launching anything; never wake/unlock the phone.
        check(!requireNotNull(base.getSystemService(KeyguardManager::class.java)).isKeyguardLocked)
        check(requireNotNull(base.getSystemService(PowerManager::class.java)).isInteractive)
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val manager = requireNotNull(base.getSystemService(ActivityManager::class.java))
        val registry = OpenFluxVerificationRequests.registry
        val child = UUID.randomUUID().toString().replace("-", "")
        val ticket = UUID.randomUUID().toString().replace("-", "")
        val commands = CopyOnWriteArrayList<String>()
        val request = requireNotNull(registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket),
            { true }, { bytes -> commands.add(bytes.toString(Charsets.US_ASCII)); true }))
        val cancel = listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n")
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { message }; SystemClock.sleep(100)
            }
        }
        fun ownedTasks(id: String = request.id) = manager.appTasks.mapNotNull { task ->
            val info = try { task.taskInfo } catch (_: IllegalArgumentException) { return@mapNotNull null }
            if (info.baseIntent.component?.className == OpenFluxHttpVerificationActivity::class.java.name &&
                info.baseIntent.getStringExtra("request") == id) task to info else null
        }
        fun finishOwned(id: String = request.id) = instrumentation.runOnMainSync {
            ownedTasks(id).forEach { (task, _) ->
                try { task.finishAndRemoveTask() } catch (_: IllegalArgumentException) { /* Await exact task below. */ }
            }
        }
        try {
            val entry = base.packageManager.getActivityInfo(ComponentName(base, OpenFluxHttpVerificationActivity::class.java), 0)
            verify(!entry.exported && entry.processName == base.packageName + ":openfluxVerification",
                "HTTP Activity is installed nonexported in the dedicated profile process")
            val intent = OpenFluxVerificationUi.httpIntent(base, request.id)
            verify(intent.component == ComponentName(base, OpenFluxHttpVerificationActivity::class.java) &&
                intent.extras?.keySet() == setOf("request") && intent.getStringExtra("request") == request.id &&
                intent.data.toString() == "mcrf-openflux-http-verification://request/${request.id}",
                "Actual HTTP routing helper carries only the exact opaque request ID")
            verify(!OpenFluxVerificationWebProfile.ready && !request.opened.get() &&
                !registry.browserOwnedBy(request.id) && commands.isEmpty(),
                "Main process has neither selected WebView profile nor claimed the HTTP request")
            instrumentation.runOnMainSync {
                base.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            }
            await("HTTP Activity did not acquire request; Android launch restrictions must not be bypassed") {
                registry.browserOwnedBy(request.id) && ownedTasks().any { it.second.topActivity != null }
            }
            verify(request.http && !request.inline && request.opened.get() && request.httpStatus().code == 0 &&
                registry.get(request.id) === request && registry.browserOwnedBy(request.id),
                "Actual HTTP Activity cross-PID ATTACH owns the exact unopened HTTP request")
            verify(commands.isEmpty() && !request.submitted.get() && request.httpReplyStatus() == 0 &&
                request.nextHttpCommand() == null && request.enqueueHttp("GET", "http://127.0.0.1:1/", emptyList(), byteArrayOf()) == null,
                "Window admission alone does not OPEN, request a resource or submit a reply")
            finishOwned()
            await("HTTP Activity finish did not retire its exact request") {
                registry.get(request.id) == null && !registry.browserOwnedBy(request.id) && commands.toList() == cancel
            }
            verify(commands.toList() == cancel && request.httpStatus().code == -1 && request.httpReplyStatus() == -1 &&
                !request.submitted.get(),
                "Finishing unopened HTTP task retires ownership with one exact cancel and no false submission")
            await("Exact HTTP task did not finish") { ownedTasks().none { it.second.topActivity != null } }
            verify(request.nextHttpCommand() == null &&
                request.enqueueHttp("GET", "http://127.0.0.1:1/", emptyList(), byteArrayOf()) == null &&
                commands.toList() == cancel && !OpenFluxVerificationWebProfile.ready,
                "Retired HTTP request cannot replay traffic or select the main-process WebView profile")
            // Separate fresh request: semantic child loss, NOT kernel process death.
            val lostChild = UUID.randomUUID().toString().replace("-", "")
            val lostTicket = UUID.randomUUID().toString().replace("-", "")
            val childAlive = AtomicBoolean(true)
            val lostCommands = CopyOnWriteArrayList<String>()
            val lost = requireNotNull(registry.registerHttp(lostChild, OpenFluxHttpWire.Pending(lostTicket),
                { childAlive.get() }, { bytes -> lostCommands.add(bytes.toString(Charsets.US_ASCII)); true }))
            try {
                instrumentation.runOnMainSync {
                    base.startActivity(OpenFluxVerificationUi.httpIntent(base, lost.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
                await("Fresh unopened HTTP request was not claimed") {
                    registry.browserOwnedBy(lost.id) && ownedTasks(lost.id).any { it.second.topActivity != null }
                }
                verify(lost.httpStatus().code == 0 && lostCommands.isEmpty(),
                    "Fresh semantic-child-loss scenario starts claimed but without manual OPEN")
                childAlive.set(false)
                // Do not explicitly cancel/unbind/finish: actual IDLE POLL must discover loss.
                await("Unopened HTTP Activity did not discover semantic child loss") {
                    // get() prunes stale entries: observe sink FIRST so the fixture
                    // cannot itself retire the dead request before Service cancellation.
                    lostCommands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$lostTicket\n") &&
                        ownedTasks(lost.id).none { it.second.topActivity != null } && registry.get(lost.id) == null
                }
                verify(!lost.submitted.get() && lost.httpReplyStatus() == -1 &&
                    lostCommands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$lostTicket\n"),
                    "Unopened Activity discovers semantic child loss without OPEN/reply/retry or explicit fixture finish")
            } finally {
                try { registry.cancel(lost.id) } finally {
                    try { finishOwned(lost.id) } finally {
                        try { await("Child-loss HTTP task cleanup could not be confirmed") {
                            ownedTasks(lost.id).none { it.second.topActivity != null }
                        } } finally { registry.closeChild(lostChild) }
                    }
                }
            }
        } finally {
            // Each cleanup still attempted if a preceding step throws.
            try { registry.cancel(request.id) } finally {
                try { OpenFluxVerificationUi.cancel(base, request.id) } finally {
                    try { finishOwned() } finally {
                        try { await("Owned HTTP fixture cleanup could not be confirmed") {
                            ownedTasks().none { it.second.topActivity != null }
                        } } finally { registry.closeChild(child) }
                    }
                }
            }
        }
    }
}
