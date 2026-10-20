package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierCaptchaDiagnosticsTest {
    @Test fun failuresAreVisibleInProblemsWithoutMarkingQueuedProofAsSuccess(){
        fun entry(value:String)=LogEntries.parse("2026-10-17 12:00:00 [Капча] "+CarrierCaptchaDiagnostics.finished("qwdtt",value,100))
        for(value in listOf("error:auto_timeout","error:auto_origin","error:auto_rejected"))assertEquals(LogEntries.Level.WARNING,entry(value).level)
        for(value in listOf("error:auto_tls","error:auto_network","error:auto_http","error:auto_webview"))assertEquals(LogEntries.Level.ERROR,entry(value).level)
        for(value in listOf("owned-proof","error:slider_detected"))assertEquals(LogEntries.Level.INFO,entry(value).level)
    }
    @Test fun distinguishesAllFourInstalledProtocolGenerations(){
        for((kind,label)in listOf("wdtt" to "WDTT","qwdtt" to "QWDTT","csqtt" to "CSQTT","csqtt25" to "CSQTT 2.5")){
            assertTrue(CarrierCaptchaDiagnostics.started(kind,9000).startsWith("$label ·"))
            assertTrue(CarrierCaptchaDiagnostics.finished(kind,"error:auto_timeout",8500).startsWith("$label ·"))
        }
    }
    @Test fun givesSafeSpecificHttpTlsAndNetworkDetails(){
        assertTrue(CarrierCaptchaDiagnostics.finished("wdtt","error:auto_http",300,httpStatus=429).contains("HTTP 429"))
        assertTrue(CarrierCaptchaDiagnostics.finished("csqtt","error:auto_tls",300,tlsError=3).contains("TLS; соединение отклонено (код 3)"))
        assertTrue(CarrierCaptchaDiagnostics.finished("qwdtt","error:auto_network",300,networkError=-2).contains("WebView -2"))
    }
    @Test fun doesNotClaimNativeDeliveryOrManualFallbackAlreadyHappened(){
        val sent=CarrierCaptchaDiagnostics.finished("csqtt25","private-owned-proof",500)
        assertTrue(sent.contains("доставка движку ещё не подтверждена"));assertFalse(sent.contains("private-owned-proof"))
        val failed=CarrierCaptchaDiagnostics.finished("csqtt25","error:slider_detected",500)
        assertTrue(failed.contains("дальнейший этап выбирает движок"));assertFalse(failed.contains("ручной режим включён"))
    }
    @Test fun unknownValuesAndIrrelevantOrInvalidCodesCannotLeak(){
        val secret="password=owned-secret https://private.invalid/?token=owned"
        for(value in listOf(secret,"error:$secret",null)){
            val message=CarrierCaptchaDiagnostics.finished(secret,value,-1,httpStatus=999,tlsError=999,networkError=-999)
            assertTrue(message.startsWith("SWG ·"));assertFalse(message.contains(secret));assertFalse(message.contains("999"))
        }
        assertFalse(CarrierCaptchaDiagnostics.finished("wdtt","error:auto_tls",1,httpStatus=403,tlsError=99).contains("403"))
    }
    @Test fun closedAndTimedOutAreDistinctAndBudgetsAreBounded(){
        assertTrue(CarrierCaptchaDiagnostics.finished("csqtt",null,10).contains("запрос закрыт или истёк"))
        assertTrue(CarrierCaptchaDiagnostics.finished("csqtt","error:auto_timeout",Long.MAX_VALUE).contains("180000 мс"))
        assertTrue(CarrierCaptchaDiagnostics.started("csqtt",-1).contains("0 мс"))
        assertTrue(CarrierCaptchaDiagnostics.started("csqtt",Long.MAX_VALUE).contains("150000 мс"))
    }
}
