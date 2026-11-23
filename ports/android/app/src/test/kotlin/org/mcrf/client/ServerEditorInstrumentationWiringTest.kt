package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only. Real layout checks execute in the common Android runner later. */
class ServerEditorInstrumentationWiringTest {
    private fun source(relative: String) = listOf(File("src/$relative"), File("app/src/$relative"))
        .first { it.isFile }.readText()
    @Test fun appearanceAdmissionPrecedesApplicationAndReleasesOnlyOwnedMarkerAfterGuards() {
        val runner = source("androidTest/kotlin/org/mcrf/client/BottomNavigationAppearanceInstrumentation.kt")
        val create = runner.substringAfter("override fun onCreate(arguments: Bundle?)")
            .substringBefore("private fun releaseAdmission()")
        assertTrue(create.indexOf("MainProcessMeasurementLease.acquire(targetContext, \"fixture-appearance\")") in
            0 until create.indexOf("            start()"))
        val shared = source("androidTest/kotlin/org/mcrf/client/MainProcessMeasurementLease.kt")
        assertTrue(shared.contains("Application.getProcessName() == \"org.mcrf.client\""))
        assertTrue(shared.contains("check(BuildConfig.DEBUG)"))
        assertTrue(shared.contains("context.packageName == \"org.mcrf.client\""))
        assertTrue(shared.contains("getDeclaredField(\"startLock\")"))
        assertTrue(shared.contains("synchronized(startLock)"))
        val acquire = shared.substringAfter("fun acquire(")
        assertTrue(acquire.indexOf("check(measurements.get(coordinator) == null)") in
            0 until acquire.indexOf("measurements.set(coordinator, owned)"))
        val release = shared.substringAfter("override fun close()").substringBefore("companion object")
        assertTrue(release.indexOf("check(measurements.get(coordinator) === owned)") in
            0 until release.indexOf("measurements.set(coordinator, null)"))
        assertTrue(release.indexOf("if (released) return") in 0 until release.indexOf("measurements.set(coordinator, null)"))
        assertTrue(release.indexOf("measurements.set(coordinator, null)") in 0 until release.indexOf("released = true"))
        val start = runner.substringAfter("override fun onStart()")
            .substringBefore("private fun runAppearanceChecks()")
        assertTrue(start.indexOf("runAppearanceChecks()") in 0 until start.indexOf("releaseAdmission()"))
        assertTrue(start.indexOf("releaseAdmission()") in 0 until start.indexOf("finish(code, outcome.second)"))
        assertTrue(runner.contains("try { releaseAdmission() } catch (cleanup: Throwable)"))
        assertFalse(create.contains("getSharedPreferences("))
        assertFalse(create.contains("cancelAll("))
        assertFalse(create.contains("ProbeCoordinator.cancel("))
    }
    @Test fun editorHelperRunsBeforeFullNoneFlowWithIndependentFinalGuards() {
        val runner = source("androidTest/kotlin/org/mcrf/client/BottomNavigationAppearanceInstrumentation.kt")
        val helper = runner.indexOf("ServerEditorLabelLayoutChecks.run(this, host)")
        assertTrue(helper >= 0)
        assertTrue(helper < runner.indexOf("host.navigate(ClientSettingsNavigation.APPEARANCE)"))
        assertTrue(runner.contains("none { nav.getChildAt(it).isSelected }"))
        assertTrue(runner.contains("check(center.getFloat(nav).isFinite())"))
        assertTrue(runner.contains("cleanupStep(\"preferences equality guard\")"))
        assertTrue(runner.contains("cleanupStep(\"encrypted stores equality guard\")"))
        assertTrue(runner.contains("cleanupStep(\"all preference XML equality guard\")"))
        assertTrue(runner.contains("check(afterPreferenceFiles == baselinePreferenceFiles)"))
        assertTrue(runner.contains("check(after == before)"))
        assertTrue(runner.contains("changedPaths(before, after, \"files/\")"))
        assertTrue(runner.contains("changedPaths(baselinePreferenceFiles, afterPreferenceFiles, \"shared_prefs/\")"))
        val diagnostics = runner.substringAfter("private fun changedPaths(").substringBefore("private fun encryptedStores()")
        assertTrue(diagnostics.contains("after.keys - before.keys"))
        assertTrue(diagnostics.contains("before.keys - after.keys"))
        assertTrue(diagnostics.contains("before[it] != after[it]"))
        assertFalse(diagnostics.contains("readBytes("))
        assertTrue(source("androidTest/AndroidManifest.xml").contains("org.mcrf.client.BottomNavigationAppearanceInstrumentation"))
    }

    @Test fun onlyIdleOwnedHistoryListenerIsDetachedAndXmlPhasesRemainExplicit() {
        val lease = source("androidTest/kotlin/org/mcrf/client/AppearanceHistoryRecorderLease.kt")
        assertTrue(lease.contains("ChangeHistoryStore.local(context)"))
        assertTrue(lease.contains("registry[path] === owned"))
        assertTrue(lease.contains("pending.get(owned) == null && !running.getBoolean(owned)"))
        assertTrue(lease.contains("preferences.unregisterOnSharedPreferenceChangeListener(owned as"))
        assertTrue(lease.contains("preferences.registerOnSharedPreferenceChangeListener(owned as"))
        val close = lease.substringAfter("override fun close()")
        assertTrue(close.indexOf("check(current == expected)") in 0 until close.indexOf("previous.set(owned, current)"))
        assertTrue(close.substringAfter("finally {").contains("preferences.registerOnSharedPreferenceChangeListener(owned as"))
        for (forbidden in listOf("pending.set(", "running.set(", "worker.shutdown", "registry.remove(", "withoutHistory", ".delete(", "awaitTermination"))
            assertFalse(forbidden, lease.contains(forbidden))
        val runner = source("androidTest/kotlin/org/mcrf/client/BottomNavigationAppearanceInstrumentation.kt")
        val create = runner.substringAfter("override fun onCreate(arguments: Bundle?)").substringBefore("private fun releaseAdmission()")
        assertTrue(create.indexOf("beforeEncrypted = encryptedStores()") in 0 until
            create.indexOf("historyRecorder = AppearanceHistoryRecorderLease.acquire(targetContext)"))
        assertTrue(create.indexOf("historyRecorder = AppearanceHistoryRecorderLease.acquire(targetContext)") in
            0 until create.indexOf("            start()"))
        val ui = runner.substringAfter("private fun runAppearanceChecks()")
        assertTrue(ui.indexOf("            ready(startup = true)") in 0 until ui.indexOf("beforePreferenceFiles = preferenceFiles()"))
        assertTrue(ui.indexOf("beforePreferenceFiles = preferenceFiles()") in 0 until
            ui.indexOf("preferences.edit().putBoolean(RandomAppearance.KEY, false)"))
        assertTrue(ui.contains("STARTUP XML delta (separate from UI phase; non-empty is NOT byte-identical)"))
        assertTrue(ui.contains("checkNotNull(historyRecorder).expectRestored(checkNotNull(original))"))
        assertTrue(runner.contains("it.isFile && it.extension == \"enc\""))
        assertFalse(runner.contains("change-history.enc")) // No history exclusion from encrypted inventory.
    }

    @Test fun clipboardComponentIsRegisteredOnceInDefaultMainProcess() {
        val manifest = source("androidTest/AndroidManifest.xml")
        val entries = Regex("""<instrumentation\b[^>]*android:name="org\.mcrf\.client\.CsqttOriginalClipboardInstrumentation"[^>]*/>""")
            .findAll(manifest).map { it.value }.toList()
        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("android:targetPackage=\"org.mcrf.client\""))
        assertFalse(entries.single().contains("android:targetProcesses="))
        assertTrue(manifest.contains("org.mcrf.client.BottomNavigationAppearanceInstrumentation"))
    }

    @Test fun bothMainProcessUiRunnersObserveIndependentStartupWorkersReadOnly() {
        val idle = source("androidTest/kotlin/org/mcrf/client/MainProcessStartupIdle.kt")
        assertTrue(idle.contains("EngineStartupUpdates::class.java.getDeclaredField(\"checking\")"))
        assertTrue(idle.contains("EngineUpdates::class.java.getDeclaredField(\"busy\")"))
        assertTrue(idle.contains("as AtomicBoolean"))
        assertTrue(idle.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue(idle.contains("!startupChecking.getBoolean(EngineStartupUpdates) && !engineBusy.get()"))
        for (forbidden in listOf(".set(", "compareAndSet(", "getSharedPreferences(", "JobScheduler", ".cancel(", "Thread.sleep("))
            assertFalse(forbidden, idle.contains(forbidden))
        val appearance = source("androidTest/kotlin/org/mcrf/client/BottomNavigationAppearanceInstrumentation.kt")
        val clipboard = source("androidTest/kotlin/org/mcrf/client/CsqttOriginalClipboardInstrumentation.kt")
        assertTrue(appearance.contains("startup && !MainProcessStartupIdle.isIdle()"))
        assertTrue(clipboard.contains("MainProcessStartupIdle.isIdle()"))
        assertFalse(appearance.contains("getDeclaredField(\"checking\")"))
        assertFalse(clipboard.contains("getDeclaredField(\"checking\")"))
    }
}
