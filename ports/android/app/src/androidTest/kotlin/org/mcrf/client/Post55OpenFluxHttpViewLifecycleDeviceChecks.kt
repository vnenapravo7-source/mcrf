package org.mcrf.client

import android.app.Application
import android.app.Instrumentation
import android.webkit.WebResourceResponse
import android.widget.Button
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.function.Consumer

/** Actual detached Android View/Handler callbacks, synthetic binary responses.
 * Main process intentionally fails the real profile guard BEFORE WebView creation.
 * No profile override, browser, window, JS, Binder, native child or provider. */
object Post55OpenFluxHttpViewLifecycleDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName && !OpenFluxVerificationWebProfile.ready)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val views = mutableListOf<OpenFluxHttpPageView>()
        fun field(view: OpenFluxHttpPageView, name: String): Any? =
            OpenFluxHttpPageView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
        fun closed(view: OpenFluxHttpPageView) = (field(view, "closed") as AtomicBoolean).get()
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        fun loaded(bytes: ByteArray) = OpenFluxHttpPageView.Loaded("http://127.0.0.1:1/",
            OpenFluxHttpIpcResponse.Response("b".repeat(32), 200, listOf("Content-Type" to "text/html; charset=utf-8"), bytes))
        var callback: ((OpenFluxHttpPageView.Loaded?) -> Unit)? = null
        var loads = 0; var exchanges = 0; var submissions = 0; var failures = 0; var cancellations = 0
        fun create() = OpenFluxHttpPageView(base, { true }, { callback = it; loads++ },
            { _, done -> exchanges++; done(null) }, { _, done -> submissions++; done(false) },
            { failures++ }, { cancellations++ }).also { views.add(it) }
        fun open(view: OpenFluxHttpPageView) { (field(view, "open") as Button).performClick() }
        lateinit var guard: OpenFluxHttpPageView
        lateinit var absent: OpenFluxHttpPageView
        val guardedBody = "<p>synthetic only</p>".toByteArray()
        val duplicateBody = byteArrayOf(7, 8)
        try {
            instrumentation.runOnMainSync {
                val queued = create()
                open(queued)
                verify(loads == 0 && field(queued, "browser") == null && !(field(queued, "send") as Button).isEnabled,
                    "Paused HTTP View neither loads nor constructs browser or enables Send")
                queued.resumePage(); open(queued); open(queued)
                verify(loads == 1 && callback != null && exchanges == 0 && submissions == 0,
                    "Duplicate synthetic native Open click admits one loader and no resource/reply")
                val queuedBody = byteArrayOf(1, 2, 3)
                callback!!.invoke(loaded(queuedBody)); queued.close(); queued.close()
                verify(closed(queued) && queuedBody.all { it == 0.toByte() } &&
                    (field(queued, "pendingResponses") as Set<*>).isEmpty() && field(queued, "browser") == null,
                    "Close drains owned queued response even when its Handler callback is removed")
                val late = create(); late.resumePage(); open(late)
                val lateCallback = callback!!; late.close()
                val lateBody = byteArrayOf(4, 5); lateCallback(loaded(lateBody))
                verify(lateBody.all { it == 0.toByte() } && closed(late) &&
                    (field(late, "pendingResponses") as Set<*>).isEmpty() && failures == 0,
                    "First response arriving after close is immediately wiped without reviving View")
                val paused = create(); paused.resumePage(); open(paused)
                val pausedCallback = callback!!; paused.pausePage()
                val pausedBody = byteArrayOf(6); pausedCallback(loaded(pausedBody)); paused.resumePage()
                verify(closed(paused) && pausedBody[0] == 0.toByte() && cancellations == 1 &&
                    field(paused, "browser") == null && submissions == 0,
                    "Pause after Open cancels once and late bytes/resume cannot revive old page epoch")
                guard = create(); guard.resumePage(); open(guard); callback!!.invoke(loaded(guardedBody))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                verify(closed(guard) && guardedBody.all { it == 0.toByte() } && failures == 1 &&
                    field(guard, "browser") == null && !OpenFluxVerificationWebProfile.ready,
                    "Real main-process profile guard fails closed and wipes response BEFORE creating WebView")
                absent = create(); absent.resumePage(); open(absent)
                callback!!.invoke(null); callback!!.invoke(loaded(duplicateBody))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                verify(closed(absent) && failures == 2 && duplicateBody.all { it == 0.toByte() } &&
                    field(absent, "browser") == null && exchanges == 0 && submissions == 0,
                    "Missing response fails once; duplicate completion wipes its body without traffic or reply")
                // Actual private handoff + OwnedStream, no WebView construction.
                val streamView = create()
                val type = OpenFluxHttpPageView::class.java.declaredClasses.single { it.simpleName == "Waiter" }
                fun waiter() = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                fun closeWaiter(value: Any) { type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(value) }
                fun take(value: Any) = type.getDeclaredMethod("take").apply { isAccessible = true }.invoke(value) as? WebResourceResponse
                fun pending(value: Any) = type.getDeclaredMethod("pending").apply { isAccessible = true }.invoke(value) as Boolean
                fun publish(value: Any, response: WebResourceResponse) = type.getDeclaredMethod("publish", WebResourceResponse::class.java)
                    .apply { isAccessible = true }.invoke(value, response) as Boolean
                fun stream(bytes: ByteArray) = OpenFluxHttpPageView::class.java
                    .getDeclaredMethod("resourceResponse", OpenFluxHttpIpcResponse.Response::class.java)
                    .apply { isAccessible = true }.invoke(streamView, loaded(bytes).response) as WebResourceResponse
                fun signalled(value: Any) = (type.getDeclaredField("latch").apply { isAccessible = true }.get(value) as CountDownLatch).count == 0L
                val retired = waiter(); closeWaiter(retired)
                val retiredBody = byteArrayOf(11)
                verify(!publish(retired, stream(retiredBody)) && retiredBody[0] == 0.toByte() &&
                    take(retired) == null && !pending(retired) && signalled(retired),
                    "Retired HTTP resource handoff rejects and wipes a late stream atomically")
                val abandoned = waiter(); val abandonedBody = byteArrayOf(21)
                check(publish(abandoned, stream(abandonedBody))); closeWaiter(abandoned); closeWaiter(abandoned)
                verify(abandonedBody[0] == 0.toByte() && take(abandoned) == null && signalled(abandoned),
                    "HTTP resource published before cancellation is closed once and cannot be claimed")
                val consumed = waiter(); val consumedBody = byteArrayOf(31, 32); val original = stream(consumedBody)
                check(publish(consumed, original)); val claimed = take(consumed); closeWaiter(consumed)
                val retainedBeforeRead = consumedBody.contentEquals(byteArrayOf(31, 32))
                val first = claimed?.data?.read(); val second = claimed?.data?.read()
                verify(claimed === original && take(consumed) == null && retainedBeforeRead && first == 31 && second == 32 &&
                    consumedBody.all { it == 0.toByte() } && signalled(consumed),
                    "HTTP resource transfers to one consumer; handoff close cannot wipe it before owned stream EOF")
                val duplicate = waiter(); val firstBody = byteArrayOf(41); val secondBody = byteArrayOf(42)
                check(publish(duplicate, stream(firstBody))); val duplicateAccepted = publish(duplicate, stream(secondBody))
                val firstRetained = firstBody[0] == 41.toByte(); closeWaiter(duplicate)
                verify(!duplicateAccepted && firstRetained && firstBody[0] == 0.toByte() && secondBody[0] == 0.toByte(),
                    "Duplicate HTTP resource publication discards only the new owner then cancellation drains the original")
                val closing = waiter(); val closingBody = byteArrayOf(51)
                check(publish(closing, stream(closingBody)))
                @Suppress("UNCHECKED_CAST")
                val pendingWaiters = field(streamView, "pendingWaiters") as MutableSet<Any>
                pendingWaiters.add(closing); streamView.close()
                verify(closingBody[0] == 0.toByte() && take(closing) == null && signalled(closing) && pendingWaiters.isEmpty() &&
                    field(streamView, "browser") == null && !OpenFluxVerificationWebProfile.ready,
                    "HTTP View close drains a published unclaimed handoff and keeps WebView uncreated")
            }
        } finally { instrumentation.runOnMainSync { views.forEach { it.close() } } }
    }
}
