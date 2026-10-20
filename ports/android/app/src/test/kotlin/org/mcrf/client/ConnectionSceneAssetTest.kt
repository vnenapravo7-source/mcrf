package org.mcrf.client

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ConnectionSceneAssetTest {
    private fun asset()=javaClass.classLoader!!.getResourceAsStream("connection-scene-r10.html")!!.bufferedReader().use {it.readText()}.replace("\r\n","\n")
    @Test fun reviewedDrawingIncludesRound11CastingDisconnectRepair(){
        val text=asset()
        val source=text.substring(text.indexOf("  const B="),text.indexOf("  const canvas=document")).trimEnd()
        val hash=MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString(""){"%02X".format(it)}
        assertEquals("B293C16A59E2468FBB830032C79C1B5DCED239A21A88A2B2ABE6C36C6A0C1452",hash)
    }
    @Test fun localSceneHasNoNetworkOrJavaBridge(){
        val text=asset()
        assertTrue(text.contains("connect-src 'none'"))
        for(token in listOf("fetch(","XMLHttpRequest","WebSocket(","window.openai","addJavascriptInterface","https://"))
            assertFalse("Unexpected external surface: $token",text.contains(token))
        assertTrue(text.contains("window.McrfScene=Object.freeze"))
    }
    @Test fun allAcceptedStylesAndExitPoliciesHaveStableMappings(){
        assertEquals(setOf("blueprint","steam","plasma","magnet","lathe","casting","welding"),ConnectionAppearance.choices.keys.filter {it!=ConnectionAppearance.CLASSIC}.map(ConnectionAppearance::canvasStyle).toSet())
        assertEquals(setOf("door","orbital","lock","cryo"),ConnectionAppearance.exits.keys.map(ConnectionAppearance::canvasExit).toSet())
        assertTrue(ConnectionAppearance.ownsExit(ConnectionAppearance.STEAM))
        assertTrue(ConnectionAppearance.ownsExit(ConnectionAppearance.CASTING))
        assertFalse(ConnectionAppearance.ownsExit(ConnectionAppearance.BLUEPRINT))
    }
}
