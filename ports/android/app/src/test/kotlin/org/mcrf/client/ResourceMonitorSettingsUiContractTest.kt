package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Authored for the common P0 batch. Source wiring is not visual, lifecycle or device acceptance. */
class ResourceMonitorSettingsUiContractTest {
    private fun source(name: String = "ResourceMonitorSettingsUi") = listOf(
        File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")
    ).first { it.isFile }.readText()

    @Test fun onlyMiniRemainsAndLegacySizeIsNeitherReadNorRewritten() {
        assertEquals(listOf("Миниатюрный"), ResourceMonitorSize.entries.map { it.title })
        for (name in listOf("ResourceMonitorSettingsUi", "ResourceMonitorController", "ResourceMonitorOverlayService")) {
            val code = source(name)
            for (removed in listOf("getSharedPreferences(", ".putString(\"size\"", "resource-monitor-size", "fun setSize(", "ResourceMonitorSize.LARGE", "ResourceMonitorSize.NORMAL"))
                assertFalse("$name: $removed", code.contains(removed))
        }
        assertFalse(source().contains("activity.picker("))
    }

    @Test fun metricsAndRecordingAreInlineWithActionsOutsideScrollAndNoDetailsSubwindow() {
        val code = source()
        assertTrue(code.contains("body.addView(metrics,"))
        assertTrue(code.contains("addView(CompactUi.scroll(activity, body, .42f)"))
        assertTrue(code.contains("addView(metrics.actions,"))
        assertFalse(code.contains("body.addView(metrics.actions"))
        assertFalse(code.contains("Показатели"))
        assertFalse(code.contains("setNeutralButton("))
        assertFalse(code.contains("monitor.showDetails()"))
        val controller = source("ResourceMonitorController")
        assertFalse(controller.contains("ThemedDialogBuilder("))
        assertTrue(controller.contains("fun showDetails() = showSettings()"))
        assertTrue(code.contains("monitor.bindMetrics(metrics)"))
        assertTrue(code.contains("monitor.bindMetrics(null)"))
    }

    @Test fun infoIsNativeAndBesideWrapContentHeadingRatherThanWeightedFarEdge() {
        val title = source().substringAfter("val title = object").substringBefore("val dialog =")
        assertTrue(title.contains("ResourceMonitorUi.icon(activity, true"))
        assertTrue(title.contains("LinearLayout.LayoutParams(-2, -2)"))
        assertFalse(title.contains("1f"))
        val controls = source("ResourceMonitorMetricsView")
        assertTrue(controls.contains("ImageButton(context)"))
        assertTrue(controls.contains("selectableItemBackgroundBorderless"))
        assertTrue(source().contains("resource-monitor-indicator-row"));assertTrue(source().contains("ToggleButton(activity)"))
    }

    @Test fun permissionVisibilityRefreshCannotAutomaticallyStartMonitorOnReturn() {
        val code = source()
        val refresh = code.substringAfter("fun refresh() {").substringBefore("fun permission()")
        assertTrue(refresh.contains("permissionButton.visibility = if (permitted) View.GONE else View.VISIBLE"))
        assertFalse(refresh.contains("ResourceMonitorOverlayService.start("))
        assertFalse(refresh.contains("monitor.setEnabled("))
        assertTrue(code.contains("OnWindowFocusChangeListener { focused -> if (focused) refresh() }"))
        assertTrue(code.contains("removeOnWindowFocusChangeListener(focusListener)"))
        assertTrue(code.contains("ResourceMonitorOverlayService.unobserve(observer)"))
    }
}
