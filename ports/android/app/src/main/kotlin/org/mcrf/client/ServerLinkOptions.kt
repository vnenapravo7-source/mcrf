package org.mcrf.client

/** Link aliases are spelling variants, not permission to change the wire protocol. */
internal object ServerLinkOptions {
    fun value(query: Map<String, String>, vararg names: String): String? {
        val values = names.mapNotNull { query[it] }.distinct()
        require(values.size <= 1) { "В ссылке заданы разные значения одного параметра." }
        return values.singleOrNull()
    }

    fun transport(value: String): String = when (value.lowercase(java.util.Locale.ROOT)) {
        "", "tcp", "raw" -> "tcp"
        "ws", "websocket" -> "ws"
        "splithttp", "xhttp" -> "xhttp"
        "grpc" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        else -> throw IllegalArgumentException("Транспорт ссылки не поддерживается установленным форматом профиля.")
    }

    fun insecure(query: Map<String, String>): Boolean {
        val values = listOfNotNull(query["insecure"], query["allowInsecure"]).map {
            when (it.lowercase(java.util.Locale.ROOT)) {
                "", "0", "false" -> false
                "1", "true" -> true
                else -> throw IllegalArgumentException("Некорректный параметр проверки сертификата.")
            }
        }.distinct()
        require(values.size <= 1) { "В ссылке заданы разные параметры проверки сертификата." }
        return values.singleOrNull() ?: false
    }
}
