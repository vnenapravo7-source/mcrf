package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Scoped source contracts; actual font/inset rendering requires Android acceptance. */
class CompactProfileEditorContractTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()

    @Test fun cardRadiusUsesDensityAndAllConsumersPassContext() {
        val compact=source("CompactUi")
        assertTrue(compact.contains("historyBackground(context:android.content.Context,index:Int,radiusDp:Int=16)"))
        assertTrue(compact.contains("cornerRadius=radiusDp*context.resources.displayMetrics.density"))
        assertFalse(compact.contains("cornerRadius=24f"))
        for(name in listOf("ConditionProfilesUi","NetworkProfileConfigurationUi","EditorLayout","LogsPage")) {
            assertFalse(name,Regex("historyBackground\\((?:index|[0-9])").containsMatchIn(source(name)))
        }
        // The history surface retains its intentional 20dp radius.
        assertTrue(source("LogsPage").contains("radiusDp=20"))
    }

    @Test fun profileRhythmIsScopedAndControlsCanGrowWithFonts() {
        val layout=source("EditorLayout")
        assertTrue(layout.contains("setPadding(activity.dp(16),activity.dp(16),activity.dp(16),activity.dp(16))"))
        assertTrue(layout.contains("setPadding(activity.dp(8),activity.dp(12),activity.dp(8),activity.dp(12))"))
        assertTrue(layout.contains("parent.addView(this,activity.space(0,8))"))
        assertTrue(layout.contains("activity.text(title,14,Loft.INK)"))
        assertTrue(layout.contains("control.textSize=13f"))
        assertTrue(layout.contains("control.minHeight=activity.dp(48)"))
        assertFalse(layout.contains("control.height="))
        assertTrue(layout.contains("fun body(activity:MainActivity)"))
        assertTrue(layout.contains("activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(16)"))
        val mode=source("ModeSwitch")
        assertTrue(mode.contains("formStyle:Boolean=false"))
        assertTrue(mode.contains("if(formStyle)activity.dp(12).toFloat()"))
    }

    @Test fun editorKeepsNativeFooterOutsideBoundedScrollableForm() {
        val editor=source("ConditionProfilesUi").substringAfter("private fun editor(")
        assertTrue(editor.contains("EditorLayout.profileBody(activity)"))
        assertTrue(editor.contains("maxHeightFraction=.8f"))
        assertTrue(editor.contains("CompactUi.scroll(activity,form,.6f)"))
        assertTrue(editor.contains("setView(profileScroll)"))
        assertTrue(editor.contains("setNegativeButton(\"Отмена\",null).setPositiveButton(\"Сохранить\",null)"))
        assertTrue(editor.contains("SOFT_INPUT_ADJUST_RESIZE"))
        assertTrue(editor.contains("EditorLayout.profileFold(activity,identity,\"Если условия совпадут\")"))
        assertTrue(source("ThemedDialogBuilder").contains("metrics.heightPixels*maxHeightFraction.coerceIn(.3f,.85f)"))
    }

    @Test fun triggerAndConnectionSelectorsRetainSelectionInOneRow() {
        val editor=source("ConditionProfilesUi").substringAfter("private fun editor(")
        assertTrue(editor.contains("listOf(\"Тип сети\",\"Расписание\"),listOf(\"\",\"\"),formStyle=true"))
        assertTrue(editor.contains("triggerMode.setMode(value,true)"))
        val actions=source("NetworkProfileConfigurationUi")
        assertTrue(actions.contains("connectionMode.setMode(vpn,true)"))
        assertTrue(actions.contains("listOf(\"VPN\",\"БС\"),listOf(\"shield\",\"proxy\"),formStyle=true"))
        assertTrue(actions.contains("profileField(activity,parent,title,button,inline=true)"))
        assertTrue(source("ConditionProfilesTriggerUi").contains("EditorLayout.profileControl(activity,this)"))
    }
}
