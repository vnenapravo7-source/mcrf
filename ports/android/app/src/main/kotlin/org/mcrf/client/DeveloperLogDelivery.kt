package org.mcrf.client

import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Explicit, user-initiated delivery of the same redacted export shown by the Logs screen. */
internal object DeveloperLogDelivery {
    private val endpoint=URI("https://vnenapravo7.ru/wdtt/api/logs")
    const val MAX_BYTES=4*1024*1024

    fun send(payload:String,open:(URI)->HttpsURLConnection={it.toURL().openConnection() as HttpsURLConnection}) {
        val bytes=ClientEvents.redact(payload).toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()&&bytes.size<=MAX_BYTES){"Логи слишком большие для отправки."}
        val connection=open(endpoint)
        try {
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=15_000;connection.readTimeout=20_000
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","text/plain; charset=utf-8")
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("User-Agent","MCRF-Android")
            connection.setRequestProperty("X-MCRF-Log-Schema","1")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use {it.write(bytes)}
            require(connection.responseCode==200){"Сервер не принял логи. Повторите позже."}
        } finally { connection.disconnect() }
    }
}
