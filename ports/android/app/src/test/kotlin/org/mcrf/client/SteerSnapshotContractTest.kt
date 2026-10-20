package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import org.mcrf.steerprep.SteerGeoProjection
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Queued whole-P1 batch; no module installation or feature advertisement. */
class SteerSnapshotContractTest {
    private fun geoVarint(value:Long):ByteArray {
        var n=value;val out=java.io.ByteArrayOutputStream()
        while(n>127){out.write((n.toInt() and 127) or 128);n=n ushr 7};out.write(n.toInt());return out.toByteArray()
    }
    private fun geoBlob(field:Int,bytes:ByteArray)=geoVarint(field*8L+2)+geoVarint(bytes.size.toLong())+bytes
    private fun geoInt(field:Int,value:Long)=geoVarint(field*8L)+geoVarint(value)
    private fun geoDomain(type:Long,value:String)=geoInt(1,type)+geoBlob(2,value.toByteArray(Charsets.UTF_8))
    private fun rejectGeoDat(block:()->Unit) {
        try{block();fail("Invalid compact DAT accepted")}
        catch(e:Exception){assertTrue(e is IllegalArgumentException||e is IllegalStateException||e is java.nio.charset.CharacterCodingException)}
    }
    @Test fun selectiveDatKeepsAllDomainTypesLateNamesAndIpv4Ipv6Inversion() {
        val entries=(0L..3L).map {geoBlob(2,geoDomain(it,when(it){0L->"track-";1L->"^video[0-9]+\\.test$";2L->"suffix.test";else->"exact.test"}))}
            .fold(ByteArray(0)){all,part->all+part}
        val owned=entries+geoBlob(1,"OWNED".toByteArray(Charsets.US_ASCII)) // country field need not precede records
        val other=geoBlob(1,"not-selected".toByteArray())+geoBlob(2,byteArrayOf(0x80.toByte())) // opaque unselected payload
        val second=geoBlob(1,"second".toByteArray())+geoBlob(2,geoDomain(2,"two.test"))
        val dat=geoBlob(1,other)+geoBlob(1,owned)+geoBlob(1,second)+geoInt(99,7)
        val view=SteerGeoDat.Dataset(dat,setOf("owned","second"))
        val compact=view.convert("geosite","owned").getJSONArray("rules").getJSONObject(0)
        val legacy=GeoDataConfig.convert(dat,"geosite","owned").getJSONArray("rules").getJSONObject(0)
        for(key in listOf("domain_keyword","domain_regex","domain_suffix","domain"))assertEquals(legacy.getJSONArray(key).toString(),compact.getJSONArray(key).toString())
        assertEquals("two.test",view.convert("geosite","second").getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").getString(0))
        val ip4=geoBlob(1,byteArrayOf(192.toByte(),0,2,129.toByte()))+geoInt(2,24)
        val ip6=ByteArray(16);ip6[0]=0x20;ip6[1]=1;ip6[2]=0x0d;ip6[3]=0xb8.toByte();ip6[15]=127
        val cidr6=geoBlob(1,ip6)+geoInt(2,64)
        val ipDat=geoBlob(1,geoBlob(1,"RU".toByteArray())+geoBlob(2,ip4)+geoBlob(2,cidr6)+geoInt(3,1))
        val ip=SteerGeoDat.convert(ipDat,"geoip","ru").getJSONArray("rules").getJSONObject(0)
        assertTrue(ip.getBoolean("invert"));assertEquals("192.0.2.0/24",ip.getJSONArray("ip_cidr").getString(0))
        ip6[15]=0;assertEquals("${java.net.InetAddress.getByAddress(ip6).hostAddress}/64",ip.getJSONArray("ip_cidr").getString(1))
    }
    @Test fun compactDatRejectsAmbiguityMalformedLengthsEnumsAndSharedBudgetOverflow() {
        val row=geoBlob(1,"owned".toByteArray())+geoBlob(2,geoDomain(2,"owned.test"));val dat=geoBlob(1,row)
        rejectGeoDat {SteerGeoDat.convert(dat+dat,"geosite","owned")}
        rejectGeoDat {SteerGeoDat.convert(dat,"geosite","missing")}
        rejectGeoDat {SteerGeoDat.convert(byteArrayOf(10,127),"geosite","owned")}
        rejectGeoDat {SteerGeoDat.convert(ByteArray(10){0x80.toByte()},"geosite","owned")}
        rejectGeoDat {SteerGeoDat.convert(geoBlob(1,geoBlob(1,"owned".toByteArray())+geoBlob(2,geoDomain(4294967298L,"owned.test"))),"geosite","owned")}
        rejectGeoDat {SteerGeoDat.convert(geoBlob(1,geoBlob(1,"owned".toByteArray())+geoBlob(2,geoDomain(2,"x".repeat(1025)))),"geosite","owned")}
        val invalidText=geoInt(1,2)+geoBlob(2,byteArrayOf(0xc3.toByte(),0x28))
        rejectGeoDat {SteerGeoDat.convert(geoBlob(1,geoBlob(1,"owned".toByteArray())+geoBlob(2,invalidText)),"geosite","owned")}
        val tooManyRegex=(0 until 129).fold(geoBlob(1,"owned".toByteArray())){all,_->all+geoBlob(2,geoDomain(1,"a"))}
        rejectGeoDat {SteerGeoDat.convert(geoBlob(1,tooManyRegex),"geosite","owned")}
        rejectGeoDat {SteerGeoDat.Dataset(dat,setOf("owned"),maxFields=1)}
        val small=SteerGeoDat.Dataset(dat,setOf("owned"),maxOutputBytes=600)
        small.convert("geosite","owned")
        rejectGeoDat {small.convert("geosite","owned")} // one shared output budget, not reset per conversion
        val mapped=ByteArray(16);mapped[10]=(-1).toByte();mapped[11]=(-1).toByte();mapped[12]=1
        rejectGeoDat {SteerGeoDat.convert(geoBlob(1,geoBlob(1,"owned".toByteArray())+geoBlob(2,geoBlob(1,mapped)+geoInt(2,128))),"geoip","owned")}
    }
    @Test fun originCaptureIsReadOnlyAndRejectsSameSizeSourceOrCategoryTampering() {
        fun hash(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject()
            .put("kind","geosite").put("code","owned").put("target","direct"))))
        val plan=SteerGeoProjection.plan(state);val key=SteerGeoPreparation.identity(state,plan)
        val dir=Files.createTempDirectory(java.nio.file.Paths.get(System.getProperty("user.dir")),"mcrf-steer-origin-").toRealPath()
        val root=dir.resolve("mcrf-geodata");val sourceName=hash(GeoDataConfig.value(state).getString("geositeUrl").toByteArray(Charsets.UTF_8))+".dat"
        val source=root.resolve(sourceName)
        // Invented private-index/file fixtures ONLY: this does not prove DAT
        // conversion, explicit UI preparation, worker admission or real Geo data.
        val data=byteArrayOf(1,2,3)
        val category="{\"version\":3,\"rules\":[{\"domain\":[\"owned.test\"]}]}".toByteArray(Charsets.UTF_8)
        val categoryHash=hash(category);val compiled=root.resolve("$categoryHash.json");val index=root.resolve("steer-$key.json")
        val pin=JSONObject().put("selector",0).put("file","$categoryHash.json").put("sha256",categoryHash).put("bytes",category.size).put("kind","geosite").put("code","owned")
        val record=JSONObject().put("version",1).put("identity",key).put("pins",JSONArray().put(pin)).put("sources",JSONArray()
            .put(JSONObject().put("kind","geosite").put("file",sourceName).put("bytes",data.size).put("sha256",hash(data))))
        val recordBytes=record.toString().toByteArray(Charsets.UTF_8)
        try {
            Files.createDirectory(root);Files.write(source,data);Files.write(compiled,category);Files.write(index,recordBytes)
            assertEquals(categoryHash,SteerGeoPreparation.captureFiles(dir,state,plan).getJSONObject(0).getString("sha256"))
            assertArrayEquals(recordBytes,Files.readAllBytes(index));assertArrayEquals(data,Files.readAllBytes(source))
            val time=Files.getLastModifiedTime(source);Files.write(source,byteArrayOf(3,2,1));Files.setLastModifiedTime(source,time)
            try {SteerGeoPreparation.captureFiles(dir,state,plan);fail("Source changed without size/mtime change accepted")}catch(expected:IllegalArgumentException){}
            Files.write(source,data)
            Files.write(compiled,String(category,Charsets.UTF_8).replace("owned","other").toByteArray(Charsets.UTF_8))
            try {SteerGeoPreparation.captureFiles(dir,state,plan);fail("Category content changed under pinned name accepted")}catch(expected:IllegalArgumentException){}
            Files.write(compiled,category)
            pin.put("code","wrong");Files.write(index,record.toString().toByteArray(Charsets.UTF_8))
            try {SteerGeoPreparation.captureFiles(dir,state,plan);fail("Wrong source category binding accepted")}catch(expected:IllegalArgumentException){}
        } finally {
            Files.deleteIfExists(index);Files.deleteIfExists(compiled);Files.deleteIfExists(source);Files.deleteIfExists(root);Files.deleteIfExists(dir)
        }
    }
    @Test fun geoAndNativeAddressHaveIndependentExactDeclarations() {
        val caps=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2).put("geo_rules",true).put("native_address_rules",true)
        assertFalse(SteerSnapshotContract.parse(caps).geoRules);assertFalse(SteerSnapshotContract.parse(caps).nativeAddressRules)
        caps.put("geo_rules","owned_pinned_geo")
        assertTrue(SteerSnapshotContract.parse(caps).geoRules);assertFalse(SteerSnapshotContract.parse(caps).nativeAddressRules)
        caps.put("native_address_rules","owned_pinned_address")
        assertTrue(SteerSnapshotContract.parse(caps).nativeAddressRules)
        assertFalse(SteerSnapshotContract.parse(caps).perFlowRules) // address/model2 never imply original tuple
        caps.put("per_flow_routing",true);assertFalse(SteerSnapshotContract.parse(caps).perFlowRules)
        caps.put("per_flow_routing","original_socket_uid");assertFalse(SteerSnapshotContract.parse(caps).perFlowRules)
        caps.put("per_flow_routing","original_socket_tuple");assertTrue(SteerSnapshotContract.parse(caps).perFlowRules)
        assertFalse(SteerSnapshotContract.parse(caps).perAppRules) // tuple marker never implies actual Android owner
        val required=org.mcrf.steerprep.json.SteerNativeFlowShape.inspect(JSONObject().put("type","logical").put("mode","or").put("invert",true)
            .put("rules",JSONArray().put(JSONObject().put("domain","owned.test")).put(JSONObject().put("package_name","android").put("port",443))))
        assertTrue(required.flow);assertTrue(required.owner)
        assertFalse(org.mcrf.steerprep.json.SteerNativeFlowShape.inspect(JSONObject().put("domain","owned.test")).flow)
        caps.remove("mcrf_snapshot_model")
        assertFalse(SteerSnapshotContract.parse(caps).geoRules);assertFalse(SteerSnapshotContract.parse(caps).nativeAddressRules)
        assertFalse(SteerSnapshotContract.parse(caps).perFlowRules)
    }
    @Test fun proxyFlowRequiresSeparateExactRequestAndOwnerDeclarations() {
        val caps=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2)
            .put("local_proxy","authenticated_http_socks_vpn").put("per_app_routing","original_socket_uid")
            .put("per_flow_routing","original_socket_tuple")
        assertFalse(SteerSnapshotContract.parse(caps).supportsProxyFlow())
        for(value in listOf<Any>(true,2,"original_socket_tuple","original_socket_uid")) {
            caps.put("local_proxy_flow",value)
            assertFalse(SteerSnapshotContract.parse(caps).proxyOriginalRequest)
            assertFalse(SteerSnapshotContract.parse(caps).supportsProxyFlow())
        }
        caps.put("local_proxy_flow","observed_control_request")
        assertTrue(SteerSnapshotContract.parse(caps).supportsProxyFlow())
        for(field in listOf("local_proxy","per_app_routing","per_flow_routing","mcrf_snapshot_model")) {
            val missing=JSONObject(caps.toString());missing.remove(field)
            assertFalse(SteerSnapshotContract.parse(missing).supportsProxyFlow())
        }
    }
    @Test fun ipv4DnsCompatibilityIsNotImpliedByIpv6OrTheModelVersion() {
        val caps=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2).put("ipv6","capture_v6_native")
        assertFalse(SteerSnapshotContract.parse(caps).ipv4Compatibility)
        for(value in listOf<Any>(true,1,"ipv4_only","capture_v6_native")){
            caps.put("ipv4_compatibility",value);assertFalse(SteerSnapshotContract.parse(caps).ipv4Compatibility)
        }
        caps.put("ipv4_compatibility","managed_dns_a_only")
        assertTrue(SteerSnapshotContract.parse(caps).ipv4Compatibility)
        assertTrue(SteerSnapshotContract.parse(caps).ipv6)
        caps.remove("mcrf_snapshot_model");assertFalse(SteerSnapshotContract.parse(caps).ipv4Compatibility)
    }
    private fun geoPlan(): SteerGeoProjection.Plan = SteerGeoProjection.plan(JSONObject().put("rules",JSONArray())
        .put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject()
            .put("kind","geosite").put("code","owned").put("target","direct")))))

    @Test fun geoPinsOnlyResolvedOrdinaryPrivateFilesWithoutCreatingOrRefreshing() {
        val dir=Files.createTempDirectory(java.nio.file.Paths.get(System.getProperty("user.dir")),"mcrf-steer-geo-").toRealPath()
        val root=dir.resolve("mcrf-geodata");val file=root.resolve("a".repeat(64)+".json")
        val plan=geoPlan()
        try {
            Files.createDirectory(root)
            val data="{\"version\":3,\"rules\":[{\"domain\":[\"owned.test\"]}]}".toByteArray(Charsets.UTF_8)
            Files.write(file,data)
            val pins=SteerGeoCacheManifest.captureFiles(dir,plan,mapOf("geo-0" to file.toString()))
            val pin=pins.getJSONObject(0)
            assertEquals(6,pin.length());assertEquals(0,pin.getInt("selector"))
            assertEquals(file.fileName.toString(),pin.getString("file"));assertEquals(data.size.toLong(),pin.getLong("bytes"))
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it.toInt() and 255)}
            assertEquals(sha,pin.getString("sha256"));assertEquals("geosite",pin.getString("kind"));assertEquals("owned",pin.getString("code"))
            assertArrayEquals(data,Files.readAllBytes(file))
            for(paths in listOf(emptyMap(),mapOf("geo-1" to file.toString()),mapOf("geo-0" to dir.resolve(file.fileName).toString()),
                mapOf("geo-0" to root.resolve("../mcrf-geodata/${file.fileName}").toString()))) {
                try {SteerGeoCacheManifest.captureFiles(dir,plan,paths);fail("Unpinned path accepted")}
                catch(expected:IllegalArgumentException){}
            }
            Files.delete(file)
            try {SteerGeoCacheManifest.captureFiles(dir,plan,mapOf("geo-0" to file.toString()));fail("Missing cache accepted")}
            catch(expected:java.nio.file.NoSuchFileException){}
            assertFalse(Files.exists(file))
            Files.createDirectory(file)
            try {SteerGeoCacheManifest.captureFiles(dir,plan,mapOf("geo-0" to file.toString()));fail("Directory accepted")}
            catch(expected:IllegalArgumentException){}
        } finally {
            Files.deleteIfExists(file);Files.deleteIfExists(root);Files.deleteIfExists(dir)
        }
    }

    @Test fun inactiveGeoDoesNotEvenCreateACacheDirectory() {
        val dir=Files.createTempDirectory(java.nio.file.Paths.get(System.getProperty("user.dir")),"mcrf-steer-inactive-geo-").toRealPath()
        try {
            val empty=SteerGeoProjection.plan(JSONObject().put("rules",JSONArray()))
            assertEquals(0,SteerGeoCacheManifest.captureFiles(dir,empty,emptyMap()).length())
            assertFalse(Files.exists(dir.resolve("mcrf-geodata")))
        } finally {Files.deleteIfExists(dir)}
    }

    @Test fun nestedAndScalarManagedGeoDependenciesMatchTheCommonResolver() {
        fun row(id:String,match:JSONObject,enabled:Boolean=true)=JSONObject().put("id",id).put("name",id).put("text","")
            .put("mode","addresses").put("target","direct").put("enabled",enabled).put("nativeMatch",match)
        val match=JSONObject().put("type","logical").put("mode","and").put("rules",JSONArray()
            .put(JSONObject().put("rule_set","mcrf-geo-0"))
            .put(JSONObject().put("domain_suffix",JSONArray().put("owned.test"))))
        val provider=row("provider",match)
        val geo=GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject().put("kind","geosite").put("code","owned")
            .put("target","direct").put("managedRule",true).put("bound",true)))
            .put("providerRules",JSONArray().put(provider))
        val state=RouteRules.defaults().put("geoData",geo);val before=state.toString()
        GeoDataConfig.validate(geo)
        assertEquals(setOf(0),GeoDataConfig.neededSelectors(state))
        assertEquals(GeoDataConfig.neededSelectors(state),SteerGeoProjection.plan(state).neededSelectors)
        assertEquals(before,state.toString())
        provider.put("enabled",false)
        assertTrue(GeoDataConfig.neededSelectors(state).isEmpty())
        assertEquals(GeoDataConfig.neededSelectors(state),SteerGeoProjection.plan(state).neededSelectors)
        val user=row("user",JSONObject(match.toString()))
        state.getJSONArray("rules").put(user)
        assertEquals(setOf(0),GeoDataConfig.neededSelectors(state))
        assertEquals(GeoDataConfig.neededSelectors(state),SteerGeoProjection.plan(state).neededSelectors)
        user.getJSONObject("nativeMatch").getJSONArray("rules").getJSONObject(0).put("rule_set","mcrf-geo-00")
        try {GeoDataConfig.neededSelectors(state);fail("Ambiguous Geo selector accepted")}catch(expected:IllegalArgumentException){}
        try {SteerGeoProjection.plan(state);fail("Ambiguous Geo plan accepted")}catch(expected:IllegalArgumentException){}
        provider.put("enabled","false")
        try {GeoDataConfig.validate(geo);fail("Coerced provider enabled flag accepted")}catch(expected:IllegalArgumentException){}
    }

    @Test fun legacyNeverInfersNewFeatures() {
        val contract=SteerSnapshotContract.parse(JSONObject().put("api_version",2)
            .put("ipv6","capture_v6_native").put("local_proxy","authenticated_http_socks_vpn"))
        assertEquals(SteerSnapshotContract(1,false,false),contract)
    }
    @Test fun newModelRequiresExactMarkerAndPerFeatureDeclarations() {
        val json=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2)
        assertEquals(SteerSnapshotContract(2,false,false),SteerSnapshotContract.parse(json))
        json.put("ipv6","capture_v6_native").put("local_proxy","authenticated_http_socks_vpn")
        assertEquals(SteerSnapshotContract(2,true,true),SteerSnapshotContract.parse(json))
    }
    @Test fun malformedOrUnknownVersionNeverFallsBack() {
        for(value in listOf<Any>(0,3,"2",2.0,4294967298L,JSONObject.NULL)) {
            try {SteerSnapshotContract.parse(JSONObject().put("api_version",2).put("mcrf_snapshot_model",value));fail("Bad model accepted")}
            catch(expected:IllegalArgumentException){}
        }
        try {SteerSnapshotContract.parse(JSONObject().put("api_version",3));fail("Bad ABI accepted")}
        catch(expected:IllegalArgumentException){}
    }
    @Test fun separateDnsNeedsItsOwnExactDeclaration() {
        val json=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2)
            .put("per_rule_dns",true)
        assertFalse(SteerSnapshotContract.parse(json).perRuleDns)
        json.put("per_rule_dns","owned_udp_doh_dot")
        assertTrue(SteerSnapshotContract.parse(json).perRuleDns)
    }
    @Test fun applicationRulesCannotUseAnUnadvertisedOrGuessedOwner() {
        val json=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2)
            .put("per_app_routing","default_process_uid")
        assertFalse(SteerSnapshotContract.parse(json).perAppRules)
        json.put("per_app_routing","original_socket_uid")
        assertTrue(SteerSnapshotContract.parse(json).perAppRules)
    }
    @Test fun packagedListsRequireTheirOwnDeclarationNotJustModel2() {
        val json=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2).put("preset_rules",true)
        assertFalse(SteerSnapshotContract.parse(json).presetRules)
        json.put("preset_rules","owned_srs_address")
        assertTrue(SteerSnapshotContract.parse(json).presetRules)
        json.remove("mcrf_snapshot_model")
        assertFalse(SteerSnapshotContract.parse(json).presetRules)
    }
}
