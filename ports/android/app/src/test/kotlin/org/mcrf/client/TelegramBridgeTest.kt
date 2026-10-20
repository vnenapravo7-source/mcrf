package org.mcrf.client
import org.junit.Test
import org.junit.Assert.*
class TelegramBridgeTest {
    @Test fun loopbackLink(){assertEquals("https://t.me/socks?server=127.0.0.1&port=19071",TelegramBridge.setupLink(19071))}
    @Test fun invalidPorts(){for(port in listOf(-1,0,1023,65536))assertThrows(IllegalArgumentException::class.java){TelegramBridge.setupLink(port)}}
}
