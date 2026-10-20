package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CsqttNativeFailureTest {
    private fun event(message:String,code:String="handshake_rejected",fatal:Any=true)=
        "__CSQTT_EVENT__|ERROR|"+JSONObject().put("code",code).put("message",message).put("fatal",fatal)
    @Test fun recognizesOnlyPinnedStructuredFatalRejections() {
        assertEquals(CsqttNativeFailure.Kind.PROTOCOL,CsqttNativeFailure.read(event("FATAL_PROTOCOL: fixture mismatch")))
        assertEquals(CsqttNativeFailure.Kind.ACCESS,CsqttNativeFailure.read(event("FATAL_AUTH: fixture password rejected")))
    }
    @Test fun shippedBridgeFixedSanitizedLiteralsAreRecognizedWithoutParsingArbitraryLogs() {
        assertEquals(CsqttNativeFailure.Kind.PROTOCOL,CsqttNativeFailure.read("[CSQTT] Клиент и сервер CSQTT несовместимы по версии протокола или числу потоков."))
        for(line in listOf("[CSQTT] Сервер отказал в авторизации CSQTT. Проверьте пароль и его привязку в панели.",
            "[CSQTT] Пароль CSQTT привязан к другому устройству. Создайте отдельный пароль для этого ПК."))
            assertEquals(CsqttNativeFailure.Kind.ACCESS,CsqttNativeFailure.read(line))
        assertNull(CsqttNativeFailure.read("prefix [CSQTT] Клиент и сервер CSQTT несовместимы по версии протокола или числу потоков."))
    }
    @Test fun ordinaryLogsAndRetryableWorkerTimeoutsDoNotBecomeGlobalFatalState() {
        for(line in listOf("[worker] FATAL_PROTOCOL: test",event("FATAL_PROTOCOL: test",fatal=false),
            event("FATAL_AUTH: AUTH_RETRY"),event("FATAL_HANDSHAKE: timeout","handshake_timeout"),
            event("FATAL_AUTH: test",fatal="true"),event("FATAL_AUTH: test","other"),"__CSQTT_EVENT__|ERROR|{}"))
            assertNull(CsqttNativeFailure.read(line))
    }
    @Test fun malformedOversizedAndConfigEventsCannotTriggerOrLeakSecrets() {
        for(line in listOf("__CSQTT_EVENT__|ERROR|{",event("FATAL_AUTH:"+"x".repeat(8192)),
            "__CSQTT_EVENT__|CONFIG|"+JSONObject().put("password","fixture-secret")))assertNull(CsqttNativeFailure.read(line))
        val failure=CsqttNativeFailure.read(event("FATAL_AUTH: password=fixture-secret token=fixture-token"))!!
        assertFalse(failure.explanation.contains("fixture-secret"));assertFalse(failure.explanation.contains("fixture-token"))
    }
}
