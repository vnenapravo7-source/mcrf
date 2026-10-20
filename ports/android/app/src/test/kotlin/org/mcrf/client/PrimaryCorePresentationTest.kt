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
    @Test fun switchDoesNotPromiseOtherModulesCoverSteerMissingFeatures(){
        val text=PrimaryCorePresentation.confirmation("steer")
        assertTrue(text.contains("Xray/TLS не расширяют Steer"));assertTrue(text.contains("несовместимые не отбросим"))
        assertTrue(PrimaryCorePresentation.details("sing-box").contains("VPN/прокси"))
    }
    @Test(expected=IllegalArgumentException::class) fun unknownCoreNeverReceivesInventedDescription(){PrimaryCorePresentation.details("unknown")}
}
