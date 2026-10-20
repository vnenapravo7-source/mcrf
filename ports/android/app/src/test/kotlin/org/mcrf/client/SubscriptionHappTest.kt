package org.mcrf.client

import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionHappTest {
    private val node="vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Fixture"
    private val url="https://example.com/sub?key=key02"
    private fun response(uri:URI,body:ByteArray,headers:Map<String,String> = emptyMap(),code:Int=200,length:Long=-1L) = object:HttpURLConnection(uri.toURL()) {
        override fun connect()=Unit
        override fun disconnect()=Unit
        override fun usingProxy()=false
        override fun getResponseCode()=code
        override fun getContentLengthLong()=length
        override fun getHeaderField(name:String?)=headers[name]
        override fun getInputStream()=body.inputStream()
    }
    private fun encrypted(text:String):Pair<ByteArray,String> {
        // Public documentation fixture only; production requests have no implicit keys.
        val key="key02:+]%4ij#P\"/".toByteArray(Charsets.US_ASCII)
        val bytes=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,"kkkkkkkkkkkk".toByteArray(Charsets.US_ASCII)))
        }.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encode(bytes.copyOfRange(0,bytes.size-16)) to Base64.getEncoder().encodeToString(bytes.copyOfRange(bytes.size-16,bytes.size))
    }
    private fun decode(source:String=url,body:ByteArray,headers:Map<String,String> = emptyMap(),keys:HappSubscriptionBody.Keys?=null) =
        SubscriptionClient.fetch(source,SubscriptionClient.Opener {response(it,body,headers)},keys=keys)

    @Test fun publicDocumentedKeyRequiresExplicitOriginBoundFactory() {
        val (body,tag)=encrypted(node)
        val fetched=decode(body=body,headers=mapOf("encrypt-tag" to tag),keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))
        assertEquals(node,fetched.servers.single().original)
        assertThrows(IllegalArgumentException::class.java){decode(body=body,headers=mapOf("encrypt-tag" to tag))}
    }
    @Test fun redirectUsesActualResponseKeyIdAndCannotUsePublicKeyOnDifferentOrigin() {
        val (body,tag)=encrypted(node)
        fun fetch(destination:String):SubscriptionClient.Downloaded {
            val opener=SubscriptionClient.Opener { uri -> if(uri.path=="/sub") response(uri,byteArrayOf(),mapOf("Location" to destination),302)
                else response(uri,body,mapOf("encrypt-tag" to tag)) }
            return SubscriptionClient.fetch(url,opener,keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))
        }
        assertEquals(node,fetch("/final?key=key02").servers.single().original)
        assertThrows(IllegalArgumentException::class.java){fetch("/final?key=unknown-private")}
        assertThrows(IllegalArgumentException::class.java){fetch("https://other.example/final?key=key02")}
        assertThrows(IllegalArgumentException::class.java){fetch("http://example.com/final?key=key02")}
    }
    @Test fun decryptErrorNeverMutatesOldSubscriptionAndNeverFallsBackToPlaintext() {
        val data=ConnectionCatalogue("");data.replaceSubscription(url,ServerImport.read(node));val before=data.serialize()
        val (body,tag)=encrypted(node)
        val failures=listOf(body to "badtag",node.toByteArray() to tag,body to Base64.getEncoder().encodeToString(ByteArray(16)))
        failures.forEach { (bytes,header) ->
            assertThrows(IllegalArgumentException::class.java){
                val downloaded=decode(body=bytes,headers=mapOf("encrypt-tag" to header),keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))
                data.refreshFetched(mapOf(url to downloaded),true)
            }
            assertEquals(before,data.serialize())
        }
    }
    @Test fun malformedDecryptedUtf8FailsBeforeParsing() {
        val key="key02:+]%4ij#P\"/".toByteArray(Charsets.US_ASCII)
        val bytes=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,"kkkkkkkkkkkk".toByteArray()))}.doFinal(byteArrayOf(0xc3.toByte(),0x28))
        val header=Base64.getEncoder().encodeToString(bytes.copyOfRange(bytes.size-16,bytes.size))
        val body=Base64.getEncoder().encode(bytes.copyOfRange(0,bytes.size-16))
        val failure=assertThrows(IllegalArgumentException::class.java){decode(body=body,headers=mapOf("encrypt-tag" to header),keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))}
        assertTrue(failure.message.orEmpty().contains("UTF-8"))
    }
    @Test fun ciphertextWireBytesRemainBoundedBeforeDecryption() {
        val (body,tag)=encrypted(node)
        val opener=SubscriptionClient.Opener {response(it,body,mapOf("encrypt-tag" to tag),length=ServerImport.MAX_BYTES+1L)}
        val error=assertThrows(IllegalArgumentException::class.java){SubscriptionClient.fetch(url,opener,keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))}
        assertTrue(error.message.orEmpty().contains("4 МБ"))
        val large=ByteArray(ServerImport.MAX_BYTES+1){'A'.code.toByte()}
        assertThrows(IllegalArgumentException::class.java){decode(body=large,headers=mapOf("encrypt-tag" to tag),keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))}
    }
    @Test fun decodedBodyMetadataAndHeadersRoundTripWithoutExecutingProviderFlags() {
        val document="#subscription-userinfo: total=123; download=10; expire=2000000000\n#profile-device-limit: 2\n#profile-title: Body\n#hide-settings: true\n#subscription-always-hwid-enable: true\n$node"
        val title=Base64.getEncoder().encodeToString("Fixture title".toByteArray())
        val (body,tag)=encrypted(document)
        val received=decode(body=body,headers=mapOf("encrypt-tag" to tag,"profile-title" to "base64:$title","subscription-userinfo" to "total=456; expire=2100000000"),keys=HappSubscriptionBody.publicTestKeysFor(URI(url)))
        assertEquals(456L,received.metadata.getLong("total"));assertEquals(2100000000L,received.metadata.getLong("expire"))
        assertEquals(2,received.metadata.getInt("deviceLimit"));assertEquals("Fixture title",received.metadata.getString("title"))
        assertTrue(received.metadata.getBoolean("providerRequestsHwid"));assertTrue(received.metadata.getBoolean("providerHidesSettings"))
        val data=ConnectionCatalogue("");data.replaceSubscription(url,received.servers,received.metadata)
        assertEquals("Fixture title",ConnectionCatalogue(data.serialize()).subscriptions().single().getJSONObject("metadata").getString("title"))
        assertFalse(SubscriptionOptions().sendDeviceHeaders)
        val wrapped=Base64.getEncoder().encode(document.toByteArray())
        assertEquals(123L,decode(body=wrapped).metadata.getLong("total"))
    }
}
