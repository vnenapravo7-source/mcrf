package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source ownership checks complement the pending narrow-screen/device acceptance. */
class UiPolish3ContractTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun compactDialogsKeepFootersOutsideTheirBoundedScroll() {
        assertTrue(source("ThemedDialogBuilder").contains("maxHeightFraction:Float=.85f"))
        assertTrue(source("ThemedDialogBuilder").contains("gravity=android.view.Gravity.START"))
        for(name in listOf("TemporaryRulesUi","FallbackChainUi","RouteServerPicker","AdaptivePolicyUi","ServerShareUi"))
            assertTrue(name,source(name).contains("CompactUi.scroll"))
    }
    @Test fun routeDnsAndBadgesAreVisibleWithoutOpeningAdvanced() {
        val route=source("RouteRuleUi")
        assertFalse(route.contains("route-editor-advanced"))
        assertTrue(route.contains("route-editor-dns"))
        assertTrue(route.contains("CompactUi.badge"));assertTrue(route.contains("CompactUi.updateBadge"))
        assertTrue(source("CompactUi").contains("Gravity.TOP or Gravity.END"))
        assertEquals("11,3 тыс.",CompactUi.count(11343));assertEquals("0",CompactUi.count(0))
    }
    @Test fun historyCardsShowAllChangesWithSideBySideActions() {
        val history=source("LogsPage")
        assertTrue(history.contains("CompactUi.historyBackground(this@with,index,radiusDp=20)"))
        assertFalse(history.contains("button(\"Подробнее\""))
        assertTrue(history.contains("historyGroups(summary.changes)"))
        assertTrue(history.contains("items.joinToString"))
        assertTrue(history.contains("actions.addView(restore,LinearLayout.LayoutParams(0,-2,1f))"))
    }
    @Test fun diagnosticTabsOwnAndCancelTheirWorkAndCategoriesFailLocally() {
        val hub=source("ConnectionDiagnosticsUi")
        val style=source("DiagnosticsUiStyle")
        for(label in listOf("Один сервер","Мульти-проверка"))assertTrue(style.contains(label))
        assertFalse(hub.contains("tab(\"Результаты\""))
        assertTrue(hub.contains("pages.getOrPut(index)"))
        assertTrue(hub.contains("onViewDetachedFromWindow")&&hub.contains("owners.forEach"))
        assertFalse(hub.contains("Dialog("))
        val tests=source("ServiceTestsUi")
        assertTrue(tests.contains("SettingsSectionUi.read({renderListContent()})"))
        assertFalse(tests.contains(".first {"))
        assertTrue(source("ServiceBrandIcons").contains("SettingsSectionUi.read"))
        assertEquals("fallback",SettingsSectionUi.read<String>({throw IllegalStateException("category")},{"fallback"}))
        assertEquals("fallback",SettingsSectionUi.read<String>({throw NoClassDefFoundError("icon")},{"fallback"}))
    }
    @Test fun compactModeSwitchDoesNotBaselineShiftOrWrapItsLabels() {
        val mode=source("ModeSwitch")
        assertTrue(mode.contains("isBaselineAligned = false"))
        assertFalse(mode.contains("setSingleLine(true)"))
        val tolerance=source("AutoToleranceUi")
        assertTrue(tolerance.contains("Сбои"))
        assertTrue(tolerance.contains("при отказах или росте задержки"))
    }
    @Test fun networkBehaviorMovesToRouteScheduleAndQuotaAppendsLimits() {
        val main=source("MainActivity")
        assertFalse(main.contains("link(ClientSettingsNavigation.CONDITIONS"))
        assertFalse(main.substringAfter("private fun betaSettings()").contains("beta-network-behavior"))
        assertTrue(main.contains("ClientSettingsNavigation.SCHEDULE -> schedule()"))
        val quota=source("AdaptivePolicyUi")
        assertTrue(quota.contains("ledger.configure(ledger.rules()+rule)"))
        assertTrue(quota.contains("Лимиты · ${'$'}{rules.size}/32"))
        assertFalse(quota.contains("Новый лимит"))
        assertTrue(source("ConnectionAppearanceUi").contains("val label=\"MCRF\\n\""))
    }
}
