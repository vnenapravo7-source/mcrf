package org.mcrf.client

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual QR codec and actual MCRF parser; only mocked payload exports, never a phone/account/server. */
class AndroidDeployQrTest {
    @Test fun payloadExportQrAndImportKeepPasswordAndEveryCallForAllThreeProtocols() {
        val fixture=javaClass.classLoader!!.getResourceAsStream("AndroidDeploy-export-links.json")!!.use { JSONObject(it.bufferedReader().readText()) }
        assertTrue(fixture.getBoolean("fixture_only"))
        val rows=fixture.getJSONArray("exports");assertEquals(10,rows.length())
        // Reflection avoids coupling this owned test to the compatibility owner's nested Server model.
        val parserClass=Class.forName("org.mcrf.client.SwgProfile")
        val parser=parserClass.getField("INSTANCE").get(null)
        val parse=parserClass.getMethod("link",String::class.java)
        val share=parserClass.getMethod("share",JSONObject::class.java)
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val link=row.getString("link")
            val matrix=MultiFormatWriter().encode(link,BarcodeFormat.QR_CODE,768,768)
            val pixels=IntArray(768*768) { p -> if(matrix[p%768,p/768]) 0xff000000.toInt() else 0xffffffff.toInt() }
            val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(768,768,pixels)))).text
            assertEquals("QR changed the full connection URI",link,decoded)
            val node=parse.invoke(parser,decoded)
            val data=node.javaClass.getField("carrier").get(node) as JSONObject
            assertEquals(row.getString("kind"),data.getString("type"))
            assertEquals(row.getString("password"),data.getString("password"))
            assertEquals(row.getInt("count"),data.getJSONArray("hashes").length())
            assertEquals(row.getJSONArray("hashes").toString(),data.getJSONArray("hashes").toString())
            val exported=share.invoke(parser,data) as String
            val reimported=parse.invoke(parser,exported)
            val roundtrip=reimported.javaClass.getField("carrier").get(reimported) as JSONObject
            assertEquals(data.getString("password"),roundtrip.getString("password"))
            assertEquals(data.getJSONArray("hashes").toString(),roundtrip.getJSONArray("hashes").toString())
        }
    }
}
