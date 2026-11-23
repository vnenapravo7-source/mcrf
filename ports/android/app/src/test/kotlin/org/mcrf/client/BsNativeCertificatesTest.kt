package org.mcrf.client
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class BsNativeCertificatesTest {
 @Test fun public_roots_are_bounded_deduplicated_and_defensively_copied(){
  val bytes=byteArrayOf(1,2,3);val value=BsNativeCertificates.encode("system",listOf(bytes,bytes.copyOf())){false};bytes.fill(0)
  assertEquals("system",value.getString("store"));assertEquals(1,value.getJSONArray("roots").length());assertEquals("AQID",value.getJSONArray("roots").getString(0))
 }
 @Test fun unsupported_empty_oversized_or_cancelled_stores_do_not_turn_into_defaults(){
  for((store,roots)in listOf("other" to listOf(byteArrayOf(1)),"system" to emptyList(),"mozilla" to listOf(ByteArray(32769)),"system" to List(1025){byteArrayOf(1)}))
   assertThrows(IllegalArgumentException::class.java){BsNativeCertificates.encode(store,roots){false}}
  assertThrows(IllegalStateException::class.java){BsNativeCertificates.encode("system",listOf(byteArrayOf(1))){true}}
 }
}
