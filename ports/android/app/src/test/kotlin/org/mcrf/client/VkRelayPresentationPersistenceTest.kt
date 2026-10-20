package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VkRelayPresentationPersistenceTest {
    @Test fun showingTheCurrentCallCountDoesNotWriteSettings(){
        var writes=0;var settings=JSONObject()
        val persistence=object:VkRelayPersistence{
            override fun get(section:String)=JSONObject(settings.toString())
            override fun update(section:String,change:(JSONObject)->Unit){writes++;change(settings)}
            override fun remove(section:String){error("No removal expected")}
        }
        val account=VkRelayAccount(persistence)
        account.setDefaultCount(account.defaultCount());assertEquals(0,writes)
        account.setDefaultCount(8);assertEquals(1,writes);assertEquals(8,account.defaultCount())
        account.setDefaultCount(8);assertEquals(1,writes)
        account.setDefaultCount(6);assertEquals(2,writes);assertEquals(6,account.defaultCount())
    }
}
