package org.mcrf.client

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/** Numeric-only old-path parity on the JVM; no live DNS, reverse lookups or Android acceptance claim. */
class CanonicalIpv4Test {
    private fun parity(value: String) {
        val actual = requireNotNull(CanonicalIpv4.parse(value)) { value }
        // Generated inputs are four canonical decimal octets; getByName's old numeric path never needs DNS.
        val previous = InetAddress.getByName(value)
        assertTrue(value, actual is Inet4Address)
        assertArrayEquals(value, previous.address, actual.address)
        assertEquals(value, previous.isLoopbackAddress, actual.isLoopbackAddress)
        assertEquals(value, previous.isAnyLocalAddress, actual.isAnyLocalAddress)
        assertEquals(value, previous.isSiteLocalAddress, actual.isSiteLocalAddress)
        assertEquals(value, previous.isMulticastAddress, actual.isMulticastAddress)
    }

    @Test fun canonicalEdgesBroadcastAndSignedByteValuesHaveOldNumericSemantics() {
        listOf("0.0.0.0", "255.255.255.255", "127.0.0.1", "127.255.255.255", "1.2.3.4", "10.0.0.1",
            "169.254.0.1", "172.16.0.1", "192.168.255.254", "224.0.0.1", "255.128.0.1").forEach(::parity)
        assertArrayEquals(byteArrayOf(-1, -128, 0, 1), CanonicalIpv4.parse("255.128.0.1")!!.address)
    }

    @Test fun anyLocalAndEntireLoopbackRangeRemainCorrectWithoutHostLookup() {
        assertTrue(CanonicalIpv4.parse("0.0.0.0")!!.isAnyLocalAddress)
        assertFalse(CanonicalIpv4.parse("0.0.0.1")!!.isAnyLocalAddress)
        for (value in listOf("127.0.0.0", "127.0.0.1", "127.128.255.1", "127.255.255.255"))
            assertTrue(value, CanonicalIpv4.parse(value)!!.isLoopbackAddress)
        assertFalse(CanonicalIpv4.parse("126.255.255.255")!!.isLoopbackAddress)
        assertFalse(CanonicalIpv4.parse("128.0.0.0")!!.isLoopbackAddress)
    }

    @Test fun everyOctetValueInEveryPositionMatchesTheOldNumericPath() {
        repeat(4) { position -> repeat(256) { number ->
            val octets = intArrayOf(11, 22, 33, 44).apply { this[position] = number }
            val value = octets.joinToString(".")
            parity(value)
            assertArrayEquals(value, ByteArray(4) { octets[it].toByte() }, CanonicalIpv4.parse(value)!!.address)
        } }
    }

    @Test fun deterministicCanonicalFuzzMatchesBytesAndAddressFlags() {
        val random = Random(0x4d435246L)
        repeat(5000) {
            val octets = IntArray(4) { random.nextInt(256) }
            val value = octets.joinToString(".")
            parity(value)
            assertArrayEquals(value, ByteArray(4) { octets[it].toByte() }, CanonicalIpv4.parse(value)!!.address)
        }
    }

    @Test fun leadingZerosAndNonFourPartRepresentationsStayWithExistingFallback() {
        for (value in listOf("00.0.0.0", "0.00.0.0", "0.0.00.0", "0.0.0.00", "01.2.3.4", "1.02.3.4", "1.2.003.4",
                "1.2.3.004", "127.1", "127.0.1", "2130706433", "0x7f.0.0.1", "0177.0.0.1", "1", "1.2.3.4.5"))
            assertNull(value, CanonicalIpv4.parse(value))
    }

    @Test fun whitespaceSignsPortsCidrAndMalformedOrOversizeOctetsAreNotOverridden() {
        for (value in listOf("", ".1.2.3", "1..2.3", "1.2.3.", "1.2.3.4.", " 1.2.3.4", "1.2.3.4 ", "1.2.3.4\n",
                "\t1.2.3.4", "+1.2.3.4", "1.-2.3.4", "1.2.3.+4", "256.0.0.1", "0.256.0.1", "0.0.256.1", "0.0.0.256",
                "999.999.999.999", "1000.1.1.1", "1.1.1.1000", "99999999999999999999.1.1.1", "1.2.3.4:443",
                "1.2.3.4/32", "1.2.3.4/0", "1.2.3.4%eth0", "1.2.3.4\u0000", "1.2.3.4/path"))
            assertNull(value, CanonicalIpv4.parse(value))
    }

    @Test fun unicodeIpv6HostnamesAndUrlSyntaxNeverReachFastPath() {
        for (value in listOf("١.٢.٣.٤", "１.２.３.４", "1.2.3.٤", "1.2.3.４", "1．2．3．4", "1.2.3.4\u00a0",
                "::1", "2001:db8::1", "::ffff:192.0.2.1", "[::1]", "1::", "example.test", "localhost", "http://1.2.3.4"))
            assertNull(value, CanonicalIpv4.parse(value))
    }

    @Test fun returnedAddressBytesCannotBeMutatedAcrossCalls() {
        val address = requireNotNull(CanonicalIpv4.parse("1.2.3.4"))
        val bytes = address.address
        bytes.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), address.address)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), CanonicalIpv4.parse("1.2.3.4")!!.address)
    }
}
