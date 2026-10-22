package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SteerNetworkRecoveryTest {
    @Test fun recoveryBarrierIsReleasedBeforeEffectiveSteerHealthIsRearmed() {
        val file=listOf(File("src/main/kotlin/org/mcrf/client/McrfVpnService.kt"),File("app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt")).first {it.isFile}
        val source=file.readText().replace("\r\n","\n")
        val reset=source.substringAfter("private fun resetConnections(reason:String)").substringBefore("private val preferences")
        val arm="if(bs==null&&sessionPrimaryChoice==\"steer\"&&autoAllowed())"
        val at=reset.indexOf(arm)
        assertTrue(at>0)
        assertTrue(reset.lastIndexOf("clearNetworkRecovery()",at) in 0 until at)
        assertTrue(reset.substring(at).contains("currentStart?.takeIf {it.requested==requested}?.let {scheduleSteerHealth(it)}"))
        // Preferred Steer may have an effective sing-box WG session. Do not
        // rearm based only on the saved preference or bypass AUTO_OFF.
        assertFalse(reset.substring(at,reset.indexOf("ClientEvents.record",at)).contains("ClientPrimarySelection.selected"))
    }
}
