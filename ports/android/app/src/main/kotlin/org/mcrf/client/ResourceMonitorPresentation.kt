package org.mcrf.client

import java.util.Locale

internal data class ResourceMonitorMetric(val id: String, val label: String, val value: String)
internal data class ResourceMonitorSection(val title: String, val rows: List<ResourceMonitorMetric>)
internal data class ResourceMonitorActions(val start: Boolean, val stop: Boolean, val reset: Boolean)

/** Presentation only: opening the main window cannot start sampling or alter a frozen recording. */
internal object ResourceMonitorPresentation {
    fun actions(value: ResourceMonitorRecording, live: Boolean) = ResourceMonitorActions(
        start = live && !value.active, stop = value.active, reset = value.samples > 0 || value.active)

    fun sections(value: ResourceMonitorSnapshot, live: Boolean): List<ResourceMonitorSection> {
        val memory = value.processMemory
        val recording = value.recording
        fun row(id: String, label: String, text: String) = ResourceMonitorMetric(id, label, text)
        fun bytes(id: String, label: String, count: Long?) = row(id, label, ResourceMonitorText.bytes(count))
        fun total(count: Long?, partial: Boolean) = ResourceMonitorText.bytes(count) +
            if (partial && count != null) " (частично)" else ""
        val difference = recording.startProcessPssBytes?.let { start -> recording.currentProcessPssBytes?.minus(start) }
            ?.let { String.format(Locale.getDefault(), "%+.1f МиБ", it / 1048576.0) } ?: "—"
        return listOf(
            ResourceMonitorSection("Процесс MCRF", listOf(
                row("cpu", "CPU", ResourceMonitorText.percent(value.processCpuPercent)),
                bytes("pss", "Память · PSS", value.processPssBytes),
                bytes("rss", "Резидентная · RSS", value.processRssBytes),
                row("threads", "Потоки процесса", value.processThreads?.toString() ?: "—"))),
            ResourceMonitorSection("Память Android · этот процесс", listOf(
                bytes("java", "Java heap", memory.javaHeapBytes),
                bytes("native", "Native heap", memory.nativeHeapBytes),
                bytes("graphics", "Graphics", memory.graphicsBytes),
                bytes("code", "Code", memory.codeBytes),
                bytes("stack", "Stack", memory.stackBytes),
                bytes("private-other", "Private other", memory.privateOtherBytes),
                bytes("system", "System", memory.systemBytes))),
            ResourceMonitorSection("Трафик UID", listOf(
                row("rx-rate", "Приём ↓", ResourceMonitorText.rate(value.uidRxBytesPerSecond)),
                row("tx-rate", "Передача ↑", ResourceMonitorText.rate(value.uidTxBytesPerSecond)),
                bytes("rx-total", "Принято с загрузки", value.uidRxBytes),
                bytes("tx-total", "Передано с загрузки", value.uidTxBytes))),
            ResourceMonitorSection("Подключение", listOf(
                row("session", "Сессия MCRF", yesNo(value.mcrfSessionPresent)),
                row("connected", "Подключено", yesNo(value.mcrfConnected)),
                row("workers", "Воркеры движка", "Недоступно"))),
            ResourceMonitorSection("Запись наблюдения", listOf(
                row("recording-state", "Состояние", if (!live) "Монитор выключен" else if (recording.active) "Идёт" else "Остановлена"),
                row("recording-time", "Время наблюдения", "${recording.observedMs / 1000} с"),
                row("recording-samples", "Замеры", recording.samples.toString()),
                row("recording-rx", "Принято ↓", total(recording.rxBytes, recording.rxPartial)),
                row("recording-tx", "Передано ↑", total(recording.txBytes, recording.txPartial)),
                row("recording-cpu-mean", "CPU · среднее", ResourceMonitorText.percent(recording.averageProcessCpuPercent)),
                row("recording-cpu-peak", "CPU · пик", ResourceMonitorText.percent(recording.peakProcessCpuPercent)),
                bytes("recording-pss-start", "PSS · начало", recording.startProcessPssBytes),
                bytes("recording-pss-current", "PSS · сейчас", recording.currentProcessPssBytes),
                row("recording-pss-delta", "PSS · изменение", difference),
                bytes("recording-pss-peak", "PSS · пик", recording.peakProcessPssBytes),
                bytes("recording-rss-peak", "RSS · пик", recording.peakProcessRssBytes)))
        )
    }

    private fun yesNo(value: Boolean?) = when (value) { true -> "Да"; false -> "Нет"; null -> "—" }
}
