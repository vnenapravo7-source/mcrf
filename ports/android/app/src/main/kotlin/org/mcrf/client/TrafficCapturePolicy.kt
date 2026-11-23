package org.mcrf.client

/** Pure capture contract. A scope is a recording filter, never a change to saved routing. */
object TrafficCapturePolicy {
    enum class Mode(val label: String) { ONLY("Только запись"), CONNECTED("Запись + подключение") }
    data class Options(val mode: Mode, val selectedApps: Set<String> = emptySet(), val seconds: Long = 0,
                       val selectedOnly: Boolean = false) {
        init {
            // Preserve existing sessions/internal callers. New UI starts only one of the six presets below.
            require(seconds in 0..86_400) { "Таймер: 1–86400 секунд или вручную." }
            require(selectedApps.size <= 512 && selectedApps.all { PACKAGE.matches(it) && it.length <= 255 })
            require(!selectedOnly || selectedApps.isNotEmpty()) { "Выберите хотя бы одно приложение." }
        }
        fun accepts(packages: Collection<String>?) = !selectedOnly || packages?.any { it in selectedApps } == true
    }
    data class Preset(val label: String, val seconds: Long)
    val presets = listOf(Preset("30 сек", 30), Preset("1 мин", 60), Preset("3 мин", 180),
        Preset("5 мин", 300), Preset("10 мин", 600), Preset("15 мин", 900))
    enum class Admission { DIRECT, ATTACH, CONNECT, FOREIGN_VPN, SERVER_CONFLICT, BUSY }
    fun admit(mode: Mode, foreignVpn: Boolean, serverActive: Boolean, serverTun: Boolean,
              captureBusy: Boolean, serverRetiring: Boolean = false): Admission = when {
        foreignVpn -> Admission.FOREIGN_VPN
        captureBusy -> Admission.BUSY
        serverRetiring -> Admission.SERVER_CONFLICT
        mode == Mode.ONLY && (serverActive || serverTun) -> Admission.SERVER_CONFLICT
        mode == Mode.ONLY -> Admission.DIRECT
        serverTun -> Admission.ATTACH
        serverActive -> Admission.SERVER_CONFLICT // proxy/starting/recovery: do not replace it
        else -> Admission.CONNECT
    }
    fun deadline(startElapsed: Long, seconds: Long): Long? {
        require(startElapsed >= 0 && seconds in 0..86_400)
        return if (seconds == 0L) null else Math.addExact(startElapsed, seconds * 1000)
    }
    fun expired(nowElapsed: Long, deadline: Long?) = deadline != null && nowElapsed >= deadline
    // Plain text is also included in manual JSON exports; formatting belongs to the UI.
    const val PRIVACY = "Только метаданные. Время, приложение, IP и доступный домен. Содержимое, URL и переписка не сохраняются и не расшифровываются.\n\n" +
        "Видимость ограничена. Домены и приложения иногда неизвестны; DNS-ответ не доказывает подключение. Видны только события движка и приложения внутри VPN. Выбор приложения включает общий UID.\n\n" +
        "Два режима. Только запись — прямой интернет через локальный VPN. Запись + подключение — текущие маршруты MCRF. Другой VPN одновременно работать не может.\n\n" +
        "Локально и зашифровано. До 1000 событий, 512 КиБ и 24 часов. Экспорт — открытый JSON по вашему выбору. Очистка останавливает запись; экспортированные копии удаляются отдельно.\n\n" +
        "Остановка. Локальный VPN записи отключается, серверный VPN продолжает работать. Запись сама не возобновляется."
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
}
