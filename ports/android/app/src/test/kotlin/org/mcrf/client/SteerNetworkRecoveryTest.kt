package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SteerNetworkRecoveryTest {
    @Test fun recoveryBarrierIsReleasedBeforeEffectiveSteerHealthIsRearmed() {
        val file=listOf(File("src/main/kotlin/org/mcrf/client/McrfVpnService.kt"),File("app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt")).first {it.isFile}
        val source=file.readText().replace("\r\n","\n")
        // Reconstruction moved out of the queue admission function into the bounded retry owner.
        val reset=source.substringAfter("private fun runRecovery(session:RuntimeStart)").substringBefore("private val preferences")
        assertTrue(reset.contains("if(!recoveryLive(session)||networkRecovery!==session)return@execute"))
        val arm="if(bs==null&&sessionPrimaryChoice==\"steer\"&&autoAllowed())"
        val at=reset.indexOf(arm)
        assertTrue(at>0)
        assertTrue(reset.lastIndexOf("clearNetworkRecovery()",at) in 0 until at)
        assertTrue(reset.substring(at).contains("currentStart?.takeIf {it.requested==requested}?.let {scheduleSteerHealth(it)}"))
        // Preferred Steer may have an effective sing-box WG session. Do not
        // rearm based only on the saved preference or bypass AUTO_OFF.
        assertFalse(reset.substring(at).substringBefore("}catch(error:Exception)").contains("ClientPrimarySelection.selected"))
    }
}
