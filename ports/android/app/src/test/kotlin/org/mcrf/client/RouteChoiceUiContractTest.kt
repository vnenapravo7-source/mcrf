package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards complement geometry tests; these do not prove device rendering. */
class RouteChoiceUiContractTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun routeActionsUseScopedRoundedMaskWithSelectedStateAndNoTint() {
        val style=source("RouteChoiceUi")
        assertTrue(style.contains("cornerRadius=activity.dp(14)"))
        assertTrue(style.contains("states,fill(Color.WHITE)"))
        assertTrue(style.contains("state_selected"))
        assertTrue(style.contains("backgroundTintList=null"))
        assertFalse(style.contains("ColorDrawable"))
        val action=source("RouteRuleUi").substringAfter("fun action(label:String,iconName:String)").substringBefore("val presets=")
        assertTrue(action.indexOf("RouteChoiceUi.style")>action.indexOf("DropdownStyle.apply"))
    }
    @Test fun domainAndIpIconsComeBeforePresetName() {
        val presets=source("RouteEditorPresets")
        assertTrue(presets.indexOf("icon(\"globe\"")<presets.indexOf("text(group?.first"))
        assertTrue(presets.indexOf("text(\"IP\"")<presets.indexOf("text(group?.first"))
        assertTrue(presets.contains("selector=RouteChoiceUi.background"))
    }
    @Test fun strategyIsInlineAndSingleLineWithNoNestedWindow() {
        val picker=source("RouteServerPicker")
        assertTrue(picker.contains("tag=\"route-auto-strategy\";textSize=13f;maxLines=1"))
        assertTrue(picker.contains("text=\"Автовыбор\""))
        assertTrue(picker.contains("setSingleLine(true);maxLines=1"))
        assertTrue(picker.contains("addView(toggle,LinearLayout.LayoutParams(-2,dp(48))"))
        val toggle=picker.substringAfter("val toggle=Switch(this)").substringBefore("val settings=")
        assertTrue(toggle.contains("textSize=13f"))
        assertFalse(toggle.contains("setAutoSizeTextType"))
        assertTrue(picker.contains("settings.addView(strategyChoices)"))
        assertFalse(picker.contains("DropdownPopup.show"))
        assertFalse(picker.contains("BottomSheet"))
        assertTrue(picker.contains("RouteChoicePopup.centered"))
    }
    @Test fun routeOutputsUseNativeAnchoringAndSameGeometryForAllEditors() {
        val page=source("RouteRuleUi")
        assertEquals(2,Regex("RouteChoicePopup\\.choices\\(this@with,anchor,TARGET_LABELS").findAll(page).count())
        val popup=source("RouteChoicePopup")
        assertTrue(popup.contains("root=anchor.rootView"))
        assertTrue(popup.contains("popup.showAsDropDown(anchor,"))
        assertTrue(popup.contains("popup.setIsLaidOutInScreen(true)"))
        assertTrue(popup.contains("popup.showAtLocation(root,"))
        assertFalse(popup.contains("showAtLocation(anchor,"))
    }
    @Test fun routeDnsGetsTheSameRoundedChoicesWithoutChangingGlobalDns() {
        assertTrue(source("DnsEditor").contains("routeChoices:Boolean=false"))
        assertTrue(source("DnsEditor").contains("if(routeChoices)RouteChoiceUi.spinner"))
        assertTrue(source("RouteRuleUi").contains("DnsConfig.defaults(),routeChoices=true"))
    }
}
