package org.mcrf.client

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ConnectionSceneAssetTest {
    private fun asset()=javaClass.classLoader!!.getResourceAsStream("connection-scene-r10.html")!!.bufferedReader().use {it.readText()}.replace("\r\n","\n")
    @Test fun reviewedDrawingIncludesUniformContainmentAndRandomExits(){
        val text=asset()
        assertFalse(text.contains("Подача заготовки"));assertFalse(text.contains("Окрашивание детали"))
        val drawing=text.substring(text.indexOf("  const B="),text.indexOf("  const canvas=document")).trimEnd()
        // Normalize the decorative fixed-flow input before pinning the reviewed R12 drawing.
        assertTrue(drawing.contains("flow(c,44,t,moving)"))
        assertFalse(drawing.contains("flow(c,s.speed,t,moving)"))
        val source=drawing.replace("      // Decorative fixed44MB/s reference; never interpret real traffic as body motion.\n","")
            .replace("flow(c,44,t,moving)","flow(c,s.speed,t,moving)")
        val hash=MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString(""){"%02X".format(it)}
        assertEquals("EA9C3B34F5F1312F575941C87917DE1FA60A723F0C179DE921BA9169C9A64EB5",hash)
    }
    @Test fun localSceneHasNoNetworkOrJavaBridge(){
        val text=asset()
        assertTrue(text.contains("connect-src 'none'"))
        for(token in listOf("fetch(","XMLHttpRequest","WebSocket(","window.openai","addJavascriptInterface","https://"))
            assertFalse("Unexpected external surface: $token",text.contains(token))
        assertTrue(text.contains("window.McrfScene=Object.freeze"))
        assertTrue(text.contains("canvas{position:absolute;inset:0"))
        assertFalse(text.contains("display:grid"))
    }
    @Test fun allAcceptedStylesAndExitPoliciesHaveStableMappings(){
        assertEquals(setOf("blueprint","magnet","lathe"),ConnectionAppearance.choices.keys.filter {it!=ConnectionAppearance.TOGGLE}.map(ConnectionAppearance::canvasStyle).toSet())
        assertEquals(setOf("orbital","lock","cryo"),ConnectionAppearance.exits.keys.map(ConnectionAppearance::canvasExit).toSet())
        assertFalse(ConnectionAppearance.ownsExit(ConnectionAppearance.STEAM))
        assertFalse(ConnectionAppearance.ownsExit(ConnectionAppearance.CASTING))
        assertFalse(ConnectionAppearance.ownsExit(ConnectionAppearance.BLUEPRINT))
    }
}
