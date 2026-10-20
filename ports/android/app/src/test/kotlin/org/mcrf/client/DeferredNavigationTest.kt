package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test

class DeferredNavigationTest {
    @Test fun noGestureDoesNotInventNavigation(){assertNull(DeferredNavigation().take())}
    @Test fun latestGestureIsDeliveredExactlyOnce(){
        val queue=DeferredNavigation()
        queue.capture("WARP");queue.capture("Настройки")
        assertEquals("Настройки",queue.take());assertNull(queue.take())
        queue.capture("Главная");assertEquals("Главная",queue.take())
    }
    @Test fun invalidLabelsDoNotOverwriteAValidGesture(){
        val queue=DeferredNavigation();queue.capture("Маршрутизация")
        for(label in listOf("", " ","x\ny","x".repeat(129))){
            assertThrows(IllegalArgumentException::class.java){queue.capture(label)}
        }
        assertEquals("Маршрутизация",queue.take())
    }
}
