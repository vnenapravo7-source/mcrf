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
        val carrier=service.substringAfter("profile?.let {").substringBefore("val outputs=")
        assertTrue(carrier.indexOf("prepareCarrierHandoverTun(vpn,requested)") in 0 until carrier.indexOf("NativeCarrier.start("))
        val workers=service.substringAfter("if(plan.requiresWorkers){")
        assertTrue(workers.contains("val legacyWorkers=vpn&&!boundWorkers"))
        assertTrue(workers.indexOf("if(legacyWorkers)prepareCarrierHandoverTun(vpn,requested)") in
            0 until workers.indexOf("OptionalCoreSession.start("))
        assertTrue(Regex("""captureVpnScope\(strictFixture\?\.includedPackages\?:ownerScope\?:options.includePackage,\s*strictFixture\?\.excludedPackages\?:options.excludePackage,nativeUidBypass\)""").containsMatchIn(service))
        assertTrue(service.contains("BuildConfig.DEBUG&&fixtureMode&&!nativeUidBypass&&ownerScope==null"))
        assertTrue(service.contains("val denied=if(bypassOwnUid)excludes+packageName else excludes"))
        assertTrue(service.contains("appScope.apply(builder)"))
        val host=source("ClientPrimaryHostPlatform")
        assertTrue(host.contains("check(active==null)"))
        assertTrue(host.contains("takeIf {it.first===old}"))
        assertTrue(host.contains("retainedTunPlan = null"))
    }
}
