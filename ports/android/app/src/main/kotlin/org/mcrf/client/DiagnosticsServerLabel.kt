package org.mcrf.client

/** Endpoint-only UI text. Never render an original import link or carrier JSON. */
internal object DiagnosticsServerLabel {
    fun display(server: ServerImport.Server): String {
        val name = ClientEvents.redact(server.name).replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").take(64)
        val host = server.host.trim().removePrefix("[").removeSuffix("]")
        val safe = host.takeIf { it.length in 1..253 && it.matches(Regex("[A-Za-z0-9._:-]+")) }
        val endpoint = safe?.let { if (':' in it) "[$it]" else it } ?: "адрес не указан"
        return "$name\n${if(server.whitelists || server.carrier != null) "БС · " else ""}$endpoint" +
            if(safe != null && server.port in 1..65535) ":${server.port}" else ""
    }
}
