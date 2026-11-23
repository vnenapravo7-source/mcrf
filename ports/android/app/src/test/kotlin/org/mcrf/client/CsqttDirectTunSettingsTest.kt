package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source/model coverage only; execute with the approved integrated batch. */
class CsqttDirectTunSettingsTest {
    @Test fun preferenceIsDefaultOffAndReadsOnlyItsOwnKeyWithoutMutation() {
        val stored=mapOf("tun" to true,"autoDetectBs" to true)
        val reads=mutableListOf<Pair<String,Boolean>>()
        assertFalse(CsqttDirectTunSettings.selected {key,default->reads.add(key to default);stored[key]?:default})
        assertTrue(reads.isEmpty())
        assertFalse(CsqttDirectTunSettings.selected {_,_->true})
        assertEquals(mapOf("tun" to true,"autoDetectBs" to true),stored)
    }

    @Test fun onlyBsVpnCsqttIdleAndNativeAdmittedMayEnableControl() {
        for(saved in listOf(false,true))for(bs in listOf(false,true))for(vpn in listOf(false,true))
            for(csqtt in listOf(false,true))for(busy in listOf(false,true))for(native in listOf(false,true)) {
                val state=CsqttDirectTunSettings.state(saved,bs,vpn,csqtt,busy,if(native)null else "Нет подтверждённого прямого TUN.")
                assertEquals(bs,state.visible)
                assertEquals(saved,state.selected) // Incompatibility must not rewrite the user's saved preference.
                assertEquals(bs&&vpn&&csqtt&&!busy&&native,state.canEnable)
                assertEquals(bs&&!busy,state.canDisable)
                assertEquals(if(saved)state.canDisable else state.canEnable,state.available)
                assertEquals(!state.canEnable,state.reason!=null)
            }
    }

    @Test fun savedOnCanAlwaysBeDisabledWithoutSupportedServerModeOrNativeCapability() {
        for(vpn in listOf(false,true))for(csqtt in listOf(false,true))for(native in listOf(false,true)) {
            val state=CsqttDirectTunSettings.state(true,true,vpn,csqtt,false,if(native)null else "Нет capability.")
            assertTrue(state.selected)
            assertTrue(state.available)
            assertTrue(state.canSet(false))
            assertEquals(vpn&&csqtt&&native,state.canSet(true))
            assertFalse(CsqttDirectTunSettings.state(true,true,vpn,csqtt,true,null).canSet(false))
        }
    }

    @Test fun sourceReusesExistingRowAndPillWithIndependentHelpAndRefreshesRetainedSection() {
        val source=File("src/main/kotlin/org/mcrf/client/MainActivity.kt").readText()
        val start=source.indexOf("private fun directTunChoice()")
        val end=source.indexOf("private fun autoBsChoice()",start)
        assertTrue(start>=0&&end>start)
        val ui=source.substring(start,end)
        assertTrue(ui.contains("listOf(\"Вкл\",\"Выкл\"),listOf(\"\",\"\"),true"))
        assertTrue(ui.contains("connectionOptionRow(CsqttDirectTunSettings.TITLE"))
        assertTrue(ui.contains("CsqttDirectTunSettings.HELP"))
        assertTrue(ui.contains("CsqttDirectTunSupport.unavailableReason"))
        assertTrue(ui.contains("putBoolean(CsqttDirectTunSettings.KEY,enabled).commit()"))
        assertTrue(ui.contains("else McrfVpnService.directTunChanged(this)"))
        assertTrue(ui.contains("if(!current.canSet(enabled))"))
        assertTrue(ui.contains("if(!latest.canSet(enabled))"))
        assertFalse(ui.contains("prepareConnection("))
        assertFalse(ui.contains("putBoolean(\"tun\""))
        assertFalse(ui.contains("putBoolean(BsDetectionMonitor.KEY"))
        assertTrue(source.contains("directTunControlsRefresh=null"))
        assertTrue(source.contains("directTunControlsRefresh?.invoke()"))
        assertFalse(source.contains("connectionCard.addView(directTunChoice()"))
    }
}
