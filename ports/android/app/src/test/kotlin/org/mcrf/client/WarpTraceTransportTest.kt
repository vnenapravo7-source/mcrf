package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WarpTraceTransportTest {
    private val body="warp=on\nloc=NL\ncolo=AMS\n"
    private fun read(value: String)=WarpTraceTransport.readBody(ByteArrayInputStream(value.toByteArray(Charsets.US_ASCII)))
    @Test fun boundedFixedAndChunkedBody() {
        assertEquals(body,read("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"))
        assertEquals(body,read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n"))
        assertEquals(body,read("HTTP/1.0 200 OK\r\n\r\n$body"))
    }
    @Test fun redirectsDuplicatesOversizeAndTruncationFail() {
        for(response in listOf(
            "HTTP/1.1 302 Found\r\nLocation: https://example.org\r\n\r\n$body",
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\n12345",
            "HTTP/1.1 200 OK\r\nContent-Length: 9000\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 30\r\n\r\nshort",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n$body",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 10\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3000\r\n")) {
            assertThrows(Exception::class.java) {read(response)}
        }
    }
    @Test fun unauthenticatedLoopbackProxyIsRefusedWithoutFallback() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            val worker=Executors.newSingleThreadExecutor()
            try {
                val peer=worker.submit {server.accept().use { socket ->
                    socket.soTimeout=2000;val input=socket.getInputStream()
                    assertEquals(5,input.read());assertEquals(1,input.read());assertEquals(2,input.read())
                    socket.getOutputStream().write(byteArrayOf(5,0));socket.getOutputStream().flush()
                }}
                assertThrows(IllegalArgumentException::class.java) {WarpTraceTransport.fetch(server.localPort,"fixture","fixture-password-only") {false}}
                peer.get(3,TimeUnit.SECONDS)
            } finally {worker.shutdownNow()}
        }
    }
    @Test fun cancellationClosesOnlyTheOwnLoopbackSocket() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            val worker=Executors.newFixedThreadPool(2);val cancelled=AtomicBoolean()
            try {
                val peer=worker.submit {server.accept().use { socket ->
                    socket.soTimeout=3000;val input=socket.getInputStream()
                    repeat(3) {assertTrue(input.read()>=0)}
                    socket.getOutputStream().write(byteArrayOf(5,2));socket.getOutputStream().flush()
                    assertEquals(1,input.read());val user=input.read();repeat(user) {assertTrue(input.read()>=0)}
                    val secret=input.read();repeat(secret) {assertTrue(input.read()>=0)}
                    cancelled.set(true)
                    assertEquals(-1,input.read())
                }}
                val operation=worker.submit {assertThrows(java.util.concurrent.CancellationException::class.java) {WarpTraceTransport.fetch(server.localPort,"fixture","fixture-password-only") {cancelled.get()}}}
                operation.get(5,TimeUnit.SECONDS);peer.get(5,TimeUnit.SECONDS)
            } finally {worker.shutdownNow()}
        }
    }
}
