package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** No native/module/account/network operations. Queued for common P2. */
class WarpRecoveryPolicyTest {
    @Test fun russiaIsOfferedOnlyAfterExhaustedForeignSearchWithExistingRetry() {
        for(exhausted in listOf(false,true))for(foreign in listOf(false,true))for(retry in listOf(false,true))
            assertEquals(exhausted&&foreign&&retry,WarpRecoveryPolicy.offerRussia(exhausted,foreign,retry))
    }
    @Test fun explicitRussiaKeepsProtocolAndExtendedButNeverEnablesDme() {
        for(protocol in listOf("auto","wg","awg"))for(extended in listOf(false,true)) {
            val source=WarpOperations.Options(protocol,"foreign",true,extended)
            val russia=WarpRecoveryPolicy.russia(source)
            assertEquals("RU",russia.country);assertTrue(russia.excludeDme)
            assertEquals(protocol,russia.protocol);assertEquals(extended,russia.extended)
            assertEquals("foreign",source.country)
        }
    }
    @Test fun cancelledOrReplacedJobCannotPublishAnOfferOrCandidate() {
        val state=WarpControllerState();val first=state.begin("original")
        state.cancel();assertFalse(state.finish(first))
        assertThrows(java.util.concurrent.CancellationException::class.java){state.commit(first){error("stale commit")}}
        val second=state.begin("original");val third=state.begin("original")
        assertFalse(state.finish(second));assertTrue(state.current(third))
        var commits=0;state.commit(third){commits++};assertEquals(1,commits)
        assertFalse(state.finish(third));assertFalse(state.running())
    }
}
