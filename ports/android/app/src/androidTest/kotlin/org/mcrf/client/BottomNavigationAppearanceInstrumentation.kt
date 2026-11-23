package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Switch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Run only in the common batch, with the real UI/device lease held by QA.
 * Captures compositor screenshots; never taps, invalidates or draw()s the bar.
 * Restores only appearance keys; changed encrypted stores fail without rollback. */
class BottomNavigationAppearanceInstrumentation : Instrumentation() {
    private var admission: MainProcessMeasurementLease? = null
    private var historyRecorder: AppearanceHistoryRecorderLease? = null
    private var beforeEncrypted: Map<String, String>? = null
    private var beforeStartupXml: Map<String, String>? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        try {
            // ActivityThread calls instrumentation onCreate before Application.onCreate.
            // Acquire synchronously, before start() can dispatch UI work or startup
            // can schedule a migration job. Same private-lock/identity ownership as
            // the drag fixture, but HERE in the actual main application process.
            admission = MainProcessMeasurementLease.acquire(targetContext, "fixture-appearance")
            // Keep ALL encrypted files (including history/generations) guarded
            // before Application startup; initializing the observer is not a write.
            beforeEncrypted = encryptedStores()
            beforeStartupXml = preferenceFiles()
            historyRecorder = AppearanceHistoryRecorderLease.acquire(targetContext)
            start()
        } catch (error: Throwable) {
            try { historyRecorder?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            try { releaseAdmission() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "FAIL Appearance admission/startup: ${error.stackTraceToString()}")
            })
        }
    }

    private fun releaseAdmission() {
        admission?.close()
        admission = null
    }

    private fun changedPaths(before: Map<String, String>, after: Map<String, String>, prefix: String): String {
        fun names(paths: Set<String>) = paths.sorted().map { prefix + it.replace('\\', '/') }
        return "added=${names(after.keys - before.keys)} removed=${names(before.keys - after.keys)} " +
            "changed=${names(before.keys.intersect(after.keys).filterTo(mutableSetOf()) { before[it] != after[it] })}"
    }

    private fun encryptedStores() = targetContext.filesDir.walkTopDown().filter { it.isFile && it.extension == "enc" }
        .associate { it.relativeTo(targetContext.filesDir).path to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }

    private fun preferenceFiles() = File(targetContext.dataDir, "shared_prefs").listFiles().orEmpty()
        .filter { it.isFile && it.extension == "xml" }
        .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }

    override fun onStart() {
        val outcome = try {
            checkNotNull(admission) { "Appearance admission was not acquired" }
            runAppearanceChecks()
        } catch (error: Throwable) {
            Activity.RESULT_CANCELED to Bundle().apply {
                putString("stream", "FAIL Appearance setup: ${error.stackTraceToString()}")
            }
        }
        var code = outcome.first
        // runAppearanceChecks has restored appearance and finished its Activity.
        // Also reattach on setup failure, without clearing/draining foreign work.
        val reattachFailure = AtomicReference<Throwable?>()
        try { runOnMainSync {
            try { historyRecorder?.close() } catch (error: Throwable) { reattachFailure.set(error) }
        } }
        catch (error: Throwable) { reattachFailure.set(error) }
        val historyFailure = reattachFailure.get()
        if (historyFailure != null) {
            code = Activity.RESULT_CANCELED
            outcome.second.putString("stream", outcome.second.getString("stream", "") +
                "\nCLEANUP FAIL owned history recorder ${historyFailure.stackTraceToString()}")
        } else {
            outcome.second.putString("stream", outcome.second.getString("stream", "") + "\nCLEANUP PASS owned history recorder")
        }
        // All scoped restoration and all independent protected-data guards have
        // already run. Release ONLY our marker, even on pre-snapshot/setup failure.
        try {
            releaseAdmission()
            outcome.second.putString("stream", outcome.second.getString("stream", "") + "\nCLEANUP PASS owned measurement admission")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            outcome.second.putString("stream", outcome.second.getString("stream", "") +
                "\nCLEANUP FAIL owned measurement admission ${error.stackTraceToString()}")
        }
        finish(code, outcome.second)
    }

    private fun runAppearanceChecks(): Pair<Int, Bundle> {
        var code = Activity.RESULT_CANCELED
        val report = StringBuilder()
        val preferences = targetContext.getSharedPreferences("MainActivity", 0)
        val keys = UserThemeColors.keys + listOf("theme", HomeUiTheme.MODE_KEY, BottomNavigationStyle.KEY, BottomNavigationStyle.POSITION, RandomAppearance.KEY,
            PatternWallpaper.KEY, ConnectionAppearance.STYLE, ConnectionAppearance.EXIT,
            AppTypography.INTERFACE, AppTypography.DISPLAY, AppearanceMotion.MATH, AppearanceMotion.MATH_BODY)
        var original: Map<String, *>? = null
        var beforePreferenceFiles: Map<String, String>? = null
        var originalDark = Loft.dark
        var activity: MainActivity? = null
        val evidence = File(targetContext.cacheDir, "nav-appearance-${SystemClock.elapsedRealtime()}")
        fun main(block: () -> Unit) {
            val failure = AtomicReference<Throwable?>()
            runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw it }
            waitForIdleSync()
        }
        val before = checkNotNull(beforeEncrypted) { "Pre-startup encrypted snapshot is missing" }
        try {
            check(BuildConfig.DEBUG) { "Debug/common QA only" }
            check(Build.VERSION.SDK_INT >= 29) { "Appearance window inspection requires Android 10+" }
            check(evidence.mkdirs())
            report.append("renderer cases=").append(BottomNavigationUnselectedChecks.run(this)).append('\n')
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra("test_no_prompts", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = host
            fun ready(startup: Boolean = false) {
                val deadline = SystemClock.elapsedRealtime() + if (startup) 35_000 else 15_000
                var busy = true
                while (busy && SystemClock.elapsedRealtime() < deadline) {
                    main { busy = host.working() || startup && !MainProcessStartupIdle.isIdle() }
                    if (busy) SystemClock.sleep(50)
                }
                check(!busy) { "Appearance operation did not settle" }
            }
            ready(startup = true)
            // Normal startup/framework/cache writes are reported, not silently
            // excluded or called byte-identical. UI phase guards every XML byte.
            beforePreferenceFiles = preferenceFiles()
            report.append("STARTUP XML delta (separate from UI phase; non-empty is NOT byte-identical): ")
                .append(changedPaths(checkNotNull(beforeStartupXml), checkNotNull(beforePreferenceFiles), "shared_prefs/")).append('\n')
            main {
                original = preferences.all.toMap()
                originalDark = Loft.dark
                checkNotNull(historyRecorder).expectRestored(checkNotNull(original))
                check(preferences.edit().putBoolean(RandomAppearance.KEY, false).commit())
            }
            // Same registered runner and independent final guards. The helper
            // restores its palette and never replaces/skips the real NONE flow.
            report.append(ServerEditorLabelLayoutChecks.run(this, host)).append('\n')
            main { host.navigate(ClientSettingsNavigation.APPEARANCE) }
            fun root() = host.window.decorView
            lateinit var retained: BottomNavigationView
            main { retained = checkNotNull(root().findViewWithTag<BottomNavigationView>("main-navigation")) }

            fun capture(name: String) {
                // A second observation catches delayed blanking. Waiting does not
                // request a frame, repair geometry or rebuild the display list.
                for ((sample, delay) in listOf(250L, 1000L).withIndex()) {
                    SystemClock.sleep(delay)
                    val location = IntArray(2)
                    var w = 0; var h = 0; var muted = 0; var count = 0
                    var slotBounds = emptyList<Pair<Int, Int>>()
                    main {
                        val nav = checkNotNull(root().findViewWithTag<BottomNavigationView>("main-navigation"))
                        check(nav === retained) { "Navigation renderer was replaced" }
                        check(root().findViewWithTag<View>("screen-${ClientSettingsNavigation.APPEARANCE}") != null)
                        check(nav.alpha == 1f && nav.visibility == View.VISIBLE && nav.isAttachedToWindow)
                        count = nav.childCount
                        check(count > 0 && (0 until count).none { nav.getChildAt(it).isSelected })
                        val center = BottomNavigationView::class.java.getDeclaredField("centerX").apply { isAccessible = true }
                        check(center.getFloat(nav).isFinite()) { "Non-finite retained geometry" }
                        nav.getLocationOnScreen(location); w = nav.width; h = nav.height; muted = Loft.MUTED
                        slotBounds = (0 until count).map { i -> nav.getChildAt(i).let { it.left to it.right } }
                        check(h == host.dp(FloatingNotchMotion.BAR_HEIGHT)) { "Navigation dimensions changed" }
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot()) { "No compositor screenshot" }
                    try {
                        File(evidence, "$name-$sample.png").outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        check(location[0] >= 0 && location[1] >= 0 && location[0] + w <= screenshot.width &&
                            location[1] + h <= screenshot.height) { "Navigation outside screenshot" }
                        for (i in 0 until count) {
                            BottomNavigationGlyphInk.requireGlyph(screenshot,
                                location[0] + slotBounds[i].first, location[1],
                                location[0] + slotBounds[i].second, location[1] + h,
                                host.resources.displayMetrics.density, muted, "$name/$sample/slot-$i")
                        }
                    } finally { screenshot.recycle() }
                }
                report.append("PASS ").append(name).append(" style=")
                    .append(BottomNavigationStyle.resolve(preferences.getString(BottomNavigationStyle.KEY, null)))
                    .append(" retained/all-unchosen/natural-window-paint\n")
            }
            fun choose(tag: String, index: Int) {
                main {
                    val anchor = checkNotNull(root().findViewWithTag<View>(tag))
                    anchor.requestRectangleOnScreen(android.graphics.Rect(0, 0, anchor.width, anchor.height), true)
                    anchor.performClick()
                }
                main {
                    val option = WindowInspector.getGlobalWindowViews().firstNotNullOf {
                        it.findViewWithTag<View>("dropdown-option-$index")
                    }
                    option.performClick()
                }
                ready()
            }
            fun changeStyle(style: String) {
                val placement = preferences.getString(BottomNavigationStyle.POSITION, null)
                choose("bottom-navigation-style", BottomNavigationStyle.choicesFor(placement).keys.indexOf(style))
                main { check(BottomNavigationStyle.resolve(preferences.getString(BottomNavigationStyle.KEY, null), placement) == style) }
            }
            choose("section-navigation-position", BottomNavigationStyle.positions.keys.indexOf(BottomNavigationStyle.BOTTOM))
            for (dark in listOf(false, true)) {
                val mode = if (dark) HomeUiTheme.Mode.DARK else HomeUiTheme.Mode.LIGHT
                choose("appearance-theme", mode.ordinal)
                capture("theme-$dark-before-style")
                for (from in BottomNavigationStyle.choices.keys) {
                    changeStyle(from); capture("$dark-$from-before")
                    for (to in BottomNavigationStyle.choices.keys) {
                        changeStyle(from); changeStyle(to); capture("$dark-$from-to-$to")
                    }
                }
                repeat(3) { iteration ->
                    val beforeStyle = preferences.getString(BottomNavigationStyle.KEY, null)
                    main { checkNotNull(root().findViewWithTag<View>("random-appearance-now")).performClick() }
                    ready()
                    main { check(preferences.getString(BottomNavigationStyle.KEY, null) != beforeStyle) }
                    capture("$dark-random-$iteration")
                }
            }
            // The actual reproducer had random-every-time ON. Exercise the
            // same editor callbacks with that policy enabled via Appearance UI.
            main { checkNotNull(root().findViewWithTag<Switch>("random-appearance-on-open")).performClick() }
            main { check(preferences.getBoolean(RandomAppearance.KEY, false)) }
            for (style in BottomNavigationStyle.choices.keys) {
                changeStyle(style); capture("random-on-open-$style")
            }
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.append("FAIL ").append(error.stackTraceToString())
        } finally {
            // Each attempt is independent: even a broken dialog/window, palette
            // configuration or finish must not skip restoration or either guard.
            // Keep the original test failure above; append cleanup failures.
            fun cleanupStep(name: String, block: () -> Unit) {
                try {
                    block()
                    report.append("\nCLEANUP PASS ").append(name)
                } catch (error: Throwable) {
                    code = Activity.RESULT_CANCELED
                    report.append("\nCLEANUP FAIL ").append(name).append(' ')
                        .append(error.stackTraceToString())
                }
            }
            cleanupStep("restore appearance preferences") {
                main {
                    val baseline = original ?: return@main
                    // Do not overwrite unrelated user preferences or encrypted data.
                    val edit = preferences.edit()
                    keys.forEach { key ->
                        when (val value = baseline[key]) {
                            null -> edit.remove(key)
                            is String -> edit.putString(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            else -> error("Unexpected appearance preference type")
                        }
                    }
                    check(edit.commit())
                }
            }
            cleanupStep("restore palette") {
                main {
                    UserThemeColorsUi.configure(targetContext); Loft.apply(originalDark)
                }
            }
            cleanupStep("finish activity") { main { activity?.finish() } }
            cleanupStep("preferences equality guard") {
                check(preferences.all == checkNotNull(original)) { "Preferences differ after scoped appearance restoration" }
            }
            cleanupStep("encrypted stores equality guard") {
                val after = encryptedStores()
                check(after == before) { "Encrypted stores changed; no rollback performed; ${changedPaths(before, after, "files/")}" }
            }
            cleanupStep("all preference XML equality guard") {
                val baselinePreferenceFiles = checkNotNull(beforePreferenceFiles) { "UI phase XML snapshot was not reached" }
                val afterPreferenceFiles = preferenceFiles()
                check(afterPreferenceFiles == baselinePreferenceFiles) {
                    "Preference XML bytes changed; external fresh-baseline recovery required; " +
                        changedPaths(baselinePreferenceFiles, afterPreferenceFiles, "shared_prefs/")
                }
            }
        }
        return code to Bundle().apply {
            putString("stream", report.toString()); putString("evidence", evidence.absolutePath)
        }
    }
}
