package org.mcrf.client

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch

internal object ServerUsageUi {
    fun view(activity: MainActivity): LinearLayout = with(activity) {
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var rowLimit = 30
        var selectedId: String? = null
        fun refresh() {
            rows.removeAllViews()
            val names = activity.catalogue.entries().associate { it.id to it.name }
            val totals = ServerUsage.totals(this).entries.sortedByDescending { it.value.bytes }
            if (totals.isEmpty()) {
                val message = if (ServerUsage.enabled(this)) "Данные появятся после подключения." else "Включите сбор для записи статистики VPN."
                rows.addView(text(message, 14, Loft.MUTED))
                return
            }
            fun serverName(id: String) = if (id == ServerUsage.UNATTRIBUTED) "Без привязки к серверу"
                else names[id] ?: "Удалённый сервер"
            val index = totals.indexOfFirst { it.key == selectedId }.coerceAtLeast(0)
            val current = totals[index].key
            selectedId = current
            val profile = button(serverName(current), false).apply {
                tag = "server-usage-profile"
                setOnClickListener {
                    DropdownPopup.show(activity, this, "Сервер", totals.map { DropdownPopup.Item(serverName(it.key)) }, index) {
                        selectedId = totals[it].key
                        refresh()
                    }
                }
            }
            rows.addView(profile, space(0, 10))
            val colors = totals.take(rowLimit).associate { it.key to ServerUsageChart.receivedColor(it.key) }
            val currentColor = colors[current] ?: ServerUsageChart.receivedColor(current)
            val chart = ServerUsageChart.card(activity, currentColor) { weekly -> ServerUsage.history(activity, current, weekly) }
            rows.addView(chart, space(0, 12))
            totals.take(rowLimit).forEach { (id, item) ->
                val name = serverName(id)
                divider(rows, 0, 8)
                rows.addView(text(name, 15, Loft.INK).apply {
                    val size = dp(8)
                    val dot = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colors.getValue(id))
                        setBounds(0, 0, size, size)
                    }
                    compoundDrawablePadding = dp(8)
                    setCompoundDrawablesRelative(dot, null, null, null)
                }, space(0, 4))
                rows.addView(text("↓ ${TrafficState.bytes(item.received)}  ↑ ${TrafficState.bytes(item.sent)}", 13, Loft.INK), space(0, 4))
                rows.addView(text("Сеансы · ${item.sessions}", 12, Loft.MUTED), space(0, 10))
            }
            if (totals.size > rowLimit) rows.addView(button("Показать ещё", false).apply {
                setOnClickListener { rowLimit += 30; refresh() }
            })
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "server-usage"
            setPaddingRelative(dp(16), dp(4), dp(16), dp(8))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                addView(Switch(activity).apply {
                    text = "Собирать статистику"
                    tune(this)
                    isChecked = ServerUsage.enabled(activity)
                    setOnCheckedChangeListener { _, checked -> ServerUsage.setEnabled(activity, checked); refresh() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton("refresh", "Обновить статистику").apply {
                    setOnClickListener { refresh() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
            }, space(0, 6))
            addView(text("Данные хранятся на устройстве. Трафик смешанных маршрутов показан отдельно. Для движков без счётчиков доступны сеансы.", 12, Loft.MUTED), space(0, 16))
            addView(rows, space(0, 12))
            addView(button("Очистить статистику", false).apply {
                setOnClickListener {
                    ThemedDialogBuilder(activity).setTitle("Очистить статистику?")
                        .setMessage("Сохранённые объёмы трафика и число сеансов будут удалены.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Очистить") { _, _ -> ServerUsage.clear(activity); refresh() }
                        .show()
                }
            })
            refresh()
        }
    }
}
