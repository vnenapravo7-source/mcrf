package org.mcrf.client
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
class BsPlanTest {
 @Test fun challengeRetirementRunsOnceForReplyCancelAndExpiry(){
   val retired=mutableListOf<String>()
   val reply=CarrierChallenge.register("reply","https://api.vk.ru/captcha",{},retired::add)
   CarrierChallenge.respond(reply,"valid-proof");CarrierChallenge.remove(reply)
   val cancelled=CarrierChallenge.register("cancel","https://api.vk.ru/captcha",{},retired::add)
   CarrierChallenge.remove(cancelled);CarrierChallenge.remove(cancelled)
   val expired=CarrierChallenge.register("expiry","https://api.vk.ru/captcha",{},retired::add)
   CarrierChallenge.expire(requireNotNull(CarrierChallenge.get(expired)).created+150_000_000_001L)
   CarrierChallenge.respond(expired,"late-proof")
   assertEquals(listOf(reply,cancelled,expired),retired)
 }
 @Test fun retiredAndExpiredCaptchaCannotDeliverLateProof(){
   var replies=0
   val retired=CarrierChallenge.register("old","https://api.vk.ru/captcha"){replies++}
   CarrierChallenge.remove(retired);CarrierChallenge.respond(retired,"late-proof")
   val expired=CarrierChallenge.register("expired","https://api.vk.ru/captcha"){replies++}
   val created=requireNotNull(CarrierChallenge.get(expired)).created
   CarrierChallenge.expire(created+150_000_000_001L)
   assertNull(CarrierChallenge.get(expired));CarrierChallenge.respond(expired,"late-proof")
   assertEquals(0,replies)
   val active=CarrierChallenge.register("active","https://api.vk.ru/captcha"){replies++}
   CarrierChallenge.respond(active,"valid-proof");CarrierChallenge.respond(active,"duplicate-proof")
   assertEquals(1,replies)
 }
 private fun swg(type:String)=ServerImport.read(SwgProfile.share(JSONObject().put("type",type).put("server","fixture.invalid").put("server_port",56000).put("password","fixture-secret").put("hashes",JSONArray().put("fixture-call-0123456789abcdef")))).single()
 private fun flux()=ServerImport.read(OpenFluxProfile.share(JSONObject().put("type","openflux").put("secret","fixture encryption secret").put("negotiate",true).put("transports",JSONArray().put(JSONObject().put("type","direct").put("dial","127.0.0.1:24001"))))).single()
 @Test fun exactPriorityAndNoFasterProtocolReplacement(){
   val entries=listOf(flux(),swg("wdtt"),swg("csqtt"),swg("qwdtt"));val plan=BsPlan.build(entries,60,50,"Cloudflare")
   assertEquals(AutoSelection.ENGINE_PRIORITY.filter {it!=S3BypassProfile.KIND},BsPlan.candidates(plan.getJSONObject("mcrf_bs")).map { it.getJSONObject("profile").getString("type") })
   assertEquals("csqtt",plan.getJSONObject("mcrf_carrier").getString("type"))
   val runtime=ProfileConfig.build(plan.toString(),false,10808);assertFalse(runtime.has("mcrf_bs"));assertFalse(runtime.has("mcrf_carrier"))
   assertEquals("socks",runtime.getJSONArray("outbounds").getJSONObject(1).getString("type"))
 }
 @Test fun refreshOnlyReplacesMarkedPoolWithoutMutatingLiveBridgeOrSource(){
   val first=swg("csqtt");val second=swg("qwdtt")
   val source=BsPlan.build(listOf(first),60,50,"Cloudflare")
   source.put("fixtureRoute","unchanged")
   val bridge=source.getJSONArray("outbounds").toString()
   val fresh=BsPlan.refresh(source,listOf(second),120)
   assertEquals(bridge,fresh.getJSONArray("outbounds").toString())
   assertEquals("unchanged",fresh.getString("fixtureRoute"))
   assertEquals(first.id,BsPlan.candidates(source.getJSONObject("mcrf_bs")).single().getString("id"))
   assertEquals(second.id,BsPlan.candidates(fresh.getJSONObject("mcrf_bs")).single().getString("id"))
   assertEquals(120,fresh.getJSONObject("mcrf_bs").getInt("interval"))
   try{BsPlan.refresh(source,emptyList(),60);fail()}catch(_:IllegalArgumentException){}
 }
 @Test fun selectedCarrierPersistsAndEmptyPoolRefuses(){
   val entry=swg("csqtt");val catalogue=ConnectionCatalogue("");catalogue.add(entry.original);catalogue.setAuto(entry.id,true)
   assertEquals(setOf(entry.id),ConnectionCatalogue(catalogue.serialize()).autoIds())
   assertTrue(AutoSelection.eligible(entry,AutoSelection.BS_ENGINES));assertFalse(AutoSelection.eligible(entry,AutoSelection.LATENCY))
   try{BsPlan.build(emptyList(),60,50,"Cloudflare");fail()}catch(_:IllegalArgumentException){}
 }
 @Test fun trafficTotalsSurviveHandoverButNotANewSession(){
   TrafficState.reset("first");TrafficState.update(100,200,1000);TrafficState.handover("second");TrafficState.update(30,40,2000)
   assertEquals(130,TrafficState.current.sent);assertEquals(240,TrafficState.current.received);assertEquals("second",TrafficState.current.server)
   assertFalse(TrafficState.notificationTraffic(TrafficState.current).contains("/с"));TrafficState.reset("third");assertEquals(0,TrafficState.current.sent)
 }
 @Test fun challengeMessagesAreBoundedOriginCheckedAndRequestScoped(){
   assertEquals("",CarrierChallenge.fields("CAPTCHA_SOLVE|manual|https://api.vk.ru/captcha?fixture=1|session")!!.first)
   assertEquals("1-2",CarrierChallenge.fields("CAPTCHA_SOLVE|1-2|manual|https://api.vk.com/captcha?fixture=1|session")!!.first)
   assertNull(CarrierChallenge.fields("CAPTCHA_SOLVE|auto|http://api.vk.ru/|session"));assertNull(CarrierChallenge.fields("CAPTCHA_SOLVE|auto|https://evil.invalid/|session"))
   var proof="";val id=CarrierChallenge.register("1-2","https://api.vk.ru/captcha") {proof=it}
   CarrierChallenge.respond(id,"bad\ncommand");assertEquals("",proof);CarrierChallenge.respond(id,"fixture-proof");assertEquals("fixture-proof",proof)
   assertNull(CarrierChallenge.get(id));assertEquals("CAPTCHA_RESULT|1-2|fixture-proof\n",CarrierChallenge.command("1-2",proof))
 }
}
