package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton

/** Legacy resource-monitor/size is ignored, not migrated or cleared; mini is the only runtime size. */
/**
 * Main owns UsageFeaturesUi/BetaFeatureRegistry. Add `ResourceMonitorSettingsUi.addTo(activity,host)`
 * to the Beta builder there, and remove ONLY the old vk-resource-monitor-row plus its subtitle
 * from AndroidDeployUi. ENERGY SAVER and the active SPEED TEST require their own Beta entries.
 */
object ResourceMonitorSettingsUi {
    fun addTo(activity: MainActivity, host: LinearLayout) = with(activity) {
        host.addView(SettingsUiNodes(this).actionRow(
            "Монитор нагрузки · Beta", "CPU и память приложения, текущий трафик",
            "arrow", "Открыть монитор нагрузки",
        ) { resourceMonitor.openShortcut() }.apply { tag = "beta-resource-monitor-open" }, space(0, 12))
    }

    internal fun show(activity: MainActivity, monitor: ResourceMonitorController, dismissed: () -> Unit): AlertDialog {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(4))
            tag = "resource-monitor-settings"
        }
        fun text(value: String) = TextView(activity).apply {
            text = value; textSize = 13f; setTextColor(Loft.MUTED)
            setPadding(0, activity.dp(4), 0, activity.dp(4))
        }
        val quiet=Switch(activity).apply {
            text="Включить без плавающего индикатора";tag="resource-monitor-quick-start";activity.tune(this)
            minHeight=activity.dp(48);isChecked=monitor.indicatorHidden
        }
        body.addView(quiet,LinearLayout.LayoutParams(-1,-2))
        fun indicatorButton(label:String,id:String,description:String)=ToggleButton(activity).apply {
            textOn=label;textOff=label;text=label;tag=id;contentDescription=description
            textSize=11f;isAllCaps=false;gravity=Gravity.CENTER;maxLines=2
            StandardButtons.compact(activity,this);textSize=12f;setPadding(activity.dp(6),activity.dp(4),activity.dp(6),activity.dp(4))
            minWidth=0;minimumWidth=0
        }
        val local=indicatorButton("В приложении","resource-monitor-in-app","Плавающий индикатор в приложении")
        val system=indicatorButton("Поверх приложений","resource-monitor-system","Плавающий индикатор поверх приложений")
        body.addView(LinearLayout(activity).apply {
            tag="resource-monitor-indicator-row";gravity=Gravity.CENTER_VERTICAL
            addView(text("Плавающий индикатор"),LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=activity.dp(8)})
            addView(local,LinearLayout.LayoutParams(activity.dp(94),activity.dp(48)).apply {marginEnd=activity.dp(6)})
            addView(system,LinearLayout.LayoutParams(activity.dp(90),activity.dp(48)))
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8);bottomMargin=activity.dp(8)})
        val status = text("").also {
            it.tag = "resource-monitor-status"
            it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            body.addView(it)
        }
        val permissionButton = activity.button("Разрешение Android", false).apply {
            tag = "resource-monitor-overlay-permission"
        }
        var permissionError: String? = null
        var refreshing = false
        fun refresh() {
            refreshing = true
            quiet.isChecked=monitor.indicatorHidden
            local.isChecked = monitor.enabled&&!monitor.indicatorHidden
            system.isChecked = ResourceMonitorOverlayService.running || ResourceMonitorOverlayService.starting
            local.background=Loft.buttonBackground(activity,selected=local.isChecked)
            system.background=Loft.buttonBackground(activity,selected=system.isChecked)
            val permitted = Settings.canDrawOverlays(activity)
            permissionButton.visibility = if (permitted) View.GONE else View.VISIBLE
            if (permitted) permissionError = null
            status.text = when {
                permissionError != null || ResourceMonitorOverlayService.lastError != null -> "Не удалось включить · подробнее в ⓘ"
                ResourceMonitorOverlayService.running && monitor.enabled && !monitor.indicatorHidden -> "Работает в приложении и поверх приложений"
                ResourceMonitorOverlayService.running -> "Работает поверх приложений"
                ResourceMonitorOverlayService.starting -> "Включение…"
                monitor.enabled&&monitor.indicatorHidden -> "Работает без плавающего индикатора"
                monitor.enabled -> "Работает в приложении"
                else -> "Монитор выключен"
            }
            refreshing = false
        }
        fun permission() {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")))
            } catch (_: Exception) {
                permissionError = "Экран разрешений недоступен. Разрешите показ поверх приложений в настройках Android."
                refresh()
            }
        }
        quiet.setOnCheckedChangeListener {_,checked->
            if(!refreshing){
                monitor.setIndicatorHidden(checked)
                if(checked){ResourceMonitorOverlayService.stop(activity);monitor.setEnabled(true)}
                refresh()
            }
        }
        local.setOnCheckedChangeListener { _, checked ->
            if (!refreshing) { if(checked)monitor.setIndicatorHidden(false);monitor.setEnabled(checked); refresh() }
        }
        system.setOnCheckedChangeListener { _, checked ->
            if (!refreshing) {
                if (!checked) ResourceMonitorOverlayService.stop(activity)
                else if (!Settings.canDrawOverlays(activity)) permission()
                else {
                    // Hidden-only monitoring is replaced by the requested HUD;
                    // an explicitly visible in-app HUD stays enabled.
                    if(monitor.indicatorHidden)monitor.setEnabled(false)
                    monitor.setIndicatorHidden(false)
                    ResourceMonitorOverlayService.start(activity)
                }
                refresh()
            }
        }
        permissionButton.setOnClickListener { permission() }
        body.addView(permissionButton, LinearLayout.LayoutParams(-1, -2))
        val metrics = ResourceMonitorMetricsView(activity, monitor::startRecording, monitor::stopRecording, monitor::resetRecording)
        body.addView(metrics, LinearLayout.LayoutParams(-1, -2))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(CompactUi.scroll(activity, body, .42f), LinearLayout.LayoutParams(-1, -2))

        }
        var helpPopup: AlertDialog? = null
        fun help() {
            if (helpPopup?.isShowing == true) return
            val explanation = text(
                "Пассивный замер: CPU/PSS/RSS только процесса приложения, трафик UID. " +
                    "Engine-подпроцессы не входят в CPU и память; UID может учитывать несколько участков VPN-передачи. " +
                    "Монитор не запускает загрузку или тест скорости.\n\n" +
                    "«В приложении» показывает окно внутри MCRF. «Поверх приложений» требует разрешения Android. Оба индикатора можно включить одновременно; они используют общие измерения. " +
                    "Режим включается вручную: после выдачи разрешения включите его здесь; автоматического запуска " +
                    "после разрешения или перезапуска приложения нет.\n\n" +
                    "Системный режим выключается кнопкой ×, из уведомления, при блокировке экрана или закрытии задачи. " +
                    "Уведомление видно, если уведомления разрешены Android. Android может скрывать окно на защищённых экранах.\n\n" +
                    "Окно всегда миниатюрное. Подробные метрики и запись доступны прямо в этом окне. " +
                    "CPU 100% — одно ядро. PSS учитывает долю общих страниц, RSS — все резидентные страницы. " +
                    "Категории памяти Android относятся только к этому процессу; это не размер выделенных куч. " +
                    "Потоки процесса не являются воркерами туннеля. Число воркеров движка недоступно. " +
                    "Счётчики UID накоплены с загрузки устройства; «—» означает отсутствие данных.\n\n" +
                    "Запись начинается со следующего замера и заканчивается на последнем. В приложении фон и " +
                    "перепривязка останавливают запись. Системный режим продолжает её до выключения, блокировки " +
                    "экрана или закрытия задачи. «Частично» — счётчик пропадал или сбрасывался. " +
                    "Запись не создаёт тестовый трафик; её данные хранятся только в памяти." +
                    listOfNotNull(permissionError, ResourceMonitorOverlayService.lastError).distinct()
                        .joinToString(separator = "\n\n", prefix = if (permissionError != null || ResourceMonitorOverlayService.lastError != null) "\n\n" else "")
            ).apply { setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(8)) }
            helpPopup = ThemedDialogBuilder(activity, .65f).setTitle("О мониторе нагрузки")
                .setView(CompactUi.scroll(activity, explanation, .40f)).setNegativeButton("Закрыть", null)
                .create().also { popup ->
                    popup.setOnDismissListener { helpPopup = null }
                    popup.show()
                }
        }
        val title = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - activity.dp(48)
                (getChildAt(0) as TextView).maxWidth = available.coerceAtLeast(1)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(16), activity.dp(8), activity.dp(8), 0)
            addView(TextView(activity).apply {
                text = "Монитор нагрузки"; textSize = 18f; setTextColor(Loft.INK)
                setAccessibilityHeading(true);maxLines=2
            }, LinearLayout.LayoutParams(-2, -2))
            addView(ResourceMonitorUi.icon(activity, true, "О мониторе нагрузки и ограничениях", ::help).apply {
                tag = "resource-monitor-help"
            }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        }
        val dialog = ThemedDialogBuilder(activity, .72f,surface=Loft::controlPopupBackground).setCustomTitle(title)
            .setView(content)
            .create()
        content.addView(LinearLayout(activity).apply {
            tag="resource-monitor-footer";gravity=Gravity.CENTER_VERTICAL
            setPadding(activity.dp(16),activity.dp(8),activity.dp(16),activity.dp(16))
            addView(metrics.actions,LinearLayout.LayoutParams(-2,-2))
            addView(View(activity),LinearLayout.LayoutParams(0,1,1f))
            addView(activity.button("Закрыть",false).apply {setOnClickListener {dialog.dismiss()}},LinearLayout.LayoutParams(-2,activity.dp(48)))
        },LinearLayout.LayoutParams(-1,-2))
        val observer: () -> Unit = { refresh() }
        // Refresh permission visibility on return from Android settings, without starting either mode.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) refresh() }
        var focusObserver: ViewTreeObserver? = null
        ResourceMonitorOverlayService.observe(observer)
        dialog.setOnDismissListener {
            focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            helpPopup?.dismiss(); helpPopup = null
            ResourceMonitorOverlayService.unobserve(observer)
            monitor.bindMetrics(null)
            dismissed()
        }
        monitor.bindMetrics(metrics)
        refresh()
        dialog.show()
        focusObserver = dialog.window?.decorView?.viewTreeObserver
        focusObserver?.addOnWindowFocusChangeListener(focusListener)
        return dialog
    }
}
