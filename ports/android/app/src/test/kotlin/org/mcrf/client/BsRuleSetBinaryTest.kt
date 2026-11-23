package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class BsRuleSetBinaryTest {
    private fun references()=JSONArray(javaClass.getResourceAsStream("/bs-srs-reference.json")!!.bufferedReader().use {it.readText()})
    private val scalar=setOf("version","type","mode","invert","network_is_expensive","network_is_constrained")
    private fun normalized(value:Any,field:String="",element:Boolean=false):Any = when(value) {
        is JSONObject->value.keys().asSequence().sorted().associateWith {normalized(value.get(it),it)}
        is JSONArray->{val values=(0 until value.length()).map {normalized(value.get(it),field,true)};if(values.none {it is Map<*,*>||it is List<*>})values.sortedBy {it.toString()}else values}
        else->{val item=when {
            field=="query_type"&&value=="A"->1
            field=="query_type"&&value=="AAAA"->28
            value is String&&field in setOf("ip_cidr","source_ip_cidr","default_interface_address","wifi","cellular","ethernet","other")&&RouteRules.literal(value)!=null->{val ip=RouteRules.literal(value)!!;ip.address.joinToString(""){"%02x".format(it.toInt()and 255)}+"/"+(ip.address.size*8)}
            value is String&&'/' in value&&RouteRules.literal(value.substringBefore('/'))!=null->{val ip=RouteRules.literal(value.substringBefore('/'))!!;ip.address.joinToString(""){"%02x".format(it.toInt()and 255)}+"/"+value.substringAfter('/')}
            else->value
        };if(!element&&field.isNotEmpty()&&field !in scalar)listOf(item)else item}
    }
    @Test fun matches_stock_compiler_and_recovery_for_all_five_versions() {
        val rows=references();assertEquals(25,rows.length())
        repeat(rows.length()){i->val row=rows.getJSONObject(i);val decoded=BsRuleSetBinary.decode(Base64.getDecoder().decode(row.getString("binary")))
            assertEquals(row.getString("name"),normalized(row.getJSONObject("expected")),normalized(decoded))}
    }
    @Test fun truncated_corrupt_unknown_and_trailing_data_are_rejected() {
        val bytes=Base64.getDecoder().decode(references().getJSONObject(0).getString("binary"))
        for(size in bytes.indices step 7)assertThrows("truncated $size",Exception::class.java){BsRuleSetBinary.decode(bytes.copyOf(size))}
        for(value in listOf(bytes+byteArrayOf(0),bytes.copyOf().also {it[0]=0},bytes.copyOf().also {it[3]=6},bytes.copyOf().also {it[it.lastIndex]=(it.last()+1).toByte()}))assertThrows(Exception::class.java){BsRuleSetBinary.decode(value)}
    }
    private fun packed(payload:ByteArray):ByteArray {val out=ByteArrayOutputStream();out.write(byteArrayOf(83,82,83,3));DeflaterOutputStream(out).use {it.write(payload)};return out.toByteArray()}
    @Test fun malformed_domain_tree_unknown_items_and_duplicate_fields_fail_closed() {
        // One default rule, domain matcher with no terminating LOUDS one bit.
        val malformed=byteArrayOf(1,0,2,0,0,1,0,0,0,0,0,0,0,0,0,255.toByte(),0)
        for(payload in listOf(malformed,byteArrayOf(1,0,254.toByte()),byteArrayOf(1,2),byteArrayOf(1,0,1,0,1,0,255.toByte(),0)))assertThrows(Exception::class.java){BsRuleSetBinary.decode(packed(payload))}
    }
    @Test fun compressed_expansion_is_bounded() {
        val out=ByteArrayOutputStream();out.write(byteArrayOf(83,82,83,3));DeflaterOutputStream(out).use {stream->repeat(24*1024*1024/8192+1){stream.write(ByteArray(8192))}}
        assertTrue(out.size()<100000);assertThrows(IllegalArgumentException::class.java){BsRuleSetBinary.decode(out.toByteArray())}
    }
}
