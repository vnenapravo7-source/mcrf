package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServiceDiagnosticsUiBatchTest {
    @Test fun serviceCategoriesStartCollapsedAndSelectionControlsHaveSeparateGaps() {
        val source = listOf(java.io.File("src/main/kotlin/org/mcrf/client/ServiceDiagnosticsUi.kt"),
            java.io.File("app/src/main/kotlin/org/mcrf/client/ServiceDiagnosticsUi.kt")).first { it.isFile }.readText()
        assertTrue(source.contains("private val expandedCategories = mutableSetOf<String>()"))
        assertTrue(source.contains("addView(search, space(16, 12))"))
        assertTrue(source.contains("it.indexOfChild(selections), space(0, 20)"))
        assertTrue(source.contains("query.isNotBlank() || category.id in expandedCategories"))
    }
    @Test fun everyProbeIncludingDiscordKeepsItsServiceAndOfflineIdentity() {
        for(target in ServiceTestCatalog.targets){
            val service=requireNotNull(ServiceTestCatalog.serviceFor(target.id))
            assertTrue(service.parts.any {it.id==target.id})
            assertTrue(ServiceTestCatalog.iconLabel(service).isNotBlank())
        }
        assertEquals("discord",ServiceTestCatalog.serviceFor("discord-text")?.id)
        assertNull(ServiceTestCatalog.serviceFor("not-a-probe"))
        assertEquals(ServiceTestCatalog.categories.sumOf {it.services.size},ServiceTestCatalog.services.size)
    }
    @Test fun selectAllDoesNotLoseTargetsWhenSearchingOrDeselectingOneService() {
        val selection=ServiceTestsSelection();selection.selectAll()
        assertEquals(ServiceTestCatalog.targets.map {it.id}.toSet(),selection.selected)
        ServiceTestCatalog.matching("Discord")
        assertEquals(ServiceTestCatalog.targets.size,selection.selected.size)
        val discord=ServiceTestCatalog.services.single {it.id=="discord"}
        selection.toggle(discord.parts)
        assertEquals(ServiceTestCatalog.targets.size-discord.parts.size,selection.selected.size)
        assertTrue(discord.parts.none {it.id in selection.selected})
    }
    @Test fun degradationNoticeDescribesFailureAndRecognizesOnlyItsExplicitActions() {
        val failure=DegradationRecommendation("connection",100,null,3,true,false)
        assertTrue(DegradationNotice.reason(failure).contains("не отвечает"))
        assertTrue(DegradationNotice.reason(failure.copy(observedMs=500)).contains("100 → 500 мс"))
        assertTrue(DegradationNotice.isAction(DiagnosticsDegradationMonitor.CHOOSE_SERVER))
        assertTrue(DegradationNotice.isAction(DiagnosticsDegradationMonitor.LATER))
        assertFalse(DegradationNotice.isAction(null));assertFalse(DegradationNotice.isAction("other"))
    }
}
