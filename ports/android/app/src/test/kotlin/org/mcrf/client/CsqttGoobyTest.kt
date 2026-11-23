package org.mcrf.client
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
class CsqttGoobyTest {
    private fun profile()=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-2.5.3")
        .put("name","Owned fixture").put("server","192.0.2.1").put("server_port",12345).put("password","owned-only")
        .put("hashes",JSONArray().put("A".repeat(43))).put("link_workers",20).put("turn_transport","tcp")
    @Test fun omitted_off_and_udp_do_not_require_or_emit_a_new_runtime_option(){
        for(p in listOf(profile(),profile().put("gooby_enabled",false),profile().put("gooby_enabled",true).put("gooby_strategy","extended").put("turn_transport","udp"))){
            val before=p.toString();val normal=Csqtt25Profile.fromJson("owned",p).carrier!!
            assertFalse(CarrierOptionContract.requiredAll(normal).contains(CsqttGooby.CAPABILITY));assertTrue(CsqttGooby.arguments(normal).isEmpty())
            val runtime=Csqtt25Profile.privateConfig(normal,22001,22002,"u","p","binding");assertFalse(runtime.has("gooby_enabled"));assertFalse(runtime.has("gooby_strategy"));assertEquals(before,p.toString())
        }
    }
    @Test fun every_builtin_strategy_survives_normalization_native_json_and_native_argv(){
        for(mode in CsqttGooby.strategies){val raw=profile().put("gooby_enabled",true).put("gooby_strategy",mode);val before=raw.toString()
            val p=Csqtt25Profile.fromJson("owned",raw).carrier!!;assertTrue(CsqttGooby.active(p));assertTrue(CarrierOptionContract.requiredAll(p).contains(CsqttGooby.CAPABILITY))
            val runtime=Csqtt25Profile.privateConfig(p,22001,22002,"u","p","binding");assertTrue(runtime.getBoolean("gooby_enabled"));assertEquals(mode,runtime.optString("gooby_strategy","current"))
            assertEquals(if(mode=="current")listOf("--gooby")else listOf("--gooby","--gooby-strategy",mode),CsqttGooby.arguments(p));assertEquals(before,raw.toString())
        }
    }
    @Test fun old_module_cannot_silently_ignore_an_enabled_strategy(){
        val p=profile().put("gooby_enabled",true)
        assertTrue(CarrierOptionContract.missing(p){key->if(key==CsqttGooby.CAPABILITY)0 else 1})
        assertFalse(CarrierOptionContract.missing(p){1})
    }
    @Test fun null_coercions_and_unknown_modes_are_rejected_even_while_disabled(){
        for(value in listOf(JSONObject.NULL,"true",1))assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("owned",profile().put("gooby_enabled",value))}
        for(value in listOf(JSONObject.NULL,true,1,"","unknown"))assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("owned",profile().put("gooby_strategy",value))}
    }
    @Test fun automatic_or_legacy_projection_cannot_drop_active_gooby(){
        val p=profile().put("gooby_enabled",true)
        assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("owned",JSONObject(p.toString()).put(CsqttGenerations.SELECTION,CsqttGenerations.AUTO))}
        assertThrows(IllegalArgumentException::class.java){CsqttGenerations.select(p,CsqttGenerations.AUTO)}
        assertThrows(IllegalArgumentException::class.java){CsqttGenerations.select(p,"legacy")}
    }
    @Test fun native_share_uri_round_trips_gooby_without_exporting_a_json_blob(){
        val p=profile().put("gooby_enabled",true).put("gooby_strategy","gentle")
        val entry=Csqtt25Profile.fromJson(p.toString(),p);val uri=OriginalCsqttShare.source(entry);assertTrue(uri.startsWith("csqtt://connect?"))
        val parsed=ServerImport.read(uri).single().carrier!!;assertTrue(CsqttGooby.enabled(parsed));assertEquals("gentle",CsqttGooby.strategy(parsed))
    }
}
