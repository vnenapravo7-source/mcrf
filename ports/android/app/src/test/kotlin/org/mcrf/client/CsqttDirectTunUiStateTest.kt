package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source regressions for the integrated batch; no service/native or device execution. */
class CsqttDirectTunUiStateTest {
    private fun source()=File("src/main/kotlin/org/mcrf/client/MainActivity.kt").readText()
    private fun method(source:String,name:String,next:String):String {
        val start=source.indexOf(name)
        val end=source.indexOf(next,start+name.length)
        require(start>=0&&end>start)
        return source.substring(start,end)
    }

    @Test fun directConnectingAndPausedUseAuthoritativeFlagsNotExactStatusStrings() {
        val source=source()
        val active=method(source,"fun active()","private fun directStopAvailable()")
        val working=method(source,"fun working()","fun probing()")
        assertTrue(active.contains("McrfVpnService.directTunActive()"))
        assertTrue(working.contains("McrfVpnService.directTunWorking()"))
        assertFalse(working.contains("directTunActive()")) // A paused owned TUN is not a pending transition.
        assertFalse(active.contains("==\"Подключение… · Direct"))
    }

    @Test fun bothPowerEntrypointsStopOwnedDirectTunBeforeBusyOrAnyNewStart() {
        val source=source()
        val power=method(source,"internal fun toggleConnection()","internal fun toggleOrbitServer(")
        assertTrue(power.indexOf("if(directStopAvailable())")<power.indexOf("if(working())return"))
        assertTrue(power.contains("if(directStopAvailable()){stopConnection();updateHomeControl();return}"))
        val edge=method(source,"private fun toggleEdgeConnection(","fun setBsDetection(")
        assertTrue(edge.contains("if(whitelists&&!wanted&&directStopAvailable()){stopConnection();updateHomeControl();return}"))
        assertTrue(edge.indexOf("directStopAvailable()")<edge.indexOf("EdgeConnectionState.action("))
        val prepare=method(source,"private fun prepareConnection(","private fun launchConnection()")
        assertTrue(prepare.contains("if(McrfVpnService.directTunActive()){toast("))
        assertTrue(prepare.indexOf("if(McrfVpnService.directTunActive())")<prepare.indexOf("connectionPreparationError=null"))
    }

    @Test fun pendingLocksSettingsButKeepsStopEnabledAndPausedNeverRendersDisconnected() {
        val home=method(source(),"private fun updateHomeControl()","internal fun changeMode(")
        assertTrue(home.contains("val directPending=McrfVpnService.directTunWorking()"))
        assertTrue(home.contains("val powerEnabled=!stopping&&(!working()||directStopAvailable())"))
        assertTrue(home.contains("modeSwitch?.setAvailable(!working()&&!stopping)"))
        assertTrue(home.contains("directPaused->\"VPN приостановлен\""))
        assertTrue(home.contains("if(directStopAvailable())connect?.contentDescription="))
        for(vpn in listOf(false,true))for(csqtt in listOf(false,true)) {
            val paused=CsqttDirectTunSettings.state(true,true,vpn,csqtt,false,"Нет native capability.")
            assertTrue(paused.canSet(false))
            val pending=CsqttDirectTunSettings.state(true,true,vpn,csqtt,true,null)
            assertFalse(pending.canSet(false))
            assertFalse(pending.canSet(true))
        }
    }
}
