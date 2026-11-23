package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only; keyboard, font scaling and rendering still require device acceptance. */
class NetworkProfileRouteEditorContractTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun liveAndProfileRoutesUseOneFullEditor() {
        assertTrue(source("RoutingPage").contains("RouteRuleUi(activity,object:RouteRuleUi.Session"))
        assertTrue(source("NetworkProfileConfigurationUi").contains("RouteRuleUi(activity,adapter,servers=servers)"))
        val full=source("RouteRuleUi")
        for(component in listOf("RouteEditorPresets.show","AppPicker.show","route-expand-text","DnsEditor(","RouteServerPicker(","route-output"))
            assertTrue(component,full.contains(component))
        assertFalse(source("NetworkProfileConfigurationUi").contains("private fun editRule("))
    }
    @Test fun profileSessionNeverConstructsLiveStorageOrWritesSettings() {
        val adapter=source("NetworkProfileRuleAdapter")
        assertTrue(adapter.contains("RouteRuleUi.Scope.PROFILE"));assertTrue(adapter.contains("draft.edit(revision,generation"))
        assertTrue(adapter.contains("PresetAssets.validate"))
        for(name in listOf("NetworkProfileRuleAdapter","ProfileRoutingDraft")) {
            val text=source(name)
            for(forbidden in listOf("RoutingStore(activity)","RoutingStore(context)","ProfileStore(","Connections(","getSharedPreferences","startService"))
                assertFalse("$name: $forbidden",text.contains(forbidden))
        }
        assertTrue(source("RouteServerPicker").contains("entries?.let {show(it.filter(RouteServerPlan::selectable));return}"))
    }
    @Test fun profileErrorsAndFooterRemainInBoundedKeyboardAwareEditor() {
        val text=source("RouteRuleUi")
        assertTrue(text.contains("maxHeightFraction=if(session.scope==Scope.PROFILE).8f"))
        assertTrue(text.contains("SOFT_INPUT_ADJUST_RESIZE"));assertTrue(text.contains("ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        assertTrue(text.contains("requestRectangleOnScreen"));assertTrue(text.contains("route-editor-error"))
        assertTrue(text.contains("session.revision()==editingRevision&&session.generation()==editingGeneration"))
    }
    @Test fun spacingIsScopedToProfileHelpersAndNestedCardsAreFlat() {
        val layout=source("EditorLayout").substringBefore("    fun body(activity:")
        assertTrue(layout.contains("parent.tag==\"profile-card\"||parent.tag==\"profile-fold\""))
        assertTrue(layout.contains("card.background=if(nested)null"))
        assertTrue(layout.contains("setPadding(activity.dp(8),activity.dp(12),activity.dp(8),activity.dp(12))"))
        assertTrue(layout.contains("if(stacked)VERTICAL else HORIZONTAL"))
        assertTrue(layout.contains("label.paint.measureText"))
    }
    @Test fun closingEitherRuleDialogInvalidatesItsOwnProfileCommitToken() {
        val editor=source("RouteRuleUi")
        assertTrue(editor.contains("shown.setOnDismissListener"));assertTrue(editor.contains("session.dismiss()"))
        assertTrue(editor.contains("onViewDetachedFromWindow(view:View){dismissed()}"))
        assertEquals(2,Regex("bindDialog\\(dialog\\)").findAll(editor).count())
        val adapter=source("NetworkProfileRuleAdapter")
        assertTrue(adapter.contains("private val token=draft.editToken()"))
        assertTrue(adapter.contains("token.cancel()"));assertTrue(adapter.contains("token=token,change=edit"))
        assertTrue(adapter.contains("onViewDetachedFromWindow(view:View){dismiss()}"))
        val click=source("NetworkProfileConfigurationUi").substringAfter("fun edit(rule:JSONObject?) {").substringBefore("repeat(rules.length())")
        assertTrue(click.contains("val adapter=NetworkProfileRuleAdapter"))
    }
    @Test fun groupIndexIsExplicitAndServerFamilyUpdatesRetainedHighlight() {
        val profile=source("NetworkProfileConfigurationUi")
        assertTrue(profile.contains("RoutingGroups.select(candidate,groups[selectedIndex].first)"))
        val picker=source("RouteServerPicker")
        assertTrue(picker.contains("lateinit var family:ModeSwitch"))
        assertTrue(picker.contains("family.setMode(vpn,true);bs=!vpn;draft.clear();redraw()"))
    }
}
