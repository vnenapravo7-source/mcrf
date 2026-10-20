package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class VkRelayTest {
    private class Memory(source: JSONObject=JSONObject()) : VkRelayPersistence {
        private val root=JSONObject(source.toString())
        fun snapshot()=JSONObject(root.toString())
        override fun get(section: String)=JSONObject(root.optJSONObject(section)?.toString() ?: "{}")
        override fun update(section: String,change: (JSONObject) -> Unit) { val s=get(section);change(s);root.put(section,s) }
        override fun remove(section: String) { root.remove(section) }
    }
    private val hash="A".repeat(43)
    @Test fun selectedCountReachesRuntimeAfterRealSwgShareAndImportWithoutNewProbeCalls() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{requests++;JSONObject().put("response",JSONObject().put("join_link",hash+requests))},{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        account.setDefaultSource(VkRelayProtocol.HashSource.AUTOMATIC);account.setDefaultCount(2)
        val raw=VkRelayRuntime.defaultsForNewQwdtt(JSONObject().put("type","qwdtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture"),account)
        val normalized=SwgProfile.fromJson("fixture",raw).carrier!!
        val imported=ServerImport.read(SwgProfile.share(normalized)).single().carrier!!
        val before=imported.toString()
        val prepared=VkRelayRuntime.prepare(imported,account)
        assertEquals(2,requests);assertEquals(2,prepared.getJSONArray("hashes").length())
        assertEquals(2,VkRelayRuntime.prepareForProbe(imported,account).getJSONArray("hashes").length())
        assertEquals(2,requests);assertEquals(before,imported.toString())
        assertEquals("manual",SwgProfile.fromJson("runtime",prepared).carrier!!.getString("hash_source"))
        assertFalse(SwgProfile.privateConfig(prepared,22300,22301,"user","pass","fixture-install").has("vk_call_count"))
    }
    @Test fun callbackGateConsumesAllEventsOnceAndNeverSavesForeignOrMissingState() {
        val state=VkRelayProtocol.newState()
        val url="https://oauth.vk.ru/blank.html#access_token=fixture&user_id=42&state=$state"
        var writes=0
        val gate=VkRelayCallback { writes++;assertEquals("fixture",it.token);assertEquals("42",it.user) }
        assertEquals(VkRelayCallback.Result.IGNORE,gate.accept(url,state,false));assertEquals(0,writes)
        assertEquals(VkRelayCallback.Result.IGNORE,gate.accept(url.replace("oauth.vk.ru","evil.test"),state,true))
        for(bad in listOf(url.substringBefore("&state="),url+"&state=$state",url.replace(state,"wrong"),"https://oauth.vk.ru/blank.html")) {
            assertEquals(VkRelayCallback.Result.REJECT,gate.accept(bad,state,true))
        }
        assertEquals(0,writes)
        assertEquals(VkRelayCallback.Result.SAVED,gate.accept(url,state,true))
        // Navigation followed by page/history/error delivery must not save a second time.
        repeat(7) { assertEquals(VkRelayCallback.Result.ALREADY_SAVED,gate.accept(url,state,true)) }
        assertEquals(1,writes)
        assertEquals(VkRelayCallback.Result.IGNORE,gate.accept("https://vk.ru/",state,true))
    }
    @Test fun callbackSaveFailureIsGenericAndDoesNotCompleteBinding() {
        val state=VkRelayProtocol.newState();var fail=true;var writes=0
        val gate=VkRelayCallback { writes++;if(fail) throw IllegalStateException("fixture secret must not become result") }
        val url="https://oauth.vk.com/blank.html#access_token=fixture&user_id=42&state=$state"
        assertEquals(VkRelayCallback.Result.SAVE_FAILED,gate.accept(url,state,true));fail=false
        assertEquals(VkRelayCallback.Result.SAVED,gate.accept(url,state,true))
        assertEquals(VkRelayCallback.Result.ALREADY_SAVED,gate.accept(url,state,true));assertEquals(2,writes)
    }
    @Test fun callbackRecognitionNeverWeakensAuthorization() {
        val state=VkRelayProtocol.newState()
        val good="https://oauth.vk.ru/blank.html#access_token=fixture&user_id=42&expires_in=10&state=$state"
        assertTrue(VkRelayProtocol.isCallback(good));assertNotNull(VkRelayProtocol.authorization(good,state))
        for(url in listOf(good.replace("oauth.vk.ru","evil.test"),good.replace("/blank.html","/other"),good.replace("https:","http:"),good.replace("oauth.vk.ru","oauth.vk.ru.evil.test"))) {
            assertFalse(VkRelayProtocol.isCallback(url));assertNull(VkRelayProtocol.authorization(url,state))
        }
        for(url in listOf(good.replace("state=$state","state=wrong"),good+"&state=$state",good.substringBefore("&state="),good.replace("user_id=42","user_id=0"))) {
            assertTrue(VkRelayProtocol.isCallback(url));assertNull(VkRelayProtocol.authorization(url,state))
        }
    }
    @Test fun selectedCountsAreScopedCachedAndBackedUp() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;JSONObject().put("response",JSONObject().put("join_link",hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture","42",3600))
        account.setDefaultCount(32);assertEquals(32,account.defaultCount())
        assertEquals(2,account.callsFor("selected","qwdtt",2).size)
        assertEquals(2,account.cachedFor("selected","qwdtt",2).size);assertEquals(2,requests)
        assertThrows(IllegalStateException::class.java) { account.callsFor("selected","qwdtt",3) };assertEquals(2,requests)
        assertEquals(32,account.callsFor("max","qwdtt",32).size);assertEquals(34,requests)
        AndroidDeployBackupSettings.validate(memory.snapshot())
        val restored=VkRelayAccount(Memory(memory.snapshot()),{ error("No API on restore") },{})
        assertEquals(32,restored.cachedFor("max","qwdtt",32).size)
        assertThrows(IllegalArgumentException::class.java) { restored.callsFor("bad","wdtt",7) }
        assertThrows(IllegalArgumentException::class.java) { account.setDefaultCount(0) }
        assertEquals(1,account.resolveCountFor("manual",listOf(hash),true,"qwdtt",32).size)
    }
    @Test fun protocolCountsAndRuntimeDefaultsKeepManualAndProbeSemantics() {
        for(kind in listOf("qwdtt","wdtt","csqtt")) {
            val memory=Memory();var requests=0
            val account=VkRelayAccount(memory,{ requests++;JSONObject().put("response",JSONObject().put("join_link",java.util.Base64.getUrlEncoder().encodeToString(ByteArray(32) { requests.toByte() }))) },{})
            account.link(VkRelayProtocol.Authorization("fixture","42",3600))
            assertEquals(1,account.callsFor("one",kind,1).size)
            assertEquals(if(kind=="qwdtt")32 else 6,account.callsFor("max",kind,if(kind=="qwdtt")32 else 6).size)
            AndroidDeployBackupSettings.validate(memory.snapshot())
        }
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;JSONObject().put("response",JSONObject().put("join_link",hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture","42",3600));account.setDefaultSource(VkRelayProtocol.HashSource.AUTOMATIC);account.setDefaultCount(2)
        val raw=VkRelayRuntime.defaultsForNewQwdtt(JSONObject().put("type","qwdtt"),account)
        assertEquals(2,raw.getInt("vk_call_count"))
        assertThrows(IllegalStateException::class.java) { VkRelayRuntime.prepareForProbe(raw,account) };assertEquals(0,requests)
        assertEquals(2,VkRelayRuntime.prepare(raw,account).getJSONArray("hashes").length());assertEquals(2,requests)
        assertEquals(2,VkRelayRuntime.prepareForProbe(raw,account).getJSONArray("hashes").length());assertEquals(2,requests)
        assertFalse(raw.has("hashes"))
    }
    @Test fun startsWithMainVkHome() { assertEquals("https://vk.ru/",VkRelayProtocol.LOGIN_URL) }
    @Test fun manualCallsBypassUnlinkedAccount() {
        val account=VkRelayAccount(Memory(),{ error("VK must never be called") },{})
        assertEquals(listOf("https://vk.ru/call/join/$hash"),account.resolve(listOf(hash),true,"qwdtt"))
    }
    @Test fun automaticCreatesExactlySixAndUsesCache() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        assertEquals(6,account.sixCalls("qwdtt").size);assertEquals(6,requests)
        assertEquals(6,account.sixCalls("qwdtt").size);assertEquals(6,requests)
    }
    @Test fun partialSuccessResumesWithoutRepeatingCalls() {
        val memory=Memory();var requests=0;var fail=true
        val account=VkRelayAccount(memory,{ requests++;if(requests==3 && fail) { fail=false;throw java.io.IOException("network") };JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        assertThrows(java.io.IOException::class.java) { account.sixCalls("qwdtt") }
        assertEquals(2,memory.get("vkCallGroups").getJSONObject("legacy-runtime").getJSONArray("hashes").length())
        assertEquals(6,account.sixCalls("qwdtt").size);assertEquals(7,requests)
    }
    @Test fun rateLimitPreventsRepeatedMutatingRequests() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;JSONObject().put("error",JSONObject().put("error_code",29)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        assertThrows(IllegalStateException::class.java) { account.sixCalls("qwdtt") }
        assertThrows(IllegalStateException::class.java) { account.sixCalls("qwdtt") };assertEquals(1,requests)
    }
    @Test fun unlinkDuringRequestNeverResurrectsCredentials() {
        val memory=Memory();val account=VkRelayAccount(memory,{ memory.remove("vk");JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/$hash")) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        assertThrows(IllegalStateException::class.java) { account.sixCalls("qwdtt") };assertFalse(account.linked())
    }
    @Test fun cancelPreventsNewRequests() {
        val account=VkRelayAccount(Memory(),{ error("must not run") },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        assertThrows(IllegalStateException::class.java) { account.sixCalls("qwdtt") { true } }
    }
    @Test fun callbackRequiresTrustedHostExactStateAndUniqueFields() {
        val good="https://oauth.vk.com/blank.html#access_token=fixture&user_id=42&state=state&expires_in=3600"
        assertNotNull(VkRelayProtocol.authorization(good,"state"))
        assertNull(VkRelayProtocol.authorization(good,"wrong"))
        assertNull(VkRelayProtocol.authorization(good.replace("oauth.vk.com","oauth.vk.com.evil.example"),"state"))
        assertNull(VkRelayProtocol.authorization(good+"&state=state","state"))
        assertFalse(VkRelayProtocol.trusted("http://vk.ru/"));assertFalse(VkRelayProtocol.trusted("https://user@vk.ru/"))
    }
    @Test fun manualHashesArePreservedWhenUnlinking() {
        val account=VkRelayAccount(Memory());account.setManualDefaults(listOf(hash));account.setDefaultSource(VkRelayProtocol.HashSource.AUTOMATIC)
        account.unlink();assertEquals(listOf("https://vk.ru/call/join/$hash"),account.manualDefaults());assertEquals(VkRelayProtocol.HashSource.MANUAL,account.defaultSource())
    }
    @Test fun runtimeManualPrecedenceNeverRequiresVk() {
        val account=VkRelayAccount(Memory(),{ error("must not run") },{})
        val raw=JSONObject().put("type","qwdtt").put("hash_source","auto").put("hashes",JSONArray(listOf(hash)))
        val runtime=VkRelayRuntime.prepare(raw,account)
        assertEquals("manual",runtime.getString("hash_source"));assertEquals("auto",raw.getString("hash_source"))
    }
    @Test fun newProfileDefaultsDoNotOverrideImportedManualCalls() {
        val account=VkRelayAccount(Memory());account.setDefaultSource(VkRelayProtocol.HashSource.AUTOMATIC)
        val raw=JSONObject().put("type","qwdtt").put("hashes",JSONArray(listOf(hash)))
        assertEquals("manual",VkRelayRuntime.defaultsForNewQwdtt(raw,account).getString("hash_source"))
        assertEquals("auto",VkRelayRuntime.defaultsForNewQwdtt(JSONObject().put("type","qwdtt"),account).getString("hash_source"))
    }
    @Test fun csqttRejectsWrongHashSizeAndDuplicateCalls() {
        assertThrows(IllegalArgumentException::class.java) { VkRelayProtocol.hash("A".repeat(20),"csqtt") }
        assertThrows(IllegalArgumentException::class.java) { VkRelayProtocol.calls(listOf(hash,hash),"qwdtt") }
        assertEquals(hash,VkRelayProtocol.hash(hash,"wdtt"))
    }
    @Test fun newDeploymentGetsNewSixWhileSameOperationIsIdempotent() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        val first=account.sixCallsFor("deploy:first","qwdtt")
        val second=account.sixCallsFor("deploy:second","qwdtt")
        assertEquals(12,requests);assertTrue(first.intersect(second.toSet()).isEmpty())
        assertEquals(first,account.sixCallsFor("deploy:first","qwdtt"));assertEquals(12,requests)
    }
    @Test fun operationRetryResumesItsPartialGroupAfterBackupRoundTrip() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;if(requests==3) throw java.io.IOException("fixture");JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        assertThrows(java.io.IOException::class.java) { account.sixCallsFor("deploy:retry","qwdtt") }
        val restored=Memory(memory.snapshot());AndroidDeployBackupSettings.validate(restored.snapshot())
        val retry=VkRelayAccount(restored,{ requests++;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        assertEquals(6,retry.sixCallsFor("deploy:retry","qwdtt").size);assertEquals(7,requests)
        assertEquals(2,memory.get("vkCallGroups").getJSONObject("deploy:retry").getJSONArray("hashes").length())
    }
    @Test fun scopedManualCallsNeedNeitherLoginNorGroupMutation() {
        val memory=Memory();val account=VkRelayAccount(memory,{ error("No API") },{})
        assertEquals(listOf("https://vk.ru/call/join/$hash"),account.resolveFor("deploy:manual",listOf(hash),true,"qwdtt"))
        assertEquals(0,memory.get("vkCallGroups").length());assertFalse(account.linked())
    }
    @Test fun differentRuntimeConnectionsDoNotShareAutomaticGroup() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        val raw=JSONObject().put("type","qwdtt").put("hash_source","auto").put("server","192.0.2.1").put("password","fixture")
        val first=VkRelayRuntime.prepareForConnection("connection:first",raw,account)
        val second=VkRelayRuntime.prepareForConnection("connection:second",raw,account)
        assertNotEquals(first.getJSONArray("hashes").toString(),second.getJSONArray("hashes").toString());assertEquals(12,requests)
        VkRelayRuntime.prepareForConnection("connection:first",raw,account);assertEquals(12,requests)
    }
    @Test fun providerCannotReuseOldCallInNewDeployment() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;val n=if(requests<=6) requests else 1;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+n)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600));account.sixCallsFor("deploy:old","qwdtt")
        assertThrows(IllegalStateException::class.java) { account.sixCallsFor("deploy:new","qwdtt") };assertEquals(7,requests)
        assertEquals(0,memory.get("vkCallGroups").getJSONObject("deploy:new").getJSONArray("hashes").length())
    }
    @Test fun anotherOperationsRateLimitDoesNotBlockCompletedGroups() {
        val memory=Memory();var requests=0
        val account=VkRelayAccount(memory,{ requests++;if(requests>6) JSONObject().put("error",JSONObject().put("error_code",29)) else JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+requests)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        val completed=account.sixCallsFor("deploy:complete","qwdtt")
        assertThrows(IllegalStateException::class.java) { account.sixCallsFor("deploy:new","qwdtt") }
        assertEquals(completed,account.sixCallsFor("deploy:complete","qwdtt"));assertEquals(7,requests)
        assertThrows(IllegalStateException::class.java) { account.sixCallsFor("deploy:retry","qwdtt") };assertEquals(7,requests)
    }
    @Test fun deploymentCallPoolsRespectActualAndroidImportLimits() {
        val hashes=(0 until 32).map { n -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { i -> if(i==0) n.toByte() else 0 }) }
        assertEquals(32,VkRelayProtocol.calls(hashes,"qwdtt").size)
        for(kind in listOf("wdtt","csqtt")) {
            assertEquals(6,VkRelayProtocol.calls(hashes.take(6),kind).size)
            assertThrows(IllegalArgumentException::class.java) { VkRelayProtocol.calls(hashes.take(7),kind) }
            val target=AndroidDeployTarget("192.0.2.1",sshPassword="fixture",options=JSONObject().put("relay_kind",kind).put("vk_calls",JSONArray(hashes.take(7))))
            assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validate("swg",target) }
        }
    }
    @Test fun manualProbeReadsNoAccountAndWritesNothing() {
        val persistence=object : VkRelayPersistence {
            override fun get(section: String): JSONObject = error("No account reads")
            override fun update(section: String,change: (JSONObject) -> Unit) = error("No writes")
            override fun remove(section: String) = error("No writes")
        }
        val raw=JSONObject().put("type","qwdtt").put("hash_source","auto").put("hashes",JSONArray(listOf(hash)))
        val ready=VkRelayRuntime.prepareForProbe(raw,VkRelayAccount(persistence,{ error("No API") },{}))
        assertEquals("manual",ready.getString("hash_source"));assertEquals("auto",raw.getString("hash_source"))
    }
    @Test fun missingAndPartialAutoProbeNeverCreateOrMutateCalls() {
        val memory=Memory();val account=VkRelayAccount(memory,{ error("No API") },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        val raw=JSONObject().put("type","qwdtt").put("hash_source","auto")
        val before=memory.snapshot().toString()
        val missing=assertThrows(IllegalStateException::class.java) { VkRelayRuntime.prepareForProbeConnection("connection:probe",raw,account) }
        assertTrue(missing.message.orEmpty().contains("Сначала включите подключение"));assertEquals(before,memory.snapshot().toString())
        memory.update("vkCallGroups") { it.put("connection:probe",JSONObject().put("owner","42").put("kind","qwdtt").put("createdAt",1).put("hashes",JSONArray(listOf(hash)))) }
        val partial=memory.snapshot().toString()
        assertThrows(IllegalStateException::class.java) { VkRelayRuntime.prepareForProbeConnection("connection:probe",raw,account) };assertEquals(partial,memory.snapshot().toString())
    }
    @Test fun completedAutoProbeUsesSameConnectionGroupReadOnly() {
        val memory=Memory();var calls=0
        val account=VkRelayAccount(memory,{ calls++;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+hash+calls)) },{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600))
        val raw=JSONObject().put("type","qwdtt").put("server","192.0.2.1").put("password","fixture").put("hash_source","auto")
        val connection=VkRelayRuntime.prepare(raw,account);val before=memory.snapshot().toString()
        val readOnly=object : VkRelayPersistence {
            override fun get(section: String)=memory.get(section)
            override fun update(section: String,change: (JSONObject) -> Unit)=error("Probe writer")
            override fun remove(section: String)=error("Probe writer")
        }
        val probe=VkRelayRuntime.prepareForProbe(raw,VkRelayAccount(readOnly,{error("Probe API")},{}))
        assertEquals(connection.getJSONArray("hashes").toString(),probe.getJSONArray("hashes").toString());assertEquals(6,calls);assertEquals(before,memory.snapshot().toString())
        memory.update("vk") { it.put("user","another-owner") }
        val changed=memory.snapshot().toString()
        assertThrows(IllegalStateException::class.java) { VkRelayRuntime.prepareForProbe(raw,VkRelayAccount(readOnly,{error("Probe API")},{})) };assertEquals(changed,memory.snapshot().toString())
    }
    @Test fun probeCannotFallBackToDefaultSourceOrUnrelatedCompleteGroup() {
        val memory=Memory();val account=VkRelayAccount(memory,{error("No API")},{})
        account.link(VkRelayProtocol.Authorization("fixture-token","42",3600));account.setDefaultSource(VkRelayProtocol.HashSource.AUTOMATIC)
        memory.update("vkCallGroups") { it.put("connection:other",JSONObject().put("owner","42").put("kind","qwdtt").put("createdAt",1).put("hashes",JSONArray((1..6).map { hash+it }))) }
        val before=memory.snapshot().toString()
        assertThrows(IllegalStateException::class.java) { VkRelayRuntime.prepareForProbeConnection("connection:missing",JSONObject().put("type","qwdtt").put("hash_source","auto"),account) }
        assertThrows(IllegalArgumentException::class.java) { VkRelayRuntime.prepareForProbe(JSONObject().put("type","qwdtt"),account) }
        assertEquals(before,memory.snapshot().toString())
    }
    @Test fun missingOwnerCannotBeLinkedOrAllocateAutomaticGroups() {
        val callback="https://oauth.vk.com/blank.html#access_token=fixture&state=state"
        assertNull(VkRelayProtocol.authorization(callback,"state"))
        assertNull(VkRelayProtocol.authorization(callback+"&user_id=not-a-vk-id","state"))
        val memory=Memory();val account=VkRelayAccount(memory,{error("No API")},{})
        assertThrows(IllegalArgumentException::class.java) { account.link(VkRelayProtocol.Authorization("fixture-token","",3600)) }
        assertEquals(0,memory.snapshot().length())
        memory.update("vk") { it.put("token","fixture-token").put("user","").put("expires",Long.MAX_VALUE) }
        val before=memory.snapshot().toString()
        assertThrows(IllegalStateException::class.java) { account.sixCallsFor("connection:unbound","qwdtt") }
        assertThrows(IllegalStateException::class.java) { account.cachedSixFor("connection:unbound","qwdtt") }
        assertEquals(before,memory.snapshot().toString())
    }
}
