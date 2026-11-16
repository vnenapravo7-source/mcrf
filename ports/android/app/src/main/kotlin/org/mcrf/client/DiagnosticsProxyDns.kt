package org.mcrf.client

import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom

/** A real control resolver response, transported over the captured proxy. This
 * measures neither the profile's DNS policy nor voice/game application traffic. */
internal object DiagnosticsProxyDns {
    class Failure(val kind: DiagnosticDnsFailure, val rcode: Int? = null, val httpStatus: Int? = null) : IOException("DIAGNOSTIC_DOH_${kind.name}")
    fun resolve(host: String, token: DiagnosticCancellation, exchange: (ByteArray) -> ByteArray): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        for (type in listOf(1, 28)) {
            token.check()
            val query = PacketDnsWire.query(host, type, SecureRandom().nextInt(65536))
            val reply = try { exchange(query) }
            catch (error: DiagnosticsHttp.DnsHttpException) { throw Failure(DiagnosticDnsFailure.CONTROL_RESPONSE, httpStatus = error.status) }
            catch (_: DiagnosticsHttp.MalformedResponse) { throw Failure(DiagnosticDnsFailure.CONTROL_RESPONSE) }
            token.check()
            try {
                val validated = PacketDnsWire.response(query, reply)
                if (validated.truncated) throw Failure(DiagnosticDnsFailure.CONTROL_RESPONSE)
                val rcode = PacketDnsWire.u16(reply, 2) and 15
                if (rcode == 3) throw Failure(if (addresses.isEmpty()) DiagnosticDnsFailure.TARGET_NO_ADDRESS else DiagnosticDnsFailure.CONTROL_RESPONSE, rcode)
                if (rcode != 0) throw Failure(DiagnosticDnsFailure.CONTROL_RESPONSE, rcode)
                addresses += PacketDnsWire.resolvedAddresses(query, reply)
            } catch (_: IllegalArgumentException) { throw Failure(DiagnosticDnsFailure.CONTROL_RESPONSE) }
        }
        token.check()
        return addresses.distinct()
    }
}
