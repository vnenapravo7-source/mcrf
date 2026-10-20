package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwgServerManagementStateTest {
    private fun response(user:JSONObject)=JSONObject().put("ok",true).put("data",JSONObject().put("roster",JSONObject().put("users",JSONObject().put("u_fixture",user))))
    @Test fun rosterProjectionDoesNotRetainProviderSecrets(){
        val user=JSONObject().put("name","Fixture").put("disabled",true).put("expiry",123L)
            .put("vk_link","secret-call-link").put("private_key","secret-private-key").put("subscription_token","secret-token")
        val client=SwgServerManagementState.clients(response(user)).single()
        assertEquals("Fixture",client.name);assertTrue(client.disabled);assertEquals(123L,client.expiresAt)
        assertFalse(client.toString().contains("secret"))
        user.put("name","Changed");assertEquals("Fixture",client.name)
    }
    @Test fun rejectedAndMalformedResultsAreNotDisplayedAsSuccessful(){
        for(source in listOf(response(JSONObject().put("name","Fixture")).put("ok",false),response(JSONObject().put("name","Fixture").put("disabled","false")))){
            try{SwgServerManagementState.clients(source);fail()}catch(_:IllegalArgumentException){}
        }
    }
}
