package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.function.Consumer

/** Coordinator-only fixture: uses isolated file roots/preferences and fake values, no VK/SSH/network. */
object AndroidDeployDeviceChecks {
    @JvmStatic fun run(base: Context,temporary: File,passed: Consumer<String>) {
        val preferenceNames=mutableSetOf<String>()
        fun isolated(name: String): Context {
            val root=File(temporary,"vk-deploy-$name-${UUID.randomUUID()}")
            check(root.canonicalFile.parentFile==temporary.canonicalFile && root.mkdir())
            val prefix="mcrf-vk-deploy-fixture-${UUID.randomUUID()}-"
            return object : ContextWrapper(base) {
                override fun getFilesDir()=root
                override fun getApplicationContext(): Context=this
                override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
                    val scoped=prefix+name;preferenceNames.add(scoped);return base.getSharedPreferences(scoped,mode)
                }
            }
        }
        fun verify(value: Boolean,message: String) { check(value) { message };passed.accept(message) }
        fun same(a: Any?,b: Any?): Boolean = when {
            a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet().let { keys -> keys==b.keys().asSequence().toSet() && keys.all { same(a.get(it),b.get(it)) } }
            a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all { same(a.get(it),b.get(it)) }
            a is Number && b is Number -> a.toString()==b.toString()
            else -> a==b
        }
        val source=isolated("source");val destination=isolated("destination")
        val password="Fixture-only-encrypted-backup".toCharArray()
        try {
            AndroidDeploySshPreflight.verify()
            passed.accept("Configured SSH reflection primitives instantiate without any connection/account/server")
            for(type in listOf(com.jcraft.jsch.KeyPair.RSA,com.jcraft.jsch.KeyPair.ECDSA)) {
                val pair=com.jcraft.jsch.KeyPair.genKeyPair(com.jcraft.jsch.JSch(),type,if(type==com.jcraft.jsch.KeyPair.RSA)2048 else 256)
                try {
                    val encoded=java.io.ByteArrayOutputStream();pair.writePrivateKey(encoded,"fixture-key-pass".toByteArray())
                    AndroidDeploySshPreflight.verifyPrivateKey(encoded.toString("UTF-8"),"fixture-key-pass")
                    var rejected=false
                    try { AndroidDeploySshPreflight.verifyPrivateKey(encoded.toString("UTF-8"),"wrong") } catch(_: IllegalArgumentException) { rejected=true }
                    check(rejected)
                    val loaded=com.jcraft.jsch.KeyPair.load(com.jcraft.jsch.JSch(),encoded.toByteArray(),null)
                    try {
                        check(loaded.decrypt("fixture-key-pass"))
                        val algorithm=if(type==com.jcraft.jsch.KeyPair.RSA)"rsa-sha2-256" else "ecdsa-sha2-nistp256"
                        val payload="fixture-key-check".toByteArray()
                        val signature=loaded.getSignature(payload,algorithm)
                        val verifier=loaded.getVerifier(algorithm);verifier.update(payload);check(verifier.verify(signature))
                        passed.accept("Fixture SSH $algorithm encrypted key and signature verified locally; wrong password rejected")
                    } finally { loaded.dispose() }
                } finally { pair.dispose() }
            }
            verify(AndroidDeployClipboard.sensitive("fixture-clipboard-secret").description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")==true,"Deployment clipboard marks credentials/links sensitive without copying into user clipboard")
            val proxyClip=AndroidDeployClipboard.sensitive("fixture-proxy-secret","MCRF · SOCKS5")
            verify(proxyClip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")==true&&proxyClip.description.label=="MCRF · SOCKS5"&&proxyClip.getItemAt(0).text=="fixture-proxy-secret","Proxy clipboard preserves label/value and sensitive flag without touching user clipboard")
            AndroidBackup.registerSection(AndroidDeployBackupSettings.SECTION,AndroidDeployBackupSettings::validate)
            val store=AndroidDeployStore(source)
            var requests=0
            val account=VkRelayAccount(store,{ requests++;JSONObject().put("response",JSONObject().put("join_link","https://vk.ru/call/join/"+"A".repeat(43)+requests)) },{})
            account.link(VkRelayProtocol.Authorization("fixture-vk-token","42",3600))
            account.setDefaultSource(VkRelayProtocol.HashSource.AUTOMATIC)
            account.setManualDefaults(listOf("B".repeat(43)))
            val operationId="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val groupId="deploy:$operationId"
            val firstCalls=account.sixCallsFor(groupId,"qwdtt")
            val secondCalls=account.sixCallsFor("deploy:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","qwdtt")
            verify(firstCalls.intersect(secondCalls.toSet()).isEmpty(),"Two VK deployment groups never reuse calls")
            store.update("sshTrust") { it.put("192.0.2.1:22","SHA256:"+"A".repeat(43)) }
            store.update("deployDrafts") { it.put("3xui",JSONObject().put("sshPassword","fixture-ssh-secret").put("privateKey","fixture-private-key").put("options",JSONObject().put("tcp",true).put("tcp_port","12325"))) }
            store.update("deployResults") { it.put("192.0.2.1:22/3xui",JSONObject().put("panel_url","https://192.0.2.1:8443/").put("ready",true).put("username","fixture").put("password","fixture-panel-secret").put("certificate_sha256","a".repeat(64)).put("connections",JSONArray())) }
            val target=AndroidDeployTarget("192.0.2.1",sshPassword="fixture-ssh-secret",operationId=operationId,
                options=JSONObject().put("automatic_calls",true).put("vk_call_group",groupId).put("vk_calls",JSONArray(firstCalls)))
            val result=AndroidDeployHistory.record(JSONObject().put("panel_url","https://192.0.2.1:8443/").put("ready",true).put("username","fixture").put("password","fixture-panel-secret").put("certificate_sha256","a".repeat(64)).put("connections",JSONArray()),target,"swg")
            store.update("deployResults") { it.put(AndroidDeployHistory.resultKey(target,"swg"),result) }
            val expected=store.exportForEncryptedBackup()
            verify(requests==12,"VK fixture: exactly six calls per new deployment, no real account/API")
            val encryptedFile=File(source.filesDir,"preferences.enc").readBytes().toString(Charsets.ISO_8859_1)
            verify(listOf("fixture-vk-token","fixture-ssh-secret","fixture-private-key","fixture-panel-secret").none { encryptedFile.contains(it) },"VK/deploy Keystore file contains no plaintext fixture credentials")
            verify(JSONObject(ProfileStore(source,"preferences.enc").read()).has("androidDeploy"),"VK/deploy settings use existing encrypted preferences generation")
            val bytes=AndroidBackup.export(source,password)
            try { AndroidBackup.restore(destination,bytes,password) } finally { bytes.fill(0) }
            val restored=AndroidDeployStore(destination).exportForEncryptedBackup()
            verify(same(expected,restored),"VK/deploy encrypted backup preserves token, hashes, defaults, SSH secrets/trust and results")
            val restoredAccount=VkRelayAccount(AndroidDeployStore(destination),{ error("Cached calls must not invoke VK") },{})
            verify(restoredAccount.sixCallsFor(groupId,"qwdtt")==firstCalls,"Restored deployment retains precisely its own six calls without external request")
            verify(restored.getJSONObject("deployResults").getJSONObject(AndroidDeployHistory.resultKey(target,"swg")).getString("vk_call_group")==groupId,"Encrypted deployment result preserves its call-group binding")
            val manual=restoredAccount.manualDefaults()
            restoredAccount.unlink()
            verify(!restoredAccount.linked() && restoredAccount.manualDefaults()==manual && restoredAccount.defaultSource()==VkRelayProtocol.HashSource.MANUAL,"Unlink clears account and restores manual default without losing hashes")
        } finally {
            password.fill('\u0000')
            // Only preferences created by this helper are removed; coordinator owns its temporary file tree.
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
