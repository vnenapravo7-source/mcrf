package org.mcrf.client

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RetainedTunTransitionTest {
    @Test fun publishPrecedesOldCloseWithoutCaptureGap() {
        val calls=mutableListOf<String>()
        RetainedTunTransition.replace("old",{true},{calls+="establish";"new"},{calls+="publish:$it"},{calls+="close:$it"})
        assertEquals(listOf("establish","publish:new","close:old"),calls)
    }
    @Test fun cancelledBeforeEstablishDoesNothing() {
        val calls=mutableListOf<String>()
        assertThrows(IOException::class.java){RetainedTunTransition.replace("old",{false},{calls+="establish";"new"},{calls+="publish"},{calls+="close"})}
        assertTrue(calls.isEmpty())
    }
    @Test fun cancellationDuringEstablishClosesOnlyCandidate() {
        var live=true;val calls=mutableListOf<String>()
        assertThrows(IOException::class.java){RetainedTunTransition.replace("old",{live},{live=false;"new"},{calls+="publish"},{calls+="close:$it"})}
        assertEquals(listOf("close:new"),calls)
    }
    @Test fun failedEstablishPreservesRetainedDescriptor() {
        val calls=mutableListOf<String>()
        assertThrows(IOException::class.java){RetainedTunTransition.replace<String>("old",{true},{throw IOException("establish")},{calls+="publish"},{calls+="close"})}
        assertTrue(calls.isEmpty())
    }
    @Test fun publicationFailureClosesCandidateButNotRetainedDescriptor() {
        val calls=mutableListOf<String>()
        assertThrows(IOException::class.java){RetainedTunTransition.replace("old",{true},{"new"},{throw IOException("publish")},{calls+="close:$it"})}
        assertEquals(listOf("close:new"),calls)
    }
    @Test fun carrierAndOptionalWorkersPrepareBeforeStartAndOrdinaryCaptureIsPreserved() {
        fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first{it.isFile}.readText().replace("\r\n","\n")
        val service=source("McrfVpnService")
        assertTrue(service.contains("prepareCarrierHandoverTun(vpn,requested)\n            val transport=NativeCarrier.start"))
        assertTrue(service.contains("if(plan.requiresWorkers){\n                prepareCarrierHandoverTun(vpn,requested)"))
        assertTrue(service.contains("if (nativeUidBypass && !restricted) builder.addDisallowedApplication(packageName)"))
        val host=source("ClientPrimaryHostPlatform")
        assertTrue(host.contains("check(active==null)"))
        assertTrue(host.contains("takeIf {it.first===old}"))
        assertTrue(host.contains("retainedTunPlan = null"))
    }
}
