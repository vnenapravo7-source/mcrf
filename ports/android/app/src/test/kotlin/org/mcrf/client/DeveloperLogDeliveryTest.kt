package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DeveloperLogDeliveryTest {
    @Test fun deliveryEndpointAndUiAreExplicitAndBounded(){
        val delivery=java.io.File("src/main/kotlin/org/mcrf/client/DeveloperLogDelivery.kt").takeIf {it.isFile}?:java.io.File("app/src/main/kotlin/org/mcrf/client/DeveloperLogDelivery.kt")
        val ui=java.io.File("src/main/kotlin/org/mcrf/client/LogsPage.kt").takeIf {it.isFile}?:java.io.File("app/src/main/kotlin/org/mcrf/client/LogsPage.kt")
        assertTrue(delivery.readText().contains("https://vnenapravo7.ru/wdtt/api/logs"))
        assertEquals(4*1024*1024,DeveloperLogDelivery.MAX_BYTES)
        assertTrue(ui.readText().contains("Отправить логи разработчику"))
    }
}
