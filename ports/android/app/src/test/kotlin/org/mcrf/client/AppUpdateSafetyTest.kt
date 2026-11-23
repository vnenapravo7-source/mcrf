package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AppUpdateSafetyTest {
    @Test fun stableApkMayReplaceBetaWithSameCode(){
        AppUpdateSafety.validate("org.mcrf.client","org.mcrf.client","android-v2.0.0","2.0.0",10178,10178,setOf("trusted"),setOf("trusted"))
    }
    @Test fun matchingPackageSignerVersionAndMonotonicCodeAccepted(){AppUpdateSafety.validate("org.mcrf.client","org.mcrf.client","android-v0.7","0.7",10047,10048,setOf("trusted"),setOf("trusted"))}
    @Test fun differentPackageRejected(){assertThrows(IllegalArgumentException::class.java){AppUpdateSafety.validate("org.mcrf.client","org.other","android-v0.7","0.7",10047,10048,setOf("trusted"),setOf("trusted"))}}
    @Test fun wrongOrMissingSignerRejected(){for(actual in listOf(emptySet(),setOf("other")))assertThrows(IllegalArgumentException::class.java){AppUpdateSafety.validate("org.mcrf.client","org.mcrf.client","android-v0.7","0.7",10047,10048,setOf("trusted"),actual)}}
    @Test fun versionMismatchAndDowngradeRejected(){
        assertThrows(IllegalArgumentException::class.java){AppUpdateSafety.validate("org.mcrf.client","org.mcrf.client","android-v0.7","0.6.1",10047,10048,setOf("trusted"),setOf("trusted"))}
        assertThrows(IllegalArgumentException::class.java){AppUpdateSafety.validate("org.mcrf.client","org.mcrf.client","android-v0.7","0.7",10047,10046,setOf("trusted"),setOf("trusted"))}
    }
}
