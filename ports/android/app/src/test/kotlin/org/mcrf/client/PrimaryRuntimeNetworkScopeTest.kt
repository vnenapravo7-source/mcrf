package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PrimaryRuntimeNetworkScopeTest {
    @Test fun actualBackupSteerMustRebuildInsteadOfResettingOnlyDispatcher(){
        assertTrue(PrimaryRuntimeNetworkScope.requiresRestart("steer",false,false))
    }
    @Test fun boundHelperAndRouteOwnersCannotFollowGenericCoreReset(){
        assertTrue(PrimaryRuntimeNetworkScope.requiresRestart("singbox",true,false))
        assertTrue(PrimaryRuntimeNetworkScope.requiresRestart("singbox",false,true))
    }
    @Test fun unassistedSingBoxRetainsExistingNetworkResetPath(){
        assertFalse(PrimaryRuntimeNetworkScope.requiresRestart("singbox",false,false))
        assertFalse(PrimaryRuntimeNetworkScope.requiresRestart(null,false,false))
    }
}
