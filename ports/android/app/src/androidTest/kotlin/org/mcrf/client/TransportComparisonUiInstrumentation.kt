package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** In-memory UI fixture: never imports servers, runs real probes or edits user preferences. */
class TransportComparisonUiInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = StringBuilder()
        var activity: MainActivity? = null
        val wasDark = Loft.dark
        var code = Activity.RESULT_OK
        fun main(block: () -> Unit) {
            val failure = AtomicReference<Throwable?>()
            runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw it }
            waitForIdleSync()
        }
        fun capture(name: String) {
            val bitmap = uiAutomation.takeScreenshot() ?: error("No screenshot")
            File(targetContext.getExternalFilesDir(null), "ab-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fun descendants(view: View): List<View> = buildList {
            add(view); if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
        }
        try {
            val launched = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra("test_no_prompts", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = launched
            val deadline = System.nanoTime() + 15_000_000_000L
            while (launched.working() && System.nanoTime() < deadline) Thread.sleep(50)
            check(!launched.working())
            fun profiles(host: String, name: String, count: Int) = (1..count).map { n ->
                ServerImport.Server("$host-$n", "$name · вариант $n", "vless", host, 440 + n,
                    JSONObject().put("type", "vless").put("server", host).put("server_port", 440 + n)
                        .put("transport", JSONObject().put("type", if (n % 2 == 0) "grpc" else "ws")), null, null)
            }
            val first = profiles("192.0.2.10", "🇩🇪 Nexus Frankfurt", 9)
            val second = profiles("198.51.100.20", "🇳🇱 Amsterdam", 3)
            for (dark in listOf(false, true)) {
                lateinit var screen: TransportComparisonScreen
                main {
                    Loft.apply(dark)
                    screen = TransportComparisonScreen(launched) {}
                    launched.setContentView(screen)
                    screen.bindGroups(listOf(first, second)); screen.connection(false, true, false)
                }
                main {
                    check(screen.chosen.size == 8)
                    val rows = descendants(screen).filter { it.tag?.toString()?.startsWith("transport-comparison-choice-") == true }
                    check(rows.size == 9)
                    for (row in rows) {
                        check(row.height >= launched.dp(56))
                        val box = descendants(row).filterIsInstance<CheckBox>().single()
                        check(box.left > row.width / 2) { "Checkbox is not on the right" }
                    }
                    screen.findViewWithTag<View>("transport-comparison-choice-${first.last().id}").performClick()
                    check(screen.chosen.size == 8) { "Ninth profile admitted" }
                    rows.first().performClick(); check(screen.chosen.size == 7)
                    rows.last().performClick(); check(screen.chosen.size == 8)
                    screen.findViewWithTag<View>("transport-comparison-group-1").performClick()
                    check(screen.chosen.size == 3 && screen.origin == second.first())
                    check(screen.findViewWithTag<View>("transport-comparison-group-body-0").visibility == View.GONE)
                    second.take(2).forEach { screen.findViewWithTag<View>("transport-comparison-choice-${it.id}").performClick() }
                    check(screen.chosen.size == 1 && !screen.findViewWithTag<View>("transport-comparison-run").isEnabled)
                    second.take(2).forEach { screen.findViewWithTag<View>("transport-comparison-choice-${it.id}").performClick() }
                    check(screen.chosen.size == 3 && screen.findViewWithTag<View>("transport-comparison-run").isEnabled)
                    screen.connection(true, true, false)
                    check(!screen.findViewWithTag<View>("transport-comparison-run").isEnabled)
                    check(screen.findViewWithTag<View>("transport-comparison-disconnect").isShown)
                    screen.connection(false, true, false)
                }
                capture(if (dark) "groups-dark" else "groups-light")
                main {
                    screen.working(true)
                    screen.publish(TransportComparisonReport(second.indices.map { TransportComparisonResult(it + 1, emptyList()) },
                        false, currentSlot = 2, remainingMs = 73_000), second)
                }
                Thread.sleep(650); waitForIdleSync()
                main {
                    check(screen.findViewWithTag<ScrollView>("transport-comparison-scroll").scrollY > 0)
                    check(screen.findViewWithTag<TextView>("transport-comparison-current").text.contains("вариант 2"))
                    check(screen.findViewWithTag<TextView>("transport-comparison-remaining").text.contains("1:"))
                    check(!screen.findViewWithTag<View>("transport-comparison-group-0").isEnabled)
                }
                capture(if (dark) "progress-dark" else "progress-light")
                main {
                    screen.publish(TransportComparisonReport(listOf(
                        TransportComparisonResult(1, listOf(30L, 31L, 32L)),
                        TransportComparisonResult(2, listOf(null, null, null)),
                        TransportComparisonResult(3, listOf(60L, 61L, 62L))), true), second)
                    screen.working(false)
                    check(screen.findViewWithTag<ProgressBar>("transport-comparison-progress").progress == 9)
                    check(descendants(screen.findViewWithTag("transport-comparison-result-2")).filterIsInstance<TextView>().any { it.text == "Нет ответа" })
                    check(screen.findViewWithTag<View>("transport-comparison-hide").visibility == View.VISIBLE)
                    check(descendants(screen.findViewWithTag("transport-comparison-result-1")).filterIsInstance<TextView>()
                        .any { it.text.contains("Лучший") && it.currentTextColor == Loft.pingColor(0L) })
                }
                main { screen.findViewWithTag<ScrollView>("transport-comparison-scroll").fullScroll(View.FOCUS_DOWN) }
                capture(if (dark) "results-dark" else "results-light")
                report.append("PASS ").append(if (dark) "dark" else "light").append(": groups, right checks, 2–8, VPN gate, progress scroll/countdown, results/winner\n")
            }
            main {
                val root = launched.findViewById<ViewGroup>(android.R.id.content)
                launched.setContentView(android.widget.FrameLayout(launched))
                TransportComparisonUi.show(launched, true)
                check(root.findViewWithTag<View>("transport-comparison-section") == null)
            }
            report.append("PASS BS A/B remains unavailable; all fixtures in memory\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED; report.append("FAIL ").append(error.stackTraceToString())
        } finally {
            main { Loft.apply(wasDark); activity?.finish() }
            finish(code, Bundle().apply { putString("stream", report.toString()) })
        }
    }
}
