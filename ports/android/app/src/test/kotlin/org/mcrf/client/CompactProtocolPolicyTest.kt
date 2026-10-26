package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactProtocolPolicyTest {
    private fun entry(kind:String,node:JSONObject=JSONObject())=ServerImport.Server(original="owned-$kind",name="Owned $kind",protocol=kind,host="192.0.2.1",port=443,
        outbound=node.put("type",kind).put("server","192.0.2.1").put("server_port",443),endpoint=null,configuration=null)
    @Test fun exactProtocolIsPreservedWithoutMutatingSource(){
        for(kind in CompactProtocolPolicy.types){
            val original=entry(kind,if(kind=="ssh")JSONObject().put("host_key","ssh-ed25519 owned-fixture-public-key")else JSONObject())
            val before=original.outbound!!.toString();val request=CompactProtocolPolicy.request(original)
            assertEquals("protocols",request.kind);assertEquals(kind,request.outbound.getString("type"));assertEquals(before,original.outbound!!.toString())
        }
        assertFalse(CompactProtocolPolicy.eligible(entry("trojan")))
        assertTrue(CompactProtocolPolicy.compatible("protocols",CompactProtocolPolicy.REPOSITORY,CompactProtocolPolicy.COMMIT,1))
        assertFalse(CompactProtocolPolicy.compatible("tlscore",CompactProtocolPolicy.REPOSITORY,CompactProtocolPolicy.COMMIT,1))
    }
    @Test fun localFilesCommandsAndUnverifiedSshAreRejected(){
        for(key in listOf("private_key_path","certificate_path","command","protect_path")){
            assertThrows(CompactProtocolPolicy.UnsafeProfile::class.java){CompactProtocolPolicy.request(entry("tuic",JSONObject().put("tls",JSONObject().put(key,"untrusted"))))}
        }
        assertThrows(CompactProtocolPolicy.UnsafeProfile::class.java){CompactProtocolPolicy.request(entry("ssh"))}
        assertThrows(CompactProtocolPolicy.UnsafeProfile::class.java){CompactProtocolPolicy.request(entry("ssh",JSONObject().put("host_key",JSONArray())))}
    }
    @Test fun serverResolverIsCapturedAndNeverSilentlyDropped(){
        val original=entry("tuic",JSONObject().put("domain_resolver","selected-resolver"))
        assertThrows(IllegalArgumentException::class.java){CompactProtocolPolicy.request(original)}
        val resolver=JSONObject().put("endpoint",JSONObject().put("type","udp").put("server","192.0.2.53"))
        val request=CompactProtocolPolicy.request(original,resolver)
        resolver.getJSONObject("endpoint").put("server","192.0.2.99")
        assertFalse(request.outbound.has("domain_resolver"));assertEquals("192.0.2.53",request.serverDns!!.getJSONObject("endpoint").getString("server"))
        assertTrue(original.outbound!!.has("domain_resolver"))
    }
    @Test fun nativeProxyLayerRetainsCatalogueIdentityAndOriginalProtocolSeparately(){
        val original=ServerImport.read("tuic://00000000-0000-0000-0000-000000000001:private-password@192.0.2.1:443").single()
        val effective=CompactProtocolPolicy.effective(original,12345,"a".repeat(32),"b".repeat(64))
        assertEquals(original.id,effective.id);assertEquals(original.original,effective.original)
        assertEquals("tuic",original.protocol);assertEquals("socks",effective.protocol);assertEquals("127.0.0.1",effective.host)
        assertTrue(SteerProbePlan.supports(effective));assertEquals(5,effective.outbound!!.getString("version").toInt())
        val native=SteerProbePlan.profile(effective)
        assertEquals("socks",ServerImport.read(native).single().protocol)
        assertFalse(native.contains("private-password"))
        assertThrows(IllegalArgumentException::class.java){CompactProtocolPolicy.effective(original,12345,"untrusted","untrusted")}
    }
}
