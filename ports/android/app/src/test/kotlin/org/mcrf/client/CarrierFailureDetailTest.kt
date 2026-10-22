package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CarrierFailureDetailTest {
    @Test fun preservesActualReasonEngineStageAndExitWithoutGuessing() {
        val detail=CarrierFailureDetail("csqtt25",JSONObject())
        detail.offer("error: permission denied loading transport")
        val message=detail.describe("выход до готовности",1)
        for(part in listOf("CSQTT25","выход до готовности","=1","permission denied"))assertTrue(message.contains(part))
        assertFalse(message.contains("проверь сервер"))
    }
    @Test fun missingReasonIsExplicitNotFabricated() {
        val message=CarrierFailureDetail("wdtt",JSONObject()).describe("запуск",null)
        assertTrue(message.contains("не сообщил причину"))
        assertTrue(message.contains("ещё недоступен"))
    }
    @Test fun replacesProfileAndOwnerSecretsBeforeTruncating() {
        val detail=CarrierFailureDetail("csqtt",JSONObject().put("password","fixture-password")
            .put("hashes",JSONArray().put("fixture-hash")).put("nested",JSONObject().put("vk_token","fixture-token")),listOf("fixture-socks"))
        detail.offer("error: fixture-password fixture-hash fixture-token fixture-socks https://fixture.invalid/?token=query-secret")
        val message=detail.describe("запуск",2)
        for(secret in listOf("fixture-password","fixture-hash","fixture-token","fixture-socks","query-secret"))assertFalse(message.contains(secret))
    }
    @Test fun ignoresPrivateProtocolRecordsAndLargeLines() {
        val detail=CarrierFailureDetail("csqtt25",JSONObject())
        for(line in listOf("CONFIG|error|private","MCRF_CONFIG=error-private","CAPTCHA_SOLVE|error|private","{\"error\":\"private\"}","error".repeat(1000)))detail.offer(line)
        assertTrue(detail.describe("запуск",1).contains("не сообщил причину"))
    }
    @Test fun optionalCoreSecretsInsideOutboundArraysAreRemoved() {
        val config=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("password","nested-password").put("uuid","uuid-secret")))
        val detail=CarrierFailureDetail("xray",config)
        detail.offer("error: nested-password uuid=uuid-secret")
        val message=detail.describe("проверка профиля",2)
        assertFalse(message.contains("nested-password"));assertFalse(message.contains("uuid-secret"))
    }
    @Test fun boundsAndDeduplicatesFailureTailAndStripsTerminalControls() {
        val detail=CarrierFailureDetail("csqtt25",JSONObject())
        detail.offer("error: obsolete")
        repeat(3){detail.offer("error: reason-$it");detail.offer("error: reason-$it")}
        detail.offer("\u001b[31merror: final\u001b[0m\u202e"+"x".repeat(1000))
        val message=detail.describe("запуск",1)
        assertFalse(message.contains("obsolete"));assertFalse(message.contains("reason-0"))
        assertFalse(message.contains('\u001b'));assertFalse(message.contains('\u202e'))
        assertTrue(message.contains("final"));assertTrue(message.length<1300)
    }
}
