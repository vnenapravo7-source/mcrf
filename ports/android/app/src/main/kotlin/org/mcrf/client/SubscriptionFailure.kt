package org.mcrf.client

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.CharacterCodingException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Diagnostic categories only: never interpolate server bodies, URLs or parser input. */
internal object SubscriptionFailure {
    enum class Phase { FETCH, SAVE }
    private val http=Regex("Сервер подписки вернул HTTP ([1-5][0-9]{2})\\.")
    private val controlled=setOf("Слишком много перенаправлений подписки.",
        "Некорректное или небезопасное перенаправление подписки.","Подписка больше 4 МБ.",
        "В подписке нет подключений. Старые данные сохранены.","В импорте больше 1000 подключений.",
        "Подписка должна быть HTTPS-ссылкой без логина и фрагмента.",SubscriptionPayloadBudget.MESSAGE)
    fun describe(error:Throwable,phase:Phase=Phase.FETCH):String {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        val chain=mutableListOf<Throwable>();var current:Throwable?=error
        repeat(16){val value=current?:return@repeat;if(!seen.add(value)){current=null;return@repeat};chain+=value;current=value.cause}
        return when {
            chain.any {it is InterruptedException||it is java.util.concurrent.CancellationException}->"SUBSCRIPTION_CANCELLED: Загрузка подписки отменена."
            phase==Phase.SAVE->"SUBSCRIPTION_WRITE_UNCONFIRMED: Не удалось подтвердить запись обновлённых подписок; проверьте текущие данные при повторном открытии."
            chain.any {it is SSLException||it is CertificateException}->"SUBSCRIPTION_TLS: Не удалось установить защищённое соединение с сервером подписки."
            chain.any {it is UnknownHostException}->"SUBSCRIPTION_DNS: Не удалось определить адрес сервера подписки."
            chain.any {it is SocketTimeoutException}->"SUBSCRIPTION_TIMEOUT: Сервер подписки не ответил за отведённое время."
            chain.any {it is CharacterCodingException}->"SUBSCRIPTION_UTF8: Подписка должна содержать UTF-8 текст."
            else->{
                val status=chain.asSequence().mapNotNull {it.message?.takeIf {text->text.length<=256}?.let(http::matchEntire)?.groupValues?.get(1)}.firstOrNull()
                val known=chain.asSequence().mapNotNull {it.message?.takeIf {text->text.length<=256}}.firstOrNull {it in controlled}
                when {
                    status!=null->"SUBSCRIPTION_HTTP_$status: Сервер подписки вернул HTTP $status."
                    known!=null->"SUBSCRIPTION_REJECTED: $known"
                    chain.any {it is IOException}->"SUBSCRIPTION_NETWORK: Не удалось загрузить подписку из-за ошибки сети."
                    else->"SUBSCRIPTION_FORMAT: Не удалось разобрать подписку. Старые данные сохранены."
                }
            }
        }
    }
}
