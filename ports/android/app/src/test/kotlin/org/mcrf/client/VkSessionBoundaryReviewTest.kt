package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VkSessionBoundaryReviewTest {
    private class Memory:VkRelayPersistence {
        private val root=JSONObject()
        override fun get(section:String)=JSONObject(root.optJSONObject(section)?.toString()?:"{}")
        override fun update(section:String,change:(JSONObject)->Unit){val value=get(section);change(value);root.put(section,value)}
        override fun remove(section:String){root.remove(section)}
    }
    private fun changedDuringPause(change:(VkRelayAccount,Memory)->Unit) {
        val memory=Memory();var requests=0
        lateinit var account:VkRelayAccount
        account=VkRelayAccount(memory,{
            requests++;JSONObject().put("response",JSONObject().put("join_link","A".repeat(42)+requests))
        },{change(account,memory)})
        account.link(VkRelayProtocol.Authorization("fixture-old-token","42",3600))
        assertThrows(IllegalStateException::class.java){account.callsFor("fixture-owner","qwdtt",2)}
        assertEquals("No second provider request after the boundary changed",1,requests)
        assertEquals(1,memory.get("vkCallGroups").getJSONObject("fixture-owner").getJSONArray("hashes").length())
    }
    @Test fun unlinkDuringPauseStopsBeforeAnotherRequest()=changedDuringPause {account,_->account.unlink()}
    @Test fun differentOwnerDuringPauseStopsBeforeAnotherRequest()=changedDuringPause {account,_->account.link(VkRelayProtocol.Authorization("fixture-new-token","43",3600))}
    @Test fun tokenRefreshForSameOwnerStopsOldOperation()=changedDuringPause {account,_->account.link(VkRelayProtocol.Authorization("fixture-new-token","42",3600))}
    @Test fun cooldownIntroducedDuringPauseStopsBeforeRequest()=changedDuringPause {_,store->store.update("vk"){it.put("retryAfter",Long.MAX_VALUE)}}
    @Test fun expiryDuringPauseStopsBeforeRequest()=changedDuringPause {_,store->store.update("vk"){it.put("expires",0)}}
    @Test fun closedCallbackCannotPersistLateAuthorization() {
        val state=VkRelayProtocol.newState();var writes=0
        val gate=VkRelayCallback {writes++}
        gate.close()
        assertEquals(VkRelayCallback.Result.IGNORE,gate.accept("https://oauth.vk.ru/blank.html#access_token=fixture-token&user_id=42&state=$state",state,true))
        assertEquals(0,writes)
    }
    @Test fun closedFailedCallbackCannotRetryItsWrite() {
        val state=VkRelayProtocol.newState();var writes=0
        val gate=VkRelayCallback {writes++;throw IllegalStateException("fixture write rejected")}
        val url="https://oauth.vk.ru/blank.html#access_token=fixture-token&user_id=42&state=$state"
        assertEquals(VkRelayCallback.Result.SAVE_FAILED,gate.accept(url,state,true))
        gate.close()
        assertEquals(VkRelayCallback.Result.IGNORE,gate.accept(url,state,true));assertEquals(1,writes)
    }
}
