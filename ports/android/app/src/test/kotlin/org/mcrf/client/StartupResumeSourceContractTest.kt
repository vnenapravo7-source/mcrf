package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards only; these do not claim timing, compilation or Android acceptance. */
class StartupResumeSourceContractTest {
    private fun source(name: String): String = listOf(
        File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")
    ).first { it.isFile }.readText()

    @Test fun resumePollingDoesNotSynchronouslyReloadTheCatalogue() {
        val activity = source("MainActivity")
        val refresh = activity.substringAfter("private val refresh = object : Runnable").substringBefore("override fun onCreate")
        assertFalse(refresh.contains("catalogue.refreshFromStorage()"))
        val worker = refresh.indexOf("worker.execute {")
        val read = refresh.indexOf("catalogue.refreshPresentationFromStorage()")
        val callback = refresh.indexOf("ui {", worker)
        assertTrue(worker >= 0 && read > worker && callback > read)
        val resume = activity.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue(resume.contains("catalogueRefreshRequested=true"))
        val remove = resume.indexOf("handler.removeCallbacks(refresh)")
        assertTrue(remove >= 0 && remove < resume.indexOf("handler.post(refresh)"))
    }

    @Test fun optionalStartupJobsCannotDelayLoadedUiPublication() {
        val create = source("MainActivity").substringAfter("override fun onCreate").substringBefore("private fun deliverCatalogueRefresh")
        val publish = create.indexOf("ui { loading = false;")
        assertTrue(publish >= 0)
        listOf("RemoteRoutingSchedule.cancelRetired", "SubscriptionUpdates.initialize", "SubscriptionOpenRefresh.onOpen", "EngineUpdateSchedule.schedule").forEach {
            assertTrue(it, create.indexOf(it) > publish)
        }
        assertFalse(create.contains("routing.snapshot()"))
    }

    @Test fun noApprovedPolicyReturnsBeforeEnteringEncryptedStorage() {
        val active = source("ProfilePolicyRuntime").substringAfter("fun active(context:Context)").substringBefore("fun requireOrdinaryEditsAllowed")
        val exit = active.indexOf("if(lease.tokens.isEmpty())return null")
        assertTrue(exit >= 0)
        assertTrue(exit < active.indexOf("ProfileStore(context"))
        assertTrue(exit < active.indexOf("store.revision()"))
    }

    @Test fun startupStageSpansPreserveLoadOrderAndCatalogueReadyMilestone() {
        val create = source("MainActivity").substringAfter("override fun onCreate").substringBefore("private fun deliverCatalogueRefresh")
        val stages = listOf("history_observer", "catalogue_load", "recover_warp", "routing_load", "profile_read", "local_proxy_settings", "ui_render")
        val positions = stages.map { stage -> create.indexOf("startupStage(\"$stage\")") }
        assertTrue(positions.all { it >= 0 })
        assertTrue(positions.zipWithNext().all { (before, after) -> before < after })
        val ready = create.indexOf("startupTiming(\"catalogue_ready\", startupQueuedNs)")
        assertTrue(ready > positions[1] && ready < positions[2])
        val queue = create.indexOf("startupTiming(\"ui_queue_wait\", startupUiQueuedNs)")
        assertTrue(queue > positions[5] && queue < positions[6])
        assertTrue(create.contains("val startupUiQueuedNs = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L"))
        assertTrue(create.indexOf("startupTiming(\"load_queue\", startupQueuedNs)") < positions.first())
        assertTrue(create.indexOf("startupFirstDraw(startupQueuedNs)") > positions.last())
        assertTrue(create.contains("runCatching {ChangeHistoryStore.local(applicationContext)}"))
        assertTrue(create.contains("if (restoreProblem) throw"))
    }

    @Test fun debugOnlyReadyTimingUsesActualDrawThenPostAndContainsNoSavedValues() {
        val activity = source("MainActivity")
        val timing = activity.substringAfter("private fun startupTiming(").substringBefore("private inline fun")
        assertTrue(timing.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(timing.contains("SystemClock.elapsedRealtimeNanos()"))
        assertTrue(timing.contains("android.util.Log.d(\"MCRF_STARTUP\", \"\$stage=\${elapsedMs}ms\")"))
        for (sensitive in listOf("selected", "prefs", "savedConfig", "catalogue", "Exception")) assertFalse(timing.contains(sensitive))
        val draw = activity.substringAfter("private fun startupFirstDraw(").substringBefore("override fun onCreate")
        assertTrue(draw.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(draw.contains("tree.addOnPreDrawListener("))
        assertTrue(draw.contains("tree.removeOnPreDrawListener(this)"))
        assertTrue(draw.contains("startupTiming(\"ui_first_predraw\", renderedNs)"))
        assertTrue(draw.contains("tree.addOnDrawListener("))
        val drawn = draw.indexOf("override fun onDraw()")
        val posted = draw.indexOf("shell.post {", drawn)
        val ready = draw.indexOf("startupTiming(\"ui_first_draw_ready\", startedNs)")
        assertTrue(drawn >= 0 && posted > drawn && ready > posted)
        assertTrue(draw.contains("tree.removeOnDrawListener(this)"))
        assertTrue(draw.contains("if (posted) return"))
        assertTrue(draw.contains("startupTiming(\"process_first_draw_ready\", android.os.Process.getStartElapsedRealtime() * 1_000_000L)"))
    }
}
