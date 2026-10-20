package org.mcrf.client
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.Base64
class WarpProfileTest {
    @Test fun auxiliaryRegistrationIdentityDoesNotActivateNestedTunnel(){
        val raw=JSONObject(account()).put("outer",JSONObject(account()).put("id","auxiliary"))
        val checked=WarpProfile.account(raw.toString())
        assertEquals("auxiliary",checked.getJSONObject("outer").getString("id"))
        val request=WarpProfile.request("scan",checked.toString(),"auto","foreign",true,false)
        assertFalse(request.has("through"))
        assertTrue(WarpProfile.isWarp(WarpProfile.initial(checked.toString())))
    }
    @Test fun malformedAuxiliaryIdentityStillRejected(){
        assertThrows(IllegalArgumentException::class.java){WarpProfile.account(JSONObject(account()).put("outer","unexpected").toString())}
    }
    private fun account()=JSONObject().put("id","fixture").put("token","fixture-only").put("private_key",Base64.getEncoder().encodeToString(ByteArray(32){(it+1).toByte()})).put("peer_public_key",WarpProfile.PEER_KEY).put("ipv4","172.16.0.2").put("ipv6","2606:4700:110::123").toString()
    @Test fun identityByKey(){val entry=WarpProfile.initial(account());assertTrue(WarpProfile.isWarp(entry));assertEquals(2,entry.endpoint!!.getJSONArray("address").length());assertEquals("WARP · WireGuard",entry.name)}
    @Test fun preservesAccount(){val entry=WarpProfile.initial(account());val store=ConnectionCatalogue("");val id=store.saveWarp(account(),entry.original);assertEquals(account(),store.warpAccount(id));assertEquals(account(),ConnectionCatalogue(store.serialize()).warpAccount(id))}
    @Test fun traceProvesCountry(){assertEquals("AMS",WarpProfile.trace("warp=on\nloc=NL\ncolo=AMS\n","foreign",true).node);assertEquals("RU",WarpProfile.trace("warp=plus\nloc=RU\ncolo=LED","RU",true).country)}
    @Test fun falseTraceRefused(){for(text in listOf("warp=off\nloc=NL\ncolo=AMS","warp=on\nloc=RU\ncolo=LED","warp=on\nloc=NL\ncolo=DME","warp=on\nloc=NL\ncolo=AMS\nwarp=off"))assertThrows(IllegalArgumentException::class.java){WarpProfile.trace(text,"foreign",true)}}
    @Test fun scannerCannotRotateKeys(){val entry=WarpProfile.initial(account());val wrong=JSONObject(account()).put("private_key",Base64.getEncoder().encodeToString(ByteArray(32){3})).toString();assertThrows(IllegalArgumentException::class.java){WarpProfile.prepare(entry.original,wrong)}}
    @Test fun byteValidation(){val entry=WarpProfile.initial(account());val valid=entry.original+"\nReserved = 1, 2, 255\n";assertEquals(255,ServerImport.read(valid).single().endpoint!!.getJSONArray("peers").getJSONObject(0).getJSONArray("reserved").getInt(2));assertThrows(IllegalArgumentException::class.java){ServerImport.read(entry.original+"\nReserved = 1, 2, 256\n")}}
    @Test fun replacementKeepsKeys(){val entry=WarpProfile.initial(account());val store=ConnectionCatalogue("");val id=store.saveWarp(account(),entry.original);val config=entry.original.replace("engage.cloudflareclient.com:2408","162.159.192.5:4500");val updated=store.saveWarp(account(),config,id);assertNotEquals(id,updated);assertEquals(1,store.entries().size);assertEquals(account(),store.warpAccount(updated));assertNull(store.warpAccount(id))}
    @Test fun removePrunesSecret(){val entry=WarpProfile.initial(account());val store=ConnectionCatalogue("");val id=store.saveWarp(account(),entry.original);store.remove(id);assertNull(store.warpAccount(id));assertFalse(store.serialize().contains("fixture-only"))}
}
