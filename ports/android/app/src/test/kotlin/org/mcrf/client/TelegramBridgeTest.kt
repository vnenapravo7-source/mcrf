package org.mcrf.client
import org.junit.Test
import org.junit.Assert.*
class TelegramBridgeTest {
    @Test fun loopbackLink(){assertEquals("https://t.me/socks?server=127.0.0.1&port=19071",TelegramBridge.setupLink(19071))}
    @Test fun invalidPorts(){for(port in listOf(-1,0,1023,65536))assertThrows(IllegalArgumentException::class.java){TelegramBridge.setupLink(port)}}
    @Test fun proxyButtonDescribesAddingProxyAndKeepsTelegramSetupAction(){
        val relative="src/main/kotlin/org/mcrf/client/TelegramControls.kt"
        val file=java.io.File(relative).takeIf {it.isFile}?:java.io.File("app/$relative")
        val source=file.readText()
        assertTrue(source.contains("iconButton(\"telegram\",\"Добавить в TG Proxy\")"))
        assertTrue(source.contains("bridge.openTelegram()"));assertTrue(source.contains("tag=\"telegram-open\""))
        assertFalse(source.contains("Открыть в Telegram"))
    }
}
