package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class WarpProgressPhaseTest {
    @Test fun nativeScanSetupAndPortLabelsAreRecognized() {
        for(label in listOf("Port","Ports","Interface","Through"))
            assertEquals("scan_setup",WarpProgressPhase.parse(label))
        for(label in listOf("Phase 1: probing reachable WARP ports","Phase 1: sweeping alternate WARP ports"))
            assertEquals("scan_ports",WarpProgressPhase.parse(label))
    }
    @Test fun nativeTunnelLabelsNormalizeWithoutExposingRawText() {
        for(protocol in listOf("wg","awg","masque-h2","masque-h3"))
            assertEquals("scan_tunnels",WarpProgressPhase.parse("Phase 2: verifying tunnels (proto=$protocol)"))
    }
    @Test fun existingEnumPhasesRemainCompatible() {
        for(phase in listOf("register","registration","scan","scan_tunnels"))
            assertEquals(phase,WarpProgressPhase.parse(phase))
    }
    @Test fun unknownDiagnosticsAndUnboundedLabelsAreRejected() {
        for(label in listOf("", "x".repeat(49), "Phase 2: secret", "Port account=private", "scan\nprivate"))
            assertNull(WarpProgressPhase.parse(label))
    }
}
