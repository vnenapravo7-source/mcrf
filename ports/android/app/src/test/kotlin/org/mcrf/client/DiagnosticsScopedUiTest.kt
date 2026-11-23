package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsScopedUiTest {
    private fun projectFile(path:String)=listOf(File(path.removePrefix("app/")),File(path)).first {it.isFile}
    private fun source(name:String)=projectFile("app/src/main/kotlin/org/mcrf/client/$name.kt").readText()
    @Test fun bsAddressIsVisibleWithoutImportCredentials() {
        val entry=ServerImport.Server("private-import","БС","csqtt","192.0.2.8",443,null,null,null,org.json.JSONObject())
        val label=DiagnosticsServerLabel.display(entry)
        assertTrue(label.contains("БС"));assertTrue(label.contains("192.0.2.8:443"));assertFalse(label.contains("private-import"))
        val ipv6=ServerImport.Server("other","БС","csqtt","2001:db8::8",443,null,null,null,org.json.JSONObject())
        assertTrue(DiagnosticsServerLabel.display(ipv6).contains("[2001:db8::8]:443"))
    }
    @Test fun brandVectorsExistOfflineAndFallbackIsNeverBlank() {
        val icons=source("ServiceBrandIcons")
        for(id in listOf("whatsapp","discord")) {
            assertTrue(icons.contains("\"$id\" to R.drawable.service_brand_$id"))
            val vector=projectFile("app/src/main/res/drawable/service_brand_$id.xml").readText()
            assertTrue(vector.contains("android:pathData="))
        }
        for(service in ServiceTestCatalog.services)assertTrue(ServiceTestCatalog.iconLabel(service).isNotBlank())
        assertTrue(icons.contains("ifBlank"));assertFalse(source("ServiceTestsUi").contains("getApplicationIcon"))
    }
    @Test fun primaryFlowHasNoResultsTabAndActionsRemainOutsideWeightedReport() {
        val tabs=source("ConnectionDiagnosticsUi")
        assertFalse(tabs.contains("\"Результаты\""));assertTrue(tabs.contains("LinearLayout.LayoutParams(-1, 0, 1f)"))
        val screen=source("ServiceDiagnosticsUi")
        assertTrue(screen.contains("Сервисы ·"));assertTrue(screen.contains("Серверы ·"))
        assertFalse(screen.contains("1. Сервисы"));assertFalse(screen.contains("2. Серверы"))
        assertTrue(source("MainActivity").contains("ClientSettingsNavigation.DIAGNOSTICS -> ConnectionDiagnosticsUi.build(this,page)"))
        assertFalse(tabs.contains("Dialog("))
        assertTrue(screen.contains("\"Проверить все\""));assertTrue(screen.contains("selectionCollapsed=true"))
        assertTrue(screen.contains("it.addView(actionRow, it.indexOfChild(selections)"))
        assertTrue(screen.indexOf("it.addView(actionRow")<screen.indexOf("root.addView(scroller"))
        assertTrue(screen.contains("View.SCROLLBARS_OUTSIDE_OVERLAY"))
        assertFalse(screen.contains("root.addView(LinearLayout(this).apply"))
        assertTrue(screen.contains("DiagnosticsServerLabel.display(server)"))
    }
}
