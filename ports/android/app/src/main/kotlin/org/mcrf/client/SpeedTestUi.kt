package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.Locale

/** UI observes an application-owned run. Dismiss/stop/rotation never cancel it. */
internal object SpeedTestUi {
    private var shown: AlertDialog? = null
    private data class Choice(val path: SpeedTestPath, val id: String?, val label: String, val available: Boolean = true)
    fun show(activity: MainActivity) = with(activity) {
        shown?.dismiss()
        val handler = Handler(Looper.getMainLooper())
        val availableChoices = listOf(Choice(SpeedTestPath.APP_NETWORK, null, "Сеть приложения Android"),
            Choice(SpeedTestPath.CURRENT_MCRF_PROXY, null, "Текущий MCRF-прокси · по его правилам")) +
            catalogue.entries().map { server -> Choice(SpeedTestPath.SAVED_SERVER, server.id,
                (if (server.whitelists || server.carrier != null) "" else "VPN · ") + DiagnosticsServerLabel.display(server)) }
        val initial = SpeedTestService.state.snapshot
        // A deleted prior server must never appear restored as app-network.
        val missing = initial.request?.takeIf { request -> request.path == SpeedTestPath.SAVED_SERVER &&
            availableChoices.none { it.id == request.serverId } }
        val choices = if (missing == null) availableChoices else availableChoices +
            Choice(SpeedTestPath.SAVED_SERVER, missing.serverId, "Сохранённый сервер недоступен", false)
        var providerIndex = 0
        var routeIndex = 0
        initial.request?.let { request ->
            providerIndex = SpeedTestProviders.all.indexOfFirst { it.id == request.providerId }.coerceAtLeast(0)
            routeIndex = choices.indexOfFirst { it.path == request.path && it.id == request.serverId }.coerceAtLeast(0)
        }
        val provider = button(SpeedTestProviders.all[providerIndex].label, false).apply {
            tag = "speedtest-provider"; DropdownStyle.apply(this@with, this)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        val route = button(choices[routeIndex].label, false).apply {
            tag = "speedtest-route"; DropdownStyle.apply(this@with, this)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        var popup: android.widget.PopupWindow? = null
        val availability = text("", 13, Loft.MUTED).apply { tag = "speedtest-provider-status" }
        val status = text("Не запущен", 13, Loft.MUTED).apply { tag = "speedtest-status" }
        val result = text("↓  — Мбит/с\n↑  — Мбит/с\nHTTPS  — мс", 22, Loft.INK).apply { tag = "speedtest-result" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(8))
            addView(text("Cloudflare · HTTPS", 14, Loft.INK), space(0, 12))
            addView(text("Сервер / маршрут", 13, Loft.INK), space(0, 4)); addView(route, space(0, 8))
            addView(availability, space(0, 8))
            // Same semantic warning palette as LogsPage; never a random accent.
            addView(text("До 10 МиБ + служебный трафик — возможна оплата.", 13, if (Loft.dark) 0xffe5be6c.toInt() else 0xff84580a.toInt())
                .apply { tag = "speedtest-data-warning" }, space(0, 8))
            addView(result.apply {setPadding(dp(16),dp(16),dp(16),dp(16));background=Loft.glass(this@with)}, space(0, 12)); addView(status, space(0, 8)); addView(bar, space(0, 12))
            addView(text("«Начать» запускает тест до 45 секунд, он продолжится в фоне. Отмена — здесь или в уведомлении.", 12, Loft.MUTED), space(0, 8))
            addView(button("Как измеряем и приватность", false).apply { setOnClickListener {
                alert("О тесте скорости", "Cloudflare: 3 HTTPS-отклика, 8 МиБ загрузки и 2 МиБ случайных данных на отдачу; " +
                    "ответ upload до 4 КиБ, дополнительно HTTP/TLS/DNS и трафик туннеля. Один поток; это не максимальная ёмкость линии. " +
                    "Отдача учитывается после ответа сервера. Отклик — медиана до полных HTTPS-заголовков после TLS, не ICMP ping. " +
                    "Cloudflare видит исходящий IP: https://www.cloudflare.com/privacypolicy/ . " +
                    
                    "Адрес сохранённого сервера, включая БС/CSQTT, не подтверждает выходной IP. " +
                    "Для отдельного сохранённого сервера активный VPN должен быть выключен вами; приложение его не останавливает. " +
                    "При изменении сети/маршрута тест прекращается без переключения на другой маршрут. " +
                    "Последний результат хранится в памяти приложения; после завершения процесса он не восстанавливается. " +
                    "45 секунд ограничивают запуск/измерение; освобождение занятого модуля может завершиться позже.")
            } }, space(0, 0))
        }
        val start=button("Начать",true).apply {tag="speedtest-start"}
        val stop=iconButton("stop","Остановить тест").apply {tag="speedtest-stop"}
        val actions=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16),dp(8),dp(16),dp(4))
            addView(start,LinearLayout.LayoutParams(0,dp(48),1f))
            addView(stop,LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
        }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(CompactUi.scroll(this@with,body,.60f),LinearLayout.LayoutParams(-1,-2))
            addView(actions,LinearLayout.LayoutParams(-1,-2))
        }
        val dialog=ThemedDialogBuilder(this,.92f).setTitle("Тест скорости · Beta")
            .setView(content).setNegativeButton("Закрыть",null).create()
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(target: Activity) { if (target === activity) dialog.dismiss() }
            override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(target: Activity) = Unit
            override fun onActivityResumed(target: Activity) = Unit
            override fun onActivityPaused(target: Activity) = Unit
            override fun onActivityStopped(target: Activity) = Unit
            override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
        }
        application.registerActivityLifecycleCallbacks(lifecycle)
        dialog.setOnDismissListener {
            popup?.dismiss(); popup = null
            handler.removeCallbacksAndMessages(null)
            application.unregisterActivityLifecycleCallbacks(lifecycle)
            if (shown === dialog) shown = null
        }
        shown = dialog; dialog.show()
        var localMessage: String? = null
        fun render() {
            val snapshot = SpeedTestService.state.snapshot
            val selected = SpeedTestProviders.all[providerIndex]
            provider.isEnabled = !snapshot.busy; route.isEnabled = !snapshot.busy
            val routeAvailable = choices[routeIndex].available
            availability.text = selected.unavailable ?: if (!routeAvailable) "Выберите доступный сохранённый сервер" else "${selected.label} · короткий замер по выбранному маршруту"
            availability.setTextColor(if (selected.available && routeAvailable) Loft.MUTED else Loft.ERROR)
            start.isEnabled = !snapshot.busy && selected.available && routeAvailable
            stop.isEnabled = snapshot.busy && snapshot.phase != SpeedTestPhase.STOPPING
            stop.alpha=if(stop.isEnabled)1f else .35f
            status.text = localMessage ?: SpeedTestService.progressText(snapshot)
            val measured = snapshot.result
            result.text = if (measured != null) String.format(Locale.forLanguageTag("ru"),
                "↓  %.2f Мбит/с\n↑  %.2f Мбит/с\nHTTPS  %.0f мс", measured.downloadMbps, measured.uploadMbps, measured.httpResponseMs)
                else "↓  — Мбит/с\n↑  — Мбит/с\nHTTPS  — мс"
            snapshot.progress?.let { update ->
                bar.progress = if (snapshot.phase == SpeedTestPhase.COMPLETE) 100 else if (update.limit == 0L)
                    update.sample * 100 / SpeedTestPlan.LATENCY_COUNT else (update.bytes * 100 / update.limit).toInt()
            }
        }
        provider.setOnClickListener {
            if (!SpeedTestService.state.snapshot.busy) {
                popup?.dismiss()
                popup = DropdownPopup.show(this, provider, null, SpeedTestProviders.all.map {
                    DropdownPopup.Item(it.label + if (it.available) "" else " · недоступно")
                }, providerIndex) { index ->
                    if (!SpeedTestService.state.snapshot.busy) {
                        providerIndex = index; provider.text = SpeedTestProviders.all[index].label
                        localMessage = null; render()
                    }
                }
            }
        }
        route.setOnClickListener {
            if (!SpeedTestService.state.snapshot.busy) {
                popup?.dismiss()
                popup = DropdownPopup.show(this, route, null, choices.map { DropdownPopup.Item(it.label) }, routeIndex) { index ->
                    if (!SpeedTestService.state.snapshot.busy) {
                        routeIndex = index; route.text = choices[index].label
                        localMessage = null; render()
                    }
                }
            }
        }
        start.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1943)
                localMessage = "После разрешения уведомлений нажмите «Начать» ещё раз"
                render(); return@setOnClickListener
            }
            val selected = SpeedTestProviders.all[providerIndex]
            val choice = choices[routeIndex]
            localMessage = SpeedTestService.start(this, SpeedTestRequest(selected.id, choice.path, choice.id))
            render()
        }
        stop.setOnClickListener { SpeedTestService.cancel(this, SpeedTestService.state.snapshot.runId); localMessage = null; render() }
        val observe = object : Runnable { override fun run() { if (dialog.isShowing) { render(); handler.postDelayed(this, 250) } } }
        handler.post(observe)
    }
}
