package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
class BsNativeContractTest {
 @Test fun legacy_fd_capability_does_not_admit_251_and_declared_generations_are_strict(){
  assertFalse(BsNativeContract.profileCapability(1,null,"swg-panel-2.5.1"))
  assertTrue(BsNativeContract.profileCapability(2,null,"swg-panel-2.5.1"))
  assertTrue(BsNativeContract.profileCapability(1,null,"swg-panel-2.5.3"))
  assertTrue(BsNativeContract.profileCapability(2,"2.5.1","swg-panel-2.5.1"))
  assertFalse(BsNativeContract.profileCapability(2,"2.5.1","swg-panel-2.5.3"))
  for(modes in listOf("","2.5.1,2.5.1","2.5.1,unknown","2.5.1, 2.5.3"))assertFalse(BsNativeContract.profileCapability(2,modes,"swg-panel-2.5.1"))
 }
 @Test fun signed_metadata_cannot_advertise_old_native_code_without_protocol_markers(){
  val text=BsNativeContract.markers.joinToString("\u0000")
  assertTrue(BsNativeContract.hasMarkers(text.byteInputStream(Charsets.ISO_8859_1)))
  for(missing in BsNativeContract.markers)assertFalse(BsNativeContract.hasMarkers(BsNativeContract.markers.filter {it!=missing}.joinToString("\u0000").byteInputStream()))
  val split="x".repeat(65530)+text;assertTrue(BsNativeContract.hasMarkers(split.byteInputStream()))
 }
 @Test fun automatic_admission_checks_every_concrete_underlay_without_rewriting_saved_profile(){
  val original=ServerImport.read("csqtt://connect?v=2&host=fixture.invalid&peer=46000&password=fixture-only&hashes=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").single().carrier!!
  val auto=CsqttAutoDetection.reset(original)
  val saved=auto.toString()
  assertTrue(BsNativeContract.allowsVersionDetection(auto))
  assertFalse(BsNativeContract.supportedProfile(auto))
  val seen=mutableListOf<CsqttAutoDetection.Target>()
  assertTrue(BsNativeContract.detectionRouter(auto){concrete->
   assertFalse(CsqttAutoDetection.negotiates(concrete))
   assertEquals(auto.getString("server"),concrete.getString("server"))
   assertEquals(auto.getInt("server_port"),concrete.getInt("server_port"))
   seen+=CsqttAutoDetection.plan(concrete,null).single()
   true
  })
  assertEquals(CsqttAutoDetection.order,seen)
  assertFalse(BsNativeContract.detectionRouter(auto){CsqttGenerations.family(it).id!="2.0"})
  assertEquals(saved,auto.toString())
  val token=org.json.JSONObject(saved).put("vk_hash_mode","auto_js").put("vk_token","fixture-token")
  assertFalse(BsNativeContract.allowsVersionDetection(token))
  assertFalse(BsNativeContract.detectionRouter(token){error("unsupported profile reached transport admission")})
 }
}
