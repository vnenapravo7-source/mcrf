package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class AndroidDeployTest {
    @org.junit.Test fun sshPolicyUsesOnlyAvailableBaseJceAlgorithmsWithoutOptionalProviders() {
        val session=com.jcraft.jsch.JSch().getSession("fixture","fixture.invalid",22)
        AndroidDeploySshAlgorithms.configure(session)
        for((key,value) in AndroidDeploySshAlgorithms.sessionConfig) org.junit.Assert.assertEquals(value,session.getConfig(key))
        for(name in AndroidDeploySshAlgorithms.networkPrimitives+AndroidDeploySshAlgorithms.privateKeyPrimitives) {
            val type=com.jcraft.jsch.JSch.getConfig(name)
            org.junit.Assert.assertFalse(type.contains(".bc.") || type.contains(".jgss.") || type.contains(".jzlib."))
            org.junit.Assert.assertNotNull(Class.forName(type).getDeclaredConstructor())
        }
        org.junit.Assert.assertFalse(session.getConfig("kex").contains("curve25519"))
        org.junit.Assert.assertFalse(session.getConfig("server_host_key").contains("ssh-ed25519"))
        org.junit.Assert.assertFalse(session.getConfig("cipher.c2s").contains("cbc"))
        org.junit.Assert.assertFalse(session.getConfig("mac.c2s").contains("sha1"))
    }
    @org.junit.Test fun rsaAndEcdsaFixtureKeysDecryptAndSignWithSha2BeforeNetwork() {
        for(type in listOf(com.jcraft.jsch.KeyPair.RSA,com.jcraft.jsch.KeyPair.ECDSA)) {
            val jsch=com.jcraft.jsch.JSch()
            val pair=com.jcraft.jsch.KeyPair.genKeyPair(jsch,type,if(type==com.jcraft.jsch.KeyPair.RSA)2048 else 256)
            try {
                val out=java.io.ByteArrayOutputStream();pair.writePrivateKey(out,"fixture-pass".toByteArray())
                AndroidDeploySshPreflight.verifyPrivateKey(out.toString("UTF-8"),"fixture-pass")
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { AndroidDeploySshPreflight.verifyPrivateKey(out.toString("UTF-8"),"wrong") }
                val loaded=com.jcraft.jsch.KeyPair.load(jsch,out.toByteArray(),null)
                try {
                    org.junit.Assert.assertTrue(loaded.decrypt("fixture-pass"))
                    val algorithm=if(type==com.jcraft.jsch.KeyPair.RSA)"rsa-sha2-256" else "ecdsa-sha2-nistp256"
                    val payload="fixture-payload".toByteArray();val signature=loaded.getSignature(payload,algorithm)
                    val verifier=loaded.getVerifier(algorithm);verifier.update(payload)
                    org.junit.Assert.assertTrue(verifier.verify(signature))
                } finally { loaded.dispose() }
            } finally { pair.dispose() }
        }
        AndroidDeploySshPreflight.verifyPrivateKey("","")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { AndroidDeploySshPreflight.verifyPrivateKey("not a key","") }
    }
    @Test fun configuredSshReflectionPrimitivesExistBeforeNetworkWork() { AndroidDeploySshPreflight.verify() }
    private fun target(options: JSONObject=JSONObject())=AndroidDeployTarget("192.0.2.1",sshPassword="fixture-ssh-password",options=options)
    @Test fun tcpGrpcDefaultPlanValidates() { AndroidDeployPlan.validate("3xui",target());assertEquals(15931,AndroidDeployPlan.normalizedOptions(target()).getInt("grpc_port")) }
    @Test fun portCollisionRejectedBeforeAnySsh() {
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validate("3xui",target(JSONObject().put("tcp_port",8443))) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validate("3xui",target(JSONObject().put("tcp_port",12325).put("grpc_port",12325))) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validate("3xui",target(JSONObject().put("tcp_port",12325.5))) }
    }
    @Test fun numericUiStringsBecomeJsonNumbersForRemoteApi() {
        val o=JSONObject().put("tcp_port","12325").put("grpc_port","15931")
        AndroidDeployPlan.validate("3xui",target(o));assertTrue(AndroidDeployPlan.normalizedOptions(target(o)).get("tcp_port") is Int)
    }
    @Test fun unsafeHostAndDomainCannotReachInstaller() {
        for(value in listOf("example.org/path","user@example.org","example.org;touch /tmp/x","example.org\n","example.org?x=1","example.org:443")) assertFalse(value,AndroidDeployPlan.host(value))
        assertTrue(AndroidDeployPlan.host("2001:db8::1"));assertTrue(AndroidDeployPlan.host("server.example"))
    }
    @Test fun targetSnapshotIsIndependentOfUiMutations() {
        val raw=target(JSONObject().put("tcp_port",12325));val snapshot=raw.snapshot();raw.options.put("tcp_port",8443)
        assertEquals(12325,snapshot.options.getInt("tcp_port"));assertFalse(raw.toString().contains("fixture-ssh-password"))
    }
    @Test fun swgRequiresManualOrExplicitAutomaticCalls() {
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validate("swg",target()) }
        AndroidDeployPlan.validate("swg",target(JSONObject().put("automatic_calls",true)))
        AndroidDeployPlan.validate("swg",target(JSONObject().put("vk_calls",JSONArray(listOf("A".repeat(43))))))
    }
    @Test fun shellValuesAreQuotedAndDirectoriesScoped() {
        assertEquals("'a'\"'\"'b'",AndroidDeployPlan.quote("a'b"))
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployScripts.build(AndroidDeployRecipe.all.first(),target(),"/tmp") }
        val script=AndroidDeployScripts.build(AndroidDeployRecipe.all.first(),target(),"/tmp/mcrf-deploy-"+"a".repeat(32))
        assertTrue(script.contains("systemctl is-active --quiet x-ui"));assertTrue(script.contains("MCRF_RESULT|"));assertTrue(script.contains("configure_initial"))
    }
    @Test fun malformedInstallerCannotRunUnpinnedOpenflux() {
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployScripts.pin(AndroidDeployRecipe.all.first { it.id=="openflux" },"curl latest | bash") }
    }
    private fun result()=JSONObject().put("panel_url","https://192.0.2.1:8443/").put("ready",true).put("username","fixture").put("password","fixture-password").put("certificate_sha256","a".repeat(64)).put("connections",JSONArray())
    @Test fun successRequiresActualReadyAddressAndCertificate() {
        AndroidDeployPlan.validateResult(result(),target())
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validateResult(result().put("ready",false),target()) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validateResult(result().put("panel_url","https://evil.example:8443/"),target()) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validateResult(result().put("certificate_sha256",""),target()) }
    }
    @Test fun provisioningCannotPretendCompleteWithoutLinks() {
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployPlan.validateResult(result().put("provision_requested",true).put("provision_complete",true),target()) }
        AndroidDeployPlan.validateResult(result().put("provision_requested",true).put("provision_complete",false).put("provision_error","fixture"),target())
    }
    @Test fun backupRejectsUnknownKeysAndForgedSuccess() {
        AndroidDeployBackupSettings.validate(JSONObject().put("vkSettings",JSONObject().put("source","MANUAL")))
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployBackupSettings.validate(JSONObject().put("arbitrary",JSONObject())) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployBackupSettings.validate(JSONObject().put("vkSettings",JSONObject().put("manual","not-an-array"))) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployBackupSettings.validate(JSONObject().put("deployResults",JSONObject().put("fixture/3xui",result().put("ready",false)))) }
    }
    @Test fun validEncryptedBackupSectionRoundTripsAllCredentialsAndResults() {
        val data=JSONObject().put("vk",JSONObject().put("token","fixture-token").put("user","42").put("expires",Long.MAX_VALUE))
            .put("sshTrust",JSONObject().put("192.0.2.1:22","SHA256:"+"A".repeat(43)))
            .put("deployDrafts",JSONObject().put("3xui",JSONObject().put("sshPassword","fixture-secret").put("options",JSONObject().put("tcp",true).put("tcp_port","12325"))))
            .put("deployResults",JSONObject().put("192.0.2.1:22/3xui",result()))
        AndroidDeployBackupSettings.validate(data)
        val copy=JSONObject(data.toString());AndroidDeployBackupSettings.validate(copy)
        assertEquals("fixture-secret",copy.getJSONObject("deployDrafts").getJSONObject("3xui").getString("sshPassword"))
        assertEquals("fixture-token",copy.getJSONObject("vk").getString("token"))
    }
    @Test fun staleTrustUiCannotApproveAnotherOperation() {
        val first=AndroidDeployTrust("192.0.2.1:22","SHA256:"+"A".repeat(43))
        val second=AndroidDeployTrust("192.0.2.2:22","SHA256:"+"B".repeat(43))
        assertFalse(second.approve(first.id,true));assertTrue(second.approve(second.id,false));assertFalse(second.await(10))
        assertTrue(first.approve(first.id,true));assertTrue(first.await(10));assertFalse(first.approve(first.id,false))
    }
    @Test fun missingTrustReplyTimesOutAndLateApprovalIsRejected() {
        val request=AndroidDeployTrust("fixture:22","SHA256:"+"A".repeat(43))
        assertFalse(request.await(1));assertFalse(request.approve(request.id,true))
    }
    @Test fun trustRequestSurvivesUiDetachWithoutRepeatingApproval() {
        val request=AndroidDeployTrust("fixture:22","SHA256:"+"A".repeat(43))
        val waiting=java.util.concurrent.CompletableFuture.supplyAsync { request.await(1000) }
        // No Activity or UI instance exists while the backend waits; a later observer resolves it.
        assertTrue(request.approve(request.id,true));assertTrue(waiting.get(1,java.util.concurrent.TimeUnit.SECONDS))
    }
    @Test fun boundedInstallerResultRejectsDuplicateAndOversizedMarkers() {
        val decoded=AndroidDeployOutput()
        assertFalse(decoded.accept("MCRF_STEP|Fixture stage"))
        val encoded=java.util.Base64.getEncoder().encodeToString(result().toString().toByteArray())
        assertTrue(decoded.accept("MCRF_RESULT|$encoded"));assertTrue(decoded.result().getBoolean("ready"))
        assertThrows(IllegalArgumentException::class.java) { decoded.accept("MCRF_RESULT|$encoded") }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployOutput().accept("MCRF_RESULT|"+"A".repeat(700_000)) }
        assertThrows(IllegalStateException::class.java) { AndroidDeployOutput().result() }
    }
    @Test fun malformedInstallerResultDoesNotExposeDecodedSecrets() {
        val output=AndroidDeployOutput()
        output.accept("MCRF_RESULT|"+java.util.Base64.getEncoder().encodeToString("fixture-provider-secret".toByteArray()))
        val error=assertThrows(IllegalArgumentException::class.java) { output.result() }
        assertFalse(error.message.orEmpty().contains("fixture-provider-secret"))
    }
    @Test fun historyRetainsMultipleDeploymentsAndLegacyResults() {
        val first=target().copy(operationId="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val second=target().copy(operationId="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val records=JSONObject().put("192.0.2.1:22/3xui",result())
            .put(AndroidDeployHistory.resultKey(first,"3xui"),AndroidDeployHistory.record(result(),first,"3xui").put("created_at",1))
            .put(AndroidDeployHistory.resultKey(second,"3xui"),AndroidDeployHistory.record(result(),second,"3xui").put("created_at",2))
        assertEquals(3,AndroidDeployHistory.entries(records).size)
        assertEquals(second.operationId,AndroidDeployHistory.latest(records,"192.0.2.1","22","3xui")!!.getString("operation_id"))
        AndroidDeployBackupSettings.validate(JSONObject().put("deployResults",records))
    }
    @Test fun deploymentResultAndBackupBindExactlyItsOwnSixCallGroup() {
        val id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";val group="deploy:$id"
        val hashes=(1..6).map { "A".repeat(43)+it };val calls=hashes.map { "https://vk.ru/call/join/$it" }
        val target=target(JSONObject().put("automatic_calls",true).put("vk_call_group",group).put("vk_calls",JSONArray(calls))).copy(operationId=id)
        val record=AndroidDeployHistory.record(result(),target,"swg")
        val data=JSONObject().put("vkCallGroups",JSONObject().put(group,JSONObject().put("owner","42").put("kind","qwdtt").put("createdAt",1).put("hashes",JSONArray(hashes))))
            .put("deployResults",JSONObject().put(AndroidDeployHistory.resultKey(target,"swg"),record))
        AndroidDeployBackupSettings.validate(data);AndroidDeployBackupSettings.validate(JSONObject(data.toString()))
        assertEquals(group,record.getString("vk_call_group"));assertEquals(6,record.getJSONArray("vk_calls").length())
        record.getJSONArray("vk_calls").put(0,"https://vk.ru/call/join/"+"B".repeat(43))
        assertThrows(IllegalArgumentException::class.java) { AndroidDeployBackupSettings.validate(data) }
    }
}
