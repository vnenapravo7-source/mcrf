package org.mcrf.client

import kotlin.math.roundToInt

/** Unknown dimensions are never silently treated as healthy. No cross-dimension blame. */
object HealthScore {
    data class Score(val server: Int?, val network: Int?, val engine: Int?)
    fun calculate(snapshot: HealthSnapshot, now: Long): Score {
        val fresh = snapshot.samples.filter { now >= it.at && now - it.at < HealthSettings.FRESH_MS }
        val latest = fresh.lastOrNull()
        val sameNetwork = fresh.filter { it.network == latest?.network }
        val attempts = sameNetwork.sumOf { it.attempts }
        val failures = sameNetwork.sumOf { it.failures }
        val ping = sameNetwork.mapNotNull { it.pingMs }.sorted().let { it.getOrNull(it.size / 2) }
        val server = if (attempts == 0) null else {
            val availability = 1.0 - failures.toDouble() / attempts
            val latency = when { ping == null -> 0; ping <= 100 -> 100; ping <= 300 -> 80; ping <= 750 -> 60; else -> 30 }
            (availability * (70 + latency * .3)).roundToInt().coerceIn(0, 100)
        }
        val eventFresh = snapshot.lastEventAt?.let { now >= it && now - it < HealthSettings.FRESH_MS } == true
        fun dimension(d: HealthDimension): Int? {
            if (!eventFresh) return null
            val good = snapshot.successes[d.ordinal]; val bad = snapshot.errors[d.ordinal]
            return if (good + bad == 0L) null else (100.0 * good / (good + bad)).roundToInt().coerceIn(0, 100)
        }
        val serverEvents = dimension(HealthDimension.SERVER)
        return Score(if (server == null) serverEvents else serverEvents?.let { minOf(server, it) } ?: server,
            dimension(HealthDimension.NETWORK), dimension(HealthDimension.ENGINE))
    }

    fun compact(snapshot: HealthSnapshot, now: Long): String {
        val s = calculate(snapshot, now)
        fun value(n:Int?)=n?.let {"$it/100"}?:"нет данных"
        val at=snapshot.samples.lastOrNull()?.at
        val time=at?.let {if(now<it)"время неизвестно" else "${(now-it)/60_000} мин назад"}?:"замеров ещё нет"
        return "Сервер: ${value(s.server)} · Сеть: ${value(s.network)} · Движок: ${value(s.engine)}\nЗамер: $time"
    }

    fun details(snapshot: HealthSnapshot, now: Long, usedBytes: Long): String {
        val score = calculate(snapshot, now)
        val last = snapshot.samples.lastOrNull()
        fun value(n: Int?) = n?.let { "$it/100" } ?: "нет свежих данных"
        val age = last?.let { if (now < it.at) "часы изменились" else "${(now - it.at) / 60_000} мин назад" } ?: "ещё нет"
        val recent = snapshot.samples.filter { now >= it.at && now - it.at < HealthSettings.FRESH_MS && it.network == last?.network }
        val attempts = recent.sumOf { it.attempts }
        val loss = if (attempts == 0) "—" else "${(100.0 * recent.sumOf { it.failures } / attempts).roundToInt()}% ($attempts попыток)"
        return "Сервер (TCP): ${value(score.server)}\nСеть: ${value(score.network)}\nДвижок: ${value(score.engine)}\n\n" +
            "Ping TCP: ${last?.pingMs?.let { "$it мс" } ?: "—"} · $age\n" +
            "Неуспешные TCP-попытки ≈ потери: $loss\nЭто оценка доступности порта, не ICMP и не проверка VPN/протокола.\n" +
            "Скорость отправки и загрузки: замер недоступен.\n\n" +
            "Сессии: ${snapshot.sessions} · время работы: ${snapshot.uptimeMs / 1000} с\n" +
            "Ошибки: сервер ${snapshot.errors[0]}, сеть ${snapshot.errors[1]}, движок ${snapshot.errors[2]}\nПоследнее отключение: ${snapshot.lastDisconnect ?: "—"}\n" +
            "Сеть: ${snapshot.network} · движок: ${snapshot.engine}\n" +
            "События сессий учитываются, пока включён Health Beta.\n\n" +
            "Только на устройстве: до 7 дней, 64 сервера, 256 замеров, 64 КиБ данных.\n" +
            "Оценочный бюджет TCP/DNS: ${usedBytes / 1024}/256 КиБ за 24 ч; учитываются отмены и ошибки."
    }
}
