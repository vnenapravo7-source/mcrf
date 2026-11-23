package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Navigation/lifecycle contracts; dimensions and rendered layout still need device review. */
class RoutesScopeUiContractTest {
    private val main=listOf(File("src/main"),File("app/src/main")).first {it.isDirectory}
    private fun source(name:String)=File(main,"kotlin/org/mcrf/client/$name.kt").readText()
    @Test fun temporaryRulesAreReachableFromTheExpertToolbarWithoutPermanentRules() {
        val page=source("RoutingPage")
        val heading=page.substringAfter("tag=\"rules-heading\"").substringBefore("if (rules.length() == 0)")
        assertTrue(heading.indexOf("iconButton(\"add\"")<heading.indexOf("routing-temporary-rules"))
        assertTrue(heading.contains("TemporaryRulesUi.show"))
        assertFalse(page.contains("RuleSandbox"));assertFalse(page.contains("Beta  ▾"))
        val temporary=source("TemporaryRulesUi")
        assertTrue(temporary.contains("setPositiveButton(\"Создать\""))
        assertFalse(temporary.contains("getJSONArray(\"rules\").length()==0"))
    }
    @Test fun pickerOffersExplicitPriorityAndHidesIrrelevantSequenceSettings() {
        val picker=source("RouteServerPicker")
        assertTrue(picker.contains("policy==BsAutoSelectionPolicy.RECENT"))
        assertTrue(picker.contains("tag=\"route-auto-strategy\";textSize=13f;maxLines=1"))
        assertTrue(picker.contains("cadence.visibility=if(sequential)View.GONE"))
        assertTrue(picker.contains("difference.visibility=if(sequential)View.GONE"))
        assertTrue(source("OrderedServerChoices").contains("draft.move(entry.id,delta)"))
    }
    @Test fun retiredComponentsHaveNoExecutableOrManifestReferences() {
        for(file in File(main,"kotlin").walkTopDown().filter {it.isFile&&it.extension=="kt"}) {
            val content=file.readText()
            assertFalse(file.name,content.contains("RuleSandbox"))
            assertFalse(file.name,content.contains("RoutingSandboxPreparation"))
        }
        val manifest=File(main,"AndroidManifest.xml").readText()
        assertFalse(manifest.contains("RuleSandbox"));assertFalse(manifest.contains("rule-sandbox-startup"))
        assertTrue(manifest.contains("TemporaryRulesReceiver"));assertTrue(manifest.contains("TemporaryRulesStartupProvider"))
    }
}
