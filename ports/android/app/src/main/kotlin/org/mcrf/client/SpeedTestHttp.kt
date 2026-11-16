package org.mcrf.client

import java.io.IOException
import java.net.Socket
import java.security.SecureRandom

/** HTTP/1.1 streaming with exact download length and small upload-control replies.
 * No redirects, decompression, cookies, user payload, telemetry POST or unbounded response buffering.
 */
internal object SpeedTestHttp {
    fun exchange(socket: Socket, stage: SpeedTestStage, bytes: Int, token: DiagnosticCancellation,
                 progress: (Long) -> Unit): SpeedTestSample {
        require(bytes == when (stage) {
            SpeedTestStage.LATENCY -> 0
            SpeedTestStage.DOWNLOAD -> SpeedTestPlan.DOWNLOAD_BYTES
            SpeedTestStage.UPLOAD -> SpeedTestPlan.UPLOAD_BYTES
        })
        val upload = stage == SpeedTestStage.UPLOAD
        val path = if (upload) "/__up" else "/__down?bytes=$bytes"
        val extra = if (upload) "Content-Type: application/octet-stream\r\nContent-Length: $bytes\r\n" else ""
        val request = "${if (upload) "POST" else "GET"} $path HTTP/1.1\r\nHost: ${SpeedTestPlan.HOST}\r\n" +
            "Accept: */*\r\nAccept-Encoding: identity\r\nCache-Control: no-store, no-cache\r\nConnection: close\r\n$extra\r\n"
        token.check()
        val started = System.nanoTime() // After TLS; includes HTTP response delay and server acknowledgement on upload.
        socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
        if (upload) {
            val block = ByteArray(16 * 1024)
            val random = SecureRandom()
            try {
                var sent = 0
                while (sent < bytes) {
                    token.check(); random.nextBytes(block)
                    val count = minOf(block.size, bytes - sent)
                    socket.outputStream.write(block, 0, count); sent += count; progress(sent.toLong())
                }
            } finally { block.fill(0) }
        }
        socket.outputStream.flush()
        val head = DiagnosticsHttp.head(socket, token)
        if (head.status != 200) throw IOException("SPEEDTEST_HTTP_REJECTED")
        val maximum = if (upload) SpeedTestPlan.CONTROL_BYTES else bytes
        body(socket, head, maximum, if (upload) null else bytes, token) { received -> if (!upload) progress(received) }
        return SpeedTestSample(bytes.toLong(), System.nanoTime() - started)
    }

    internal fun body(socket: Socket, head: DiagnosticsHttp.Head, maximum: Int, exact: Int?, token: DiagnosticCancellation,
                      progress: (Long) -> Unit) {
        if (head.headers["content-encoding"]?.let { !it.equals("identity", true) } == true) throw DiagnosticsHttp.MalformedResponse()
        val transfer = head.headers["transfer-encoding"]
        val length = head.headers["content-length"]
        if (transfer != null && length != null) throw DiagnosticsHttp.MalformedResponse()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        fun read(count: Int) {
            if (count < 0 || count > maximum - total) throw DiagnosticsHttp.MalformedResponse()
            var left = count
            while (left > 0) {
                token.check(); socket.soTimeout = token.remainingMs()
                val got = socket.inputStream.read(buffer, 0, minOf(buffer.size, left))
                if (got <= 0) throw DiagnosticsHttp.MalformedResponse()
                left -= got; total += got; progress(total.toLong())
            }
        }
        when {
            transfer?.equals("chunked", true) == true -> {
                var chunks = 0
                while (true) {
                    if (++chunks > 8192) throw DiagnosticsHttp.MalformedResponse()
                    val sizeText = DiagnosticsHttp.line(socket, token, 128).substringBefore(';')
                    if (!sizeText.matches(Regex("[0-9a-fA-F]{1,8}"))) throw DiagnosticsHttp.MalformedResponse()
                    val size = sizeText.toIntOrNull(16) ?: throw DiagnosticsHttp.MalformedResponse()
                    if (size == 0) {
                        var remaining = 4096
                        while (true) {
                            val trailer = DiagnosticsHttp.line(socket, token, minOf(1024, remaining))
                            remaining -= trailer.length + 2
                            if (remaining < 0 || (trailer.isNotEmpty() && !trailer.contains(':'))) throw DiagnosticsHttp.MalformedResponse()
                            if (trailer.isEmpty()) break
                        }
                        break
                    }
                    read(size)
                    if (DiagnosticsHttp.line(socket, token, 2).isNotEmpty()) throw DiagnosticsHttp.MalformedResponse()
                }
            }
            transfer != null -> throw DiagnosticsHttp.MalformedResponse()
            length != null -> {
                if (!length.matches(Regex("[0-9]{1,10}"))) throw DiagnosticsHttp.MalformedResponse()
                val count = length.toIntOrNull() ?: throw DiagnosticsHttp.MalformedResponse()
                if (exact != null && count != exact) throw DiagnosticsHttp.MalformedResponse()
                read(count)
            }
            else -> throw DiagnosticsHttp.MalformedResponse() // No close-delimited/unbounded response.
        }
        if (exact != null && total != exact) throw DiagnosticsHttp.MalformedResponse()
    }
}
