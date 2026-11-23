package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring checks; these do not claim Android rendering acceptance. */
class UiNavigationRoutesTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()

    @Test fun sharedCompactBadgesAndDialogBoundsRemainAvailable() {
        assertEquals("11,3 тыс.",CompactUi.count(11343))
        assertEquals("0",CompactUi.count(0))
        assertTrue(source("ThemedDialogBuilder").contains("maxHeightFraction:Float=.85f"))
        assertTrue(source("RouteServerPicker").contains("CompactUi.scroll"))
    }

    @Test fun settingsExposeTheNewParentsAndKeepTheVkTurnEntry() {
        val main=source("MainActivity")
        val root=main.substringAfter("private fun settings()").substringBefore("private fun settingsSection")
        assertFalse(root.contains("link(ClientSettingsNavigation.VK,"))
        assertFalse(root.contains("link(ClientSettingsNavigation.GEO,"))
        assertTrue(main.contains("vkSettings={navigate(ClientSettingsNavigation.VK)}"))
        val tun=source("ClientTunUi")
        assertTrue(tun.contains("activity.navigate(ClientSettingsNavigation.DNS)"))
        // Sites are index 0; DNS is inserted at 1, immediately before kill switch.
        assertTrue(tun.contains("}.apply {tag=\"vpn-dns-settings\"},1,"))
        val advanced=main.substringAfter("ClientSettingsNavigation.ADVANCED -> {").substringBefore("ClientSettingsNavigation.DNS ->")
        assertTrue(advanced.contains("GeoDataEditor(this@MainActivity,routing).build()"))
        assertTrue(advanced.contains("GeoDataEditor(this@MainActivity,routing).advancedView()"))
    }

    @Test fun serverChoiceIsCenteredCancellableAndHasNoObsoleteFastestControl() {
        val picker=source("RouteServerPicker")
        assertTrue(picker.contains("RouteChoicePopup.centered(this,view,panel)"))
        assertFalse(picker.contains("ThemedDialogBuilder"))
        assertFalse(picker.contains("Выбор сервера"))
        assertFalse(picker.contains("Менять только при недоступности"))
        assertTrue(picker.contains("text=\"Автовыбор\""))
        assertTrue(picker.contains("button(\"Отмена\",false)"))
        assertTrue(picker.indexOf("addView(toggle,")<picker.indexOf("addView(strategy,"))
        assertTrue(picker.contains("onViewDetachedFromWindow(v:View){choicesPopup?.dismiss()}"))
    }

    @Test fun editorFoldsShareRevealStateAndTitlesAlignAtStart() {
        val editor=source("ServerEditorLayout")
        assertTrue(editor.contains("CompactUi.fold(activity,parent,title)"))
        assertTrue(editor.contains("CompactUi.expandFold(node)"))
        assertFalse(editor.contains("▾"))
        val dialog=source("ThemedDialogBuilder")
        assertTrue(dialog.contains("override fun setCustomTitle"))
        assertTrue(dialog.contains("textAlignment=View.TEXT_ALIGNMENT_VIEW_START"))
        assertTrue(dialog.contains("title_template"))
    }

    @Test fun selectionDecoratesTheRowAndDnsAndRouteActionsHaveNoOutlines() {
        val row=source("OrderedServerChoices")
        assertTrue(row.contains("background=CompactUi.choiceBackground"))
        assertTrue(row.contains("row.isSelected=on"))
        assertTrue(row.contains("setOnLongClickListener {choice.toggle();true}"))
        assertTrue(row.contains("background=null;isClickable=false"))
        for(name in listOf("RoutingPage","DnsEditor","AdvancedDnsEditor"))
            assertFalse(name,source(name).contains("CompactUi.outline"))
        assertTrue(source("AdvancedDnsEditor").contains("CompactUi.fieldSurface"))
        assertTrue(source("ThemedDialogBuilder").contains("node.background !is CompactUi.FieldSurface"))
    }
}
