package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class WarpNetworkPolicyTest {
    @Test fun activeVpnNeverSelected() {
        assertEquals(12L,WarpNetworkPolicy.select(listOf(
            WarpNetworkPolicy.Candidate(10,true,false,true,true),
            WarpNetworkPolicy.Candidate(11,true,true,false,false),
            WarpNetworkPolicy.Candidate(12,true,true,true,false))))
    }
    @Test fun prefersActivePhysicalAndAllowsUnvalidatedPhysical() {
        assertEquals(11L,WarpNetworkPolicy.select(listOf(
            WarpNetworkPolicy.Candidate(11,true,true,false,true),
            WarpNetworkPolicy.Candidate(12,true,true,true,false))))
        assertEquals(11L,WarpNetworkPolicy.select(listOf(WarpNetworkPolicy.Candidate(11,true,true,false,false))))
    }
    @Test fun missingPhysicalOrZeroHandleFailsClosed() {
        for(candidates in listOf(emptyList(),listOf(WarpNetworkPolicy.Candidate(0,true,true,true,true)),listOf(WarpNetworkPolicy.Candidate(10,true,false,true,true)),listOf(WarpNetworkPolicy.Candidate(11,false,true,true,true)))) {
            assertThrows(IllegalArgumentException::class.java) { WarpNetworkPolicy.select(candidates) }
        }
    }
}
