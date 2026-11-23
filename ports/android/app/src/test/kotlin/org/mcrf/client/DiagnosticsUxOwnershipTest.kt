package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Lifecycle wiring regressions checked without an Android runtime. */
class DiagnosticsUxOwnershipTest {
    private fun projectFile(path:String)=listOf(File(path),File("../$path"))
        .firstOrNull(File::isFile)?:error("Project file not found: $path")
    private fun source(name:String)=projectFile("app/src/main/kotlin/org/mcrf/client/$name.kt").readText()

    @Test fun tabsRetainViewsAndOwnersUntilPageDeparture() {
        val source=source("ConnectionDiagnosticsUi")
        val selection=source.substringAfter("fun select(index: Int)").substringBefore("body.addView(DiagnosticsUiStyle.selector")
        assertTrue(selection.contains("pages.getOrPut(index)"))
        assertTrue(selection.contains("if (page.childCount > 0) return"))
        assertFalse(selection.contains(".close()"))
        assertFalse(selection.contains("dialog.dismiss();"))
        assertFalse(selection.contains("host.removeAllViews()"))
        assertTrue(source.contains("onViewDetachedFromWindow"))
        assertTrue(source.contains("it.close()"))
        assertFalse(source.contains("Dialog("))
        assertTrue(source.contains("activity.navigate(ClientSettingsNavigation.DIAGNOSTICS)"))
        assertFalse(source.contains("\"Результаты\""))
    }

    @Test fun removedDegradationFeatureIsNotRestoredOrExported() {
        val main=source("MainActivity")
        assertFalse(main.contains("DiagnosticsDegradationMonitor.restore"))
        assertFalse(main.contains("Ухудшение соединения · Beta"))
        assertFalse(projectFile("app/src/main/AndroidManifest.xml").readText().contains("DegradationDismissReceiver"))
    }

    @Test fun profileEditorKeepsSharedFoldInteraction() {
        assertTrue(source("NetworkProfileConfigurationUi").contains("return EditorLayout.profileFold(activity,parent,title,sectionIndex++)"))
        assertTrue(source("EditorLayout").contains("val section=CompactUi.fold(activity,parent,title)"))
        assertFalse(source("ConditionProfilesUi").contains("heading.setOnClickListener"))
    }
}
