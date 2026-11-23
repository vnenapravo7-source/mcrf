package org.mcrf.client

/** Shared wording for both diagnostic paths, including unfinished measurements. */
internal object DiagnosticsPresentation {
    data class ComparisonEntry(val serverSlot: Int, val targetId: String, val state: DiagnosticState,
        val group: ServiceTestResults.Group, val reason: String)

    fun comparisonEntries(measured: List<ServerServiceMeasurement>, slots: Set<Int>, targets: Set<String>,
        unfinishedReason: String? = null): List<ComparisonEntry> {
        val entries = measured.map { value ->
            val group = value.diagnostic?.let(ServiceTestResults::group) ?: when (value.state) {
                DiagnosticState.AVAILABLE -> ServiceTestResults.Group.SUCCESS
                DiagnosticState.ERROR -> if (value.limitation == ServerComparisonLimit.BUDGET) ServiceTestResults.Group.FAILURE else ServiceTestResults.Group.UNKNOWN
                else -> ServiceTestResults.Group.UNKNOWN
            }
            ComparisonEntry(value.serverSlot, value.targetId,
                if (value.state == DiagnosticState.ERROR && group == ServiceTestResults.Group.UNKNOWN) DiagnosticState.UNAVAILABLE else value.state,
                group, value.diagnostic?.let(::resultReason) ?: if (value.state == DiagnosticState.AVAILABLE)
                    successReason(value.targetId, value.latencyMs, null) else comparisonReason(value.limitation))
        }.toMutableList()
        if (unfinishedReason != null) {
            val covered = measured.mapTo(hashSetOf()) { it.serverSlot to it.targetId }
            for (slot in slots) for (target in targets) if (slot to target !in covered)
                entries.add(ComparisonEntry(slot, target, DiagnosticState.UNAVAILABLE, ServiceTestResults.Group.UNKNOWN, unfinishedReason))
        }
        return entries
    }

    fun stopReason(issue: DiagnosticIssue) = when (issue) {
        DiagnosticIssue.CANCELLED -> "Проверка отменена"
        DiagnosticIssue.TIMEOUT, DiagnosticIssue.BUDGET_EXHAUSTED -> "Истекло время проверки"
        DiagnosticIssue.PATH_CHANGED -> "Во время проверки изменилась сеть"
        DiagnosticIssue.CAPACITY -> "Предыдущая проверка ещё завершается"
        else -> "Измерение не выполнено — повторите проверку"
    }

    fun comparisonReason(limit: ServerComparisonLimit) = when (limit) {
        ServerComparisonLimit.NONE -> "Недостаточно данных для вывода"
        ServerComparisonLimit.UNSUPPORTED_SERVICE -> "Эту часть нельзя проверить автоматически"
        ServerComparisonLimit.UNSUPPORTED_PROVIDER -> "Движок не поддерживает отдельную проверку"
        ServerComparisonLimit.ACTIVE_VPN_ISOLATION -> "Активный VPN мешает отдельной проверке — отключите его"
        ServerComparisonLimit.BUSY -> "Другая проверка ещё завершается"
        ServerComparisonLimit.NETWORK -> "Нет доступной физической сети"
        ServerComparisonLimit.PATH_CHANGED -> "Во время проверки изменилась сеть"
        ServerComparisonLimit.BUDGET -> "Истекло время ответа"
        ServerComparisonLimit.CANCELLED -> "Проверка отменена"
        ServerComparisonLimit.PROVIDER_FAILURE -> "Ответ не получен; точная причина не установлена"
        ServerComparisonLimit.SAFE_MODE -> "Безопасный режим запрещает запуск отдельного прокси"
        ServerComparisonLimit.INTERNET_UNCONFIRMED -> "Сеть есть, но Android ещё не подтвердил доступ в интернет"
        ServerComparisonLimit.PROXY_CONFIG -> "Не удалось подготовить конфигурацию временного прокси"
        ServerComparisonLimit.PROXY_VALIDATION -> "Движок отклонил конфигурацию временного прокси"
        ServerComparisonLimit.PROXY_START -> "Временный прокси не запустился; сервис ещё не проверен"
        ServerComparisonLimit.PROXY_START_TIMEOUT -> "Истекло время запуска временного прокси; сервис ещё не проверен"
        ServerComparisonLimit.PROXY_EXITED -> "Временный прокси остановился до завершения проверки"
        ServerComparisonLimit.PROXY_AUTH -> "Прокси отклонил авторизацию; ответ сервиса не получен"
        ServerComparisonLimit.PROXY_CONNECT -> "Прокси не открыл туннель к узлу; ответ сервиса не получен"
        ServerComparisonLimit.PROXY_PROTOCOL -> "Некорректный или неполный ответ локального прокси"
        ServerComparisonLimit.SINGBOX_MISSING -> "Для отдельной проверки нужен установленный модуль sing-box; основной движок менять не требуется"
        ServerComparisonLimit.SINGBOX_UNVERIFIED -> "Не удалось проверить установленный модуль sing-box; отдельная проверка не запущена"
    }

    private fun dnsScope(scope: DiagnosticDnsScope?) = when (scope) {
        DiagnosticDnsScope.CONTROL_DOH_THROUGH_PROXY -> "Контрольный DNS-over-HTTPS через прокси; DNS профиля и работа приложения не проверены"
        DiagnosticDnsScope.PLATFORM -> "Только DNS физической сети; работа приложения не проверена"
        null -> "Только DNS; путь резолвера не подтверждён, работа приложения не проверена"
    }

    private fun successReason(targetId: String, elapsedMs: Long?, scope: DiagnosticDnsScope?): String =
        if (ServiceTestCatalog.find(targetId)?.check == DiagnosticCheck.DNS)
            "Только DNS: ${dnsScope(scope)} · ${elapsedMs ?: "?"} мс"
        else "HTTPS узла ответил за ${elapsedMs ?: "?"} мс; работа приложения не проверена"

    /** Safe enum/numeric evidence only. Used by comparisons and other consumers. */
    fun resultReason(result: DiagnosticResult): String {
        if (result.proxyFailure != null) return comparisonReason(serverServiceMeasurement(1, result).limitation)
        val status = result.evidence.lastOrNull { it.httpStatus != null }?.httpStatus
        if (result.dnsFailure != null && result.issue !in setOf(DiagnosticIssue.PATH_CHANGED, DiagnosticIssue.CANCELLED)) {
            val rcode = result.evidence.lastOrNull { it.dnsRcode != null }?.dnsRcode
            val reason = when (result.dnsFailure) {
                DiagnosticDnsFailure.CONTROL_TRANSPORT -> "Не удалось выполнить контрольный DoH-запрос через прокси: " + when (result.issue) {
                    DiagnosticIssue.TIMEOUT -> "истекло время"
                    DiagnosticIssue.TLS_FAILED -> "сбой проверки TLS"
                    else -> "соединение не завершено"
                } + "; DNS целевого имени не проверен"
                DiagnosticDnsFailure.CONTROL_RESPONSE -> "Контрольный DoH-резолвер вернул отказ или некорректный ответ" +
                    (status?.let { " · HTTP $it" } ?: rcode?.let { " · DNS RCODE $it" }.orEmpty())
                DiagnosticDnsFailure.TARGET_NO_ADDRESS -> if (rcode == 3) "Контрольный DNS вернул NXDOMAIN для целевого имени" else "Контрольный DNS не вернул адресов целевого имени"
            }
            return "$reason · ${dnsScope(result.dnsScope)}"
        }
        val reason = if (result.state == DiagnosticState.AVAILABLE) {
            successReason(result.targetId, result.elapsedMs, result.dnsScope) +
                if (status in 300..399) " · HTTP $status, перенаправление не проверено" else ""
        } else when (result.issue) {
            DiagnosticIssue.DNS_FAILED -> "Не получен корректный ответ DNS"
            DiagnosticIssue.NO_ADDRESS -> "DNS не вернул адресов для узла"
            DiagnosticIssue.DNS_NOT_OBSERVED -> "DNS-ответ через этот путь не измерен"
            DiagnosticIssue.CONNECT_FAILED -> "Не удалось установить соединение с узлом"
            DiagnosticIssue.TLS_FAILED -> "Не удалось подтвердить TLS узла"
            DiagnosticIssue.HTTP_REJECTED -> when (status) {
                401, 403 -> "HTTP $status: узел ответил, но отклонил запрос; доступ к сервису не подтверждён"
                405, 501 -> "HTTP $status: узел не поддерживает метод проверки"
                429 -> "HTTP 429: узел ограничил частоту запросов"
                in 500..599 -> "HTTP $status: ошибка на стороне узла"
                null -> "Некорректный или неполный ответ HTTP"
                else -> "HTTP $status: запрос проверки отклонён"
            }
            DiagnosticIssue.TIMEOUT -> "Истекло время ответа узла"
            DiagnosticIssue.CANCELLED -> stopReason(result.issue)
            DiagnosticIssue.PATH_CHANGED -> comparisonReason(ServerComparisonLimit.PATH_CHANGED)
            DiagnosticIssue.CAPACITY -> comparisonReason(ServerComparisonLimit.BUSY)
            DiagnosticIssue.NO_DIRECT_NETWORK -> comparisonReason(ServerComparisonLimit.NETWORK)
            DiagnosticIssue.NO_MCRF_PROXY -> "Нет работающего локального прокси MCRF"
            DiagnosticIssue.UNSUPPORTED_PROTOCOL -> comparisonReason(ServerComparisonLimit.UNSUPPORTED_SERVICE)
            DiagnosticIssue.BUDGET_EXHAUSTED -> stopReason(result.issue)
            else -> "Проверка не выполнена; недостаточно данных для вывода"
        }
        return reason + if (result.dnsScope == DiagnosticDnsScope.CONTROL_DOH_THROUGH_PROXY && result.state != DiagnosticState.AVAILABLE)
            " · ${dnsScope(result.dnsScope)}" else ""
    }

    /** Missing evidence is not a confirmed failure of the service. */
    fun displayState(result:DiagnosticResult):DiagnosticState =
        if(result.state==DiagnosticState.ERROR && ServiceTestResults.group(result)==ServiceTestResults.Group.UNKNOWN)
            DiagnosticState.UNAVAILABLE else result.state

    fun resultState(value:DiagnosticState?)=when(value) {
        DiagnosticState.AVAILABLE -> "Успех"
        DiagnosticState.ERROR -> "Ошибка"
        DiagnosticState.RUNNING -> "Проверяем…"
        DiagnosticState.WAITING, null -> "Ожидание"
        else -> "Не определено"
    }
    fun progress(completed:Int,total:Int):Int = if(total<=0)0 else
        (completed.coerceIn(0,total).toLong()*100/total).toInt()
    fun state(value: DiagnosticState?) = when(value) {
        DiagnosticState.AVAILABLE -> "Доступен"
        DiagnosticState.ERROR -> "Ошибка"
        DiagnosticState.RUNNING -> "Проверяем…"
        DiagnosticState.WAITING, null -> "Ожидание"
        DiagnosticState.CANCELLED -> "Отменено"
        DiagnosticState.UNAVAILABLE -> "Не определено"
    }
}
