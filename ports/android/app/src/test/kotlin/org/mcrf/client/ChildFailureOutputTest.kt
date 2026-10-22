package org.mcrf.client

import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChildFailureOutputTest {
    @Test fun drainsUtf8FinalUnterminatedErrorAndKeepsReadyExact() {
        val detail=CarrierFailureDetail("tls-core",JSONObject())
        var ready=0
        ChildFailureOutput.drain(ByteArrayInputStream("MCRF_READY\r\nerror: ошибка сокета".toByteArray()),detail){if(it=="MCRF_READY")ready++}
        assertEquals(1,ready);assertTrue(detail.describe("запуск",1).contains("ошибка сокета"))
    }
    @Test fun oversizedLineDoesNotBlockDrainingOrLeakItsSuffix() {
        val detail=CarrierFailureDetail("xray",JSONObject())
        val lines=mutableListOf<String>()
        ChildFailureOutput.drain(ByteArrayInputStream(("error:"+"x".repeat(10000)+"secret-tail\nerror: real reason\n").toByteArray()),detail){lines.add(it)}
        assertEquals(listOf("error: real reason"),lines)
        val message=detail.describe("проверка профиля",23)
        assertTrue(message.contains("real reason"));assertFalse(message.contains("secret-tail"))
    }
}
