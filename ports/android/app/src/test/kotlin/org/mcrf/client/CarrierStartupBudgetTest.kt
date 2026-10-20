package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierStartupBudgetTest {
    @Test fun manualWaitPreservesRemainingNetworkTime(){
        val budget=CarrierStartupBudget(10000,0)
        assertTrue(budget.canContinue(2000,true))
        assertTrue(budget.canContinue(142000,false))
        assertTrue(budget.canContinue(149999,false))
        assertFalse(budget.canContinue(150000,false))
    }
    @Test fun repeatedChallengesCannotResetTheManualAllowance(){
        val budget=CarrierStartupBudget(10000,0)
        assertTrue(budget.canContinue(1000,true))
        assertTrue(budget.canContinue(101000,false))
        assertTrue(budget.canContinue(102000,true))
        assertTrue(budget.canContinue(152000,false))
        assertFalse(budget.canContinue(160000,true))
    }
    @Test fun noChallengeKeepsOriginalDeadline(){
        val budget=CarrierStartupBudget(10000,0)
        assertTrue(budget.canContinue(9999,false))
        assertFalse(budget.canContinue(10000,false))
    }
}
