package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded owned fixture response; correct Content-Length framing never waits for TCP EOF. */
object PacketFixtureHttp {
    fun verify(input: InputStream, expected: String) {
        require(expected.matches(Regex("mcrf-fixture-[0-9a-f]{32}")))
        val header = ByteArrayOutputStream()
        var ending = 0
        while(ending != 0x0d0a0d0a) {
            require(header.size() < 4096) {"PACKET_FIXTURE_HTTP_HEADER_LIMIT"}
            val value = input.read()
            check(value >= 0) {"PACKET_FIXTURE_HTTP_HEADER_EOF"}
            header.write(value); ending = (ending shl 8) or value
        }
        val lines = header.toString("US-ASCII").split("\r\n")
        require(lines.first().startsWith("HTTP/1.1 200 ")) {"PACKET_FIXTURE_HTTP_STATUS"}
        val lengths = lines.filter {it.startsWith("Content-Length:",true)}
        require(lengths.size == 1 && lengths.single().substringAfter(':').trim() == expected.length.toString() &&
            lines.none {it.startsWith("Transfer-Encoding:",true)}) {"PACKET_FIXTURE_HTTP_LENGTH"}
        val body = ByteArray(expected.length)
        var position = 0
        while(position<body.size) {
            val count = input.read(body,position,body.size-position)
            check(count>0) {"PACKET_FIXTURE_HTTP_BODY_EOF"}; position+=count
        }
        require(body.contentEquals(expected.toByteArray(Charsets.US_ASCII))) {"PACKET_FIXTURE_HTTP_PAYLOAD"}
    }
}
