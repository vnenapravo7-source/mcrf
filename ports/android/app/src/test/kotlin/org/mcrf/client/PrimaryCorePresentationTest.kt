package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PrimaryCorePresentationTest {
    @Test fun steerExplainsIndependenceAndConcreteMissingProtocols(){
        val text=PrimaryCorePresentation.details("steer")
        listOf("без sing-box","IPv4","WireGuard/AmneziaWG","TUIC","AnyTLS","NaiveProxy","SSH","DNS отдельных правил").forEach {assertTrue(it,text.contains(it))}
    }
    @Test fun presentationDoesNotRepeatRemovedExperimentalTrafficClaim(){
        for(kind in listOf("sing-box","steer")){
            val text=(PrimaryCorePresentation.details(kind)+PrimaryCorePresentation.confirmation(kind)).lowercase()
            assertFalse(text.contains("эксперимент"));assertFalse(text.contains("трафик ещё проверяется"))
        }
    }
    @Test fun confirmationIsShortAndDoesNotDuplicateCapabilityDescriptions(){
        for(kind in listOf("steer","sing-box")) {
            val text=PrimaryCorePresentation.confirmation(kind)
            assertTrue(text.length<120)
            assertTrue(text.contains("совместимость"))
            listOf("IPv6","TUIC","AnyTLS","SSH","WireGuard").forEach {assertFalse(text.contains(it))}
        }
        assertTrue(PrimaryCorePresentation.details("sing-box").contains("VPN/прокси"))
    }
    @Test fun wgOfferNeverDuplicatesTheCompanionOrOffersItToSingbox() {
        for(kind in listOf("steer","sing-box"))for(addon in listOf(false,true)) {
            assertEquals(kind=="steer"&&!addon,PrimaryCorePresentation.offerWgAddon(kind,addon))
        }
    }
    @Test(expected=IllegalArgumentException::class) fun unknownCoreNeverReceivesInventedDescription(){PrimaryCorePresentation.details("unknown")}
}
