package org.mcrf.client

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** A/B-only presentation. The provider, profile catalogue and visibility writes stay outside. */
internal class TransportComparisonScreen(private val activity: MainActivity, private val back: () -> Unit) : LinearLayout(activity) {
    var onRun: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onDisconnect: () -> Unit = {}
    var onRestore: () -> Unit = {}
    var onHide: () -> Unit = {}
    var onSelectionChanged: () -> Unit = {}
    var origin: ServerImport.Server? = null; private set
    var chosen = emptyList<ServerImport.Server>(); private set
    private var activeGroup = -1
    private var busy = false
    private var blocked = true
    private val selected = linkedSetOf<String>()
    private val headers = mutableListOf<View>()
    private val choiceRows = mutableListOf<Pair<View, CheckBox>>()
    private val groupBodies = mutableListOf<LinearLayout>()
    private val groupsView = column().apply { tag = "transport-comparison-hosts" }
    private val status = label("Загрузка серверов…", 13, Loft.MUTED)
    private val selectionCount = label("Выберите 2–8 транспортов одного сервера", 13, Loft.MUTED)
    private val warning = panel().apply { tag = "transport-comparison-vpn-warning" }
    private val disconnect = activity.button("Отключить VPN", false).apply {
        tag = "transport-comparison-disconnect"; setOnClickListener { onDisconnect() }
    }
    private val run = activity.button("Сравнить", true).apply { tag = "transport-comparison-run"; setOnClickListener { onRun() } }
    private val stop = activity.button("Остановить", false).apply { tag = "transport-comparison-cancel"; setOnClickListener { onStop() } }
    private val progressPanel = panel().apply { tag = "transport-comparison-progress-panel"; visibility = GONE }
    private val progressText = label("0 из 0", 14, Loft.INK).apply {
        tag = "transport-comparison-progress-text"; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val remaining = label("", 12, Loft.MUTED).apply { tag = "transport-comparison-remaining" }
    private val current = label("", 13, Loft.INK).apply { tag = "transport-comparison-current"; maxLines = 2 }
    private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        tag = "transport-comparison-progress"; isIndeterminate = false
        progressTintList = ColorStateList.valueOf(Loft.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(Loft.FIELD)
    }
    private val results = column().apply { tag = "transport-comparison-results" }
    private val verdict = label("", 14, Loft.INK).apply { tag = "transport-comparison-verdict"; visibility = GONE }
    private val hide = activity.button("Скрыть проигравших", false).apply {
        tag = "transport-comparison-hide"; visibility = GONE; setOnClickListener { onHide() }
    }
    private val restore = activity.button("Показать скрытые серверы", false).apply {
        tag = "transport-comparison-restore"; setOnClickListener { onRestore() }
    }
    private val content = column()
    private val scroll = ScrollView(activity).apply {
        tag = "transport-comparison-scroll"; isFillViewport = true; clipToPadding = false
        addView(content)
    }
    private var clockRemaining = 0L
    private var clockUpdated = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (!busy || !isAttachedToWindow) return
            val seconds = ((clockRemaining - (SystemClock.elapsedRealtime() - clockUpdated)).coerceAtLeast(0) + 999) / 1000
            remaining.text = "Осталось до ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL; tag = "transport-comparison-section"
        setBackgroundColor(Loft.BACKGROUND); isFocusableInTouchMode = true
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false; minimumHeight = dp(56)
            addView(activity.iconButton("back", "Назад").apply { setOnClickListener { back() } }, LayoutParams(dp(48), dp(48)))
            addView(label("A/B транспортов", 20, Loft.INK).apply {
                setTypeface(typeface, Typeface.BOLD); setAccessibilityHeading(true)
            }, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        })
        addView(scroll, LayoutParams(-1, 0, 1f))
        warning.addView(label("Для сравнения отключите VPN", 14, Loft.INK))
        warning.addView(disconnect, gap(8))
        content.addView(warning, gap(16))
        content.addView(selectionCount, gap(16))
        content.addView(groupsView, gap(8))
        content.addView(status, gap(8))
        content.addView(LinearLayout(activity).apply {
            isBaselineAligned = false
            addView(run, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(stop, LayoutParams(0, -2, 1f))
        }, gap(16))
        progressPanel.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(progressText, LayoutParams(0, -2, 1f))
            addView(remaining, LayoutParams(-2, -2))
        })
        progressPanel.addView(bar, LayoutParams(-1, dp(8)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        progressPanel.addView(current)
        content.addView(progressPanel, gap(16))
        content.addView(verdict, gap(16))
        content.addView(results, gap(8))
        content.addView(hide, gap(8))
        content.addView(restore, gap(16))
        updateControls()
    }

    fun bindGroups(groups: List<List<ServerImport.Server>>) {
        groupsView.removeAllViews(); headers.clear(); groupBodies.clear(); choiceRows.clear()
        selected.clear(); chosen = emptyList(); origin = null; activeGroup = -1
        groups.forEachIndexed { index, group ->
            val container = panel()
            val title = TransportComparisonPlan.groupTitle(group)
            val address = runCatching { TransportComparisonPlan.host(group.first()) }.getOrDefault(group.first().host)
            val header = LinearLayout(activity).apply {
                minimumHeight = dp(56); gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
                tag = "transport-comparison-group-$index"; setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(Loft.SURFACE, ripple = true)
                val labels = column()
                labels.addView(label(title.substringBeforeLast(" · "), 15, Loft.INK).apply { setTypeface(typeface, Typeface.BOLD) })
                labels.addView(label(address, 12, Loft.MUTED), gap(4))
                addView(labels, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(label("⌄", 22, Loft.MUTED))
                contentDescription = "$title, раскрыть варианты"
            }
            val body = column().apply { visibility = GONE; tag = "transport-comparison-group-body-$index" }
            headers += header; groupBodies += body
            header.setOnClickListener {
                if (busy) return@setOnClickListener
                if (activeGroup == index) {
                    body.visibility = if (body.visibility == VISIBLE) GONE else VISIBLE
                } else selectGroup(index, group)
                (header.getChildAt(1) as TextView).text = if (body.visibility == VISIBLE) "⌃" else "⌄"
            }
            // The group owns 12dp padding; header/rows share the same rounded hit area.
            container.addView(header); container.addView(body, gap(8))
            groupsView.addView(container, gap(if (index == 0) 0 else 12))
        }
        status.text = if (groups.isEmpty()) "Нет серверов с двумя доступными транспортами." else ""
        status.visibility = if (groups.isEmpty()) VISIBLE else GONE
        if (groups.isNotEmpty()) selectGroup(0, groups.first())
        updateControls()
    }

    private fun selectGroup(index: Int, group: List<ServerImport.Server>) {
        activeGroup = index; origin = group.first(); selected.clear(); choiceRows.clear()
        groupBodies.forEachIndexed { i, body ->
            body.visibility = if (i == index) VISIBLE else GONE
            (headers[i] as LinearLayout).let { header ->
                header.background = rounded(if (i == index) Loft.mix(Loft.SURFACE, Loft.ACCENT, .12f) else Loft.SURFACE, true)
                (header.getChildAt(1) as TextView).text = if (i == index) "⌃" else "⌄"
            }
        }
        val body = groupBodies[index]; body.removeAllViews()
        group.filter(TransportComparisonPlan::supported).forEachIndexed { slot, entry ->
            if (selected.size < TransportComparisonPlan.MAX_PROFILES) selected += entry.id
            val check = CheckBox(activity).apply {
                isChecked = entry.id in selected; isClickable = false; isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList = ColorStateList.valueOf(Loft.ACCENT)
            }
            val row = LinearLayout(activity).apply {
                tag = "transport-comparison-choice-${entry.id}"; minimumHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
                setPadding(dp(12), dp(8), dp(4), dp(8)); background = rounded(Loft.FIELD, true)
                addView(column().apply {
                    addView(label(safeName(entry), 14, Loft.INK))
                    addView(label("${TransportComparisonPlan.label(entry, slot + 1).substringAfter(" · ")} · ${entry.port}", 12, Loft.MUTED), gap(4))
                }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(check, LayoutParams(dp(44), dp(48)))
                contentDescription = "${safeName(entry)}, ${entry.host}:${entry.port}"
                isSelected = check.isChecked
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    if (!check.isChecked && selected.size == TransportComparisonPlan.MAX_PROFILES) {
                        activity.toast("Можно выбрать до 8 транспортов"); return@setOnClickListener
                    }
                    check.isChecked = !check.isChecked; isSelected = check.isChecked
                    if (check.isChecked) selected += entry.id else selected -= entry.id
                    chosen = group.filter { it.id in selected }
                    selectionChanged()
                }
            }
            choiceRows += row to check; body.addView(row, gap(if (slot == 0) 0 else 8))
        }
        chosen = group.filter { it.id in selected }; selectionChanged()
    }

    private fun selectionChanged() {
        selectionCount.text = "Выбрано ${chosen.size} · от 2 до 8 транспортов"
        progressPanel.visibility = GONE; results.removeAllViews(); verdict.visibility = GONE; hide.visibility = GONE
        updateControls(); onSelectionChanged()
    }

    fun connection(blocked: Boolean, owns: Boolean, cleanup: Boolean) {
        this.blocked = blocked; warning.visibility = if (blocked) VISIBLE else GONE
        disconnect.text = if (owns) "Отключить VPN" else "Открыть настройки VPN"
        disconnect.isEnabled = !busy && !cleanup; updateControls()
    }

    fun working(value: Boolean) {
        busy = value; updateControls(); removeCallbacks(ticker)
        if (value) {
            status.visibility = GONE; progressPanel.visibility = VISIBLE
            clockRemaining = TransportComparisonPlan.BUDGET_MS; clockUpdated = SystemClock.elapsedRealtime()
            progressText.text = "0 из ${chosen.size * TransportComparisonPlan.REPEATS}"
            current.text = "Подготовка движка…"; bar.max = chosen.size * TransportComparisonPlan.REPEATS; bar.progress = 0
            results.removeAllViews(); verdict.visibility = GONE; hide.visibility = GONE
            post(ticker)
            scroll.post { scroll.smoothScrollTo(0, progressPanel.top) }
        } else remaining.text = ""
    }

    fun saving(value: Boolean) { busy = value; updateControls() }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun message(value: String) { status.text = value; status.visibility = VISIBLE }

    fun publish(report: TransportComparisonReport, profiles: List<ServerImport.Server>) {
        progressPanel.visibility = VISIBLE
        progressText.text = "${report.completed} из ${report.total}"
        bar.max = report.total.coerceAtLeast(1); bar.progress = report.completed
        clockRemaining = report.remainingMs; clockUpdated = SystemClock.elapsedRealtime()
        report.currentSlot?.let { slot -> profiles.getOrNull(slot - 1)?.let { current.text = safeName(it) } }
        results.removeAllViews()
        val green = Loft.pingColor(0L)
        report.results.filter { it.attempted > 0 }.forEach { result ->
            val entry = profiles.getOrNull(result.slot - 1) ?: return@forEach
            val winner = report.suggestedSlot == result.slot
            val latency = result.medianMs?.let { "$it мс" } ?: "Нет ответа"
            results.addView(panel(if (winner) Loft.mix(Loft.SURFACE, green, .13f) else Loft.SURFACE).apply {
                tag = "transport-comparison-result-${result.slot}"
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
                    addView(label(safeName(entry), 14, if (winner) green else Loft.INK).apply {
                        maxLines = 2; setTypeface(typeface, if (winner) Typeface.BOLD else Typeface.NORMAL)
                    }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
                    addView(label(latency, 14, if (winner) green else if (result.successes == 0) Loft.ERROR else Loft.INK))
                })
                addView(label("${if (winner) "Лучший · " else ""}${result.successes}/${result.attempted} успешно · ${TransportComparisonPlan.label(entry, result.slot).substringAfter(" · ")}", 12,
                    if (winner) green else Loft.MUTED), gap(6))
            }, gap(8))
        }
        if (report.complete || report.exhausted) {
            current.text = if (report.complete) "Проверка завершена" else "Проверка остановлена"
            verdict.visibility = VISIBLE
            verdict.text = when {
                report.exhausted -> report.stopReason ?: "Время проверки истекло"
                report.suggestedSlot != null -> "Лучший транспорт выделен зелёным"
                else -> "Стабильного транспорта нет"
            }
            verdict.setTextColor(if (report.suggestedSlot != null) green else Loft.MUTED)
            hide.visibility = if (TransportComparisonPlan.hideExceptWinner(report, profiles).isNotEmpty()) VISIBLE else GONE
        }
    }

    private fun updateControls() {
        run.isEnabled = !busy && !blocked && chosen.size in 2..TransportComparisonPlan.MAX_PROFILES
        stop.isEnabled = busy; stop.visibility = if (busy) VISIBLE else GONE
        restore.isEnabled = !busy; hide.isEnabled = !busy
        headers.forEach { it.isEnabled = !busy }
        choiceRows.forEach { (row, check) -> row.isEnabled = !busy; check.isEnabled = !busy }
    }

    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }
    private fun dp(value: Int) = activity.dp(value)
    private fun column() = LinearLayout(activity).apply { orientation = VERTICAL }
    private fun label(value: String, size: Int, color: Int) = activity.text(value, size, color)
    private fun gap(top: Int) = LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun rounded(color: Int, ripple: Boolean = false): android.graphics.drawable.Drawable {
        fun shape(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat() }
        return if (ripple) RippleDrawable(ColorStateList.valueOf(Loft.ripple()), shape(color), shape(android.graphics.Color.WHITE)) else shape(color)
    }
    private fun panel(color: Int = Loft.SURFACE) = column().apply {
        setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(Loft.translucent(color))
    }
    private fun safeName(entry: ServerImport.Server) = ClientEvents.redact(entry.name).replace(Regex("[\\r\\n\\t]"), " ").take(72)
}
