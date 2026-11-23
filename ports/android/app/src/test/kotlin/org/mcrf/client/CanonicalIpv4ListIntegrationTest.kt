package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CanonicalIpv4ListIntegrationTest {
    @Test fun listsRetainAddressCidrAndHostsFileSemantics() {
        val parsed=RouteRules.parse("1.2.3.4\n255.128.0.1/24\n0.0.0.0\n127.0.0.1\n127.0.0.1/32\n0.0.0.0 hosts.example.test\n::1\n2001:db8::1/64")
        assertEquals(listOf("1.2.3.4/32","255.128.0.1/24","127.0.0.1/32","2001:db8::1/64"),parsed.getValue("ip_cidr"))
        assertEquals(listOf("hosts.example.test"),parsed.getValue("domain_suffix"))
    }

    @Test fun leadingZerosAndIpv6StillUseTheExistingNumericFallback() {
        for(value in listOf("001.002.003.004","2001:db8::a","::ffff:192.0.2.1")) {
            assertNull(CanonicalIpv4.parse(value))
            val old=RouteRules.literal(value)
            if(old==null&&value.contains(':')) {
                assertThrows(IllegalArgumentException::class.java){RouteRules.parse(value)}
                continue
            }
            val parsed=RouteRules.parse(value)
            if(old!=null&&!old.isLoopbackAddress&&!old.isAnyLocalAddress)
                assertEquals(listOf(value+if(value.contains(':'))"/128"else"/32"),parsed.getValue("ip_cidr"))
            else if(old==null)assertEquals(listOf(value),parsed.getValue("domain_suffix"))
        }
    }

    @Test fun invalidCidrBoundsAndAddressesRemainRejected() {
        for(value in listOf("1.2.3.4/33","1.2.3.4/-1","1.2.3.4/","256.1.1.1/24","1.2.3.4/24/1","2001:db8::1/129"))
            assertThrows(value,IllegalArgumentException::class.java){RouteRules.parse(value)}
    }

    @Test fun fastPathIsPrivateToThreeListCallsitesAndPublicLiteralIsUnchanged() {
        val source=listOf(File("src/main/kotlin/org/mcrf/client/RouteRules.kt"),File("app/src/main/kotlin/org/mcrf/client/RouteRules.kt"))
            .first {it.isFile}.readText().replace("\r\n","\n")
        val parser=source.substringAfter("private fun parseUncached(text:String)").substringBefore("private fun listLiteral")
        assertEquals(3,parser.split("listLiteral(").size-1)
        assertTrue(source.contains("private fun listLiteral(value:String):InetAddress?=CanonicalIpv4.parse(value)?:literal(value)"))
        val public=source.substringAfter("@JvmStatic fun literal(value: String): InetAddress? {").substringBefore("@JvmStatic fun validatePackage")
        assertFalse(public.contains("CanonicalIpv4"))
        assertTrue(public.contains("InetAddress.getByName(value)"))
        assertTrue(public.contains("value.split('.').any { it.toInt() > 255 }"))
    }
}
