package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import org.json.JSONObject

/** Bounded HTTPS only. Device headers require an explicit option; URLs stay out of errors. */
object SubscriptionClient {
    fun interface Opener { @Throws(IOException::class) fun open(uri: URI): HttpURLConnection }
    class Downloaded(val servers: List<ServerImport.Server>, val metadata: JSONObject)
    @JvmStatic fun isAddress(text: String) = text.trim().lowercase(Locale.ROOT).startsWith("https://")
    @JvmStatic fun address(text: String): URI {
        try {
            val uri = URI(text.trim())
            require(text.length <= 8192 && "https".equals(uri.scheme, true) && uri.host != null &&
                uri.rawUserInfo == null && uri.rawFragment == null && uri.port != 0 && uri.port <= 65535)
            return uri
        } catch (_: Exception) { throw IllegalArgumentException("Подписка должна быть HTTPS-ссылкой без логина и фрагмента.") }
    }
    @JvmStatic @Throws(Exception::class)
    fun download(source: String): List<ServerImport.Server> = fetch(source).servers
    @JvmStatic @Throws(Exception::class)
    fun download(source: String, opener: Opener): List<ServerImport.Server> = fetch(source, opener).servers
    @JvmStatic @JvmOverloads @Throws(Exception::class)
    fun fetch(source: String, opener: Opener = Opener { it.toURL().openConnection() as HttpURLConnection }, hwid: String? = null,
        options: SubscriptionOptions = SubscriptionOptions(), model: String? = null, osVersion: String? = null,
        keys: HappSubscriptionBody.Keys? = null): Downloaded {
        checkActive()
        var uri = address(source)
        val origin = uri
        val headers = options.headers(hwid, model, osVersion)
        val deadline = System.nanoTime() + 30_000_000_000L
        try {
            for (redirects in 0..5) {
                checkActive()
                val connection = opener.open(uri)
                try {
                    checkActive()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = remaining(deadline); connection.readTimeout = remaining(deadline)
                    connection.setRequestProperty("User-Agent", headers.getValue("User-Agent"))
                    // Consent applies to this subscription host, never to a different redirect destination.
                    if (uri.host.equals(origin.host, true) && uri.port == origin.port)
                        headers.filterKeys { it != "User-Agent" }.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                    connection.setRequestProperty("Accept-Encoding", "identity"); connection.useCaches = false
                    val code = connection.responseCode
                    checkActive()
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                        require(redirects != 5 && location != null) { "Слишком много перенаправлений подписки." }
                        uri = try { address(uri.resolve(location).toString()) } catch (_: IllegalArgumentException) {
                            throw IllegalArgumentException("Некорректное или небезопасное перенаправление подписки.")
                        }
                        continue
                    }
                    require(code in 200..299) { "Сервер подписки вернул HTTP $code." }
                    require(connection.contentLengthLong <= ServerImport.MAX_BYTES) { "Подписка больше 4 МБ." }
                    val data = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    connection.inputStream.use { input ->
                        while (true) {
                            checkActive()
                            connection.readTimeout = remaining(deadline)
                            val length = input.read(buffer)
                            checkActive()
                            if (length < 0) break
                            require(data.size() + length <= ServerImport.MAX_BYTES) { "Подписка больше 4 МБ." }
                            data.write(buffer, 0, length)
                        }
                    }
                    val raw = data.toByteArray()
                    // Authenticate the actual response's encrypted body before decoding or parsing profiles.
                    // No keys are inferred from URL IDs; the caller must explicitly supply a scoped provider.
                    val decoded = try {
                        if(keys==null) HappSubscriptionBody.decode(raw,connection.getHeaderField("encrypt-tag"),uri)
                        else HappSubscriptionBody.decode(raw,connection.getHeaderField("encrypt-tag"),uri,keys)
                    } finally { raw.fill(0) }
                    val text = try {checkActive();Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded))
                        .toString().removePrefix("\uFEFF") } finally { decoded.fill(0) }
                    // Existing subscriptions use the context-free parser, never defaults for new user imports.
                    val imported = ServerImport.read(text)
                    checkActive()
                    require(imported.isNotEmpty()) { "В подписке нет подключений. Старые данные сохранены." }
                    val info=metadata(connection,text)
                    checkActive()
                    return Downloaded(imported,info)
                } finally { connection.disconnect() }
            }
        } catch (error: IOException) { throw IllegalArgumentException(SubscriptionFailure.describe(error),error) }
        throw IllegalArgumentException("Не удалось загрузить подписку.")
    }
    private fun checkActive(){if(Thread.currentThread().isInterrupted)throw InterruptedException("Загрузка подписки отменена.")}
    private fun metadata(connection: HttpURLConnection, body:String): JSONObject {
        val headers=linkedMapOf<String,String>()
        listOf("subscription-userinfo","profile-device-limit","profile-title","subscription-always-hwid-enable","hide-settings").forEach { name ->
            connection.getHeaderField(name)?.takeIf {it.length<=2048}?.let {headers[name]=it}
        }
        // Provider flags are metadata only: they never switch identity consent or execute routing actions.
        return HappCompatibility.metadata(body,headers)
    }
    private fun remaining(deadline: Long): Int {
        val milliseconds = (deadline - System.nanoTime()) / 1_000_000
        if (milliseconds <= 0) throw SocketTimeoutException()
        return milliseconds.coerceAtMost(30_000).toInt()
    }
}
