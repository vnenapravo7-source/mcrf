package org.mcrf.client

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView

object TransportComparisonUi {
    fun show(activity: MainActivity) = show(activity, activity.connectionSectionWhitelists())

    fun show(activity: MainActivity, whitelists: Boolean, visibilityChanged: () -> Unit = { activity.render() }) = with(activity) {
        if (whitelists) return@with
        val surface = findViewById<ViewGroup>(android.R.id.content)
        if (surface.findViewWithTag<View>("transport-comparison-section") != null) return@with
        val previous = (0 until surface.childCount).map { surface.getChildAt(it) }.map {
            Triple(it, it.visibility, it.importantForAccessibility)
        }
        var closeSection: () -> Unit = {}
        val screen = TransportComparisonScreen(activity) { closeSection() }
        var token: TransportComparisonCancellation? = null
        var working = false
        var cancelled = false
        var finalReport: TransportComparisonReport? = null
        var compared = emptyList<ServerImport.Server>()
        fun alive() = screen.isAttachedToWindow && !isFinishing && !isDestroyed
        fun refreshConnection() = screen.connection(
            TransportComparisonNativeProvider.connectionBlocked(applicationContext),
            TransportComparisonNativeProvider.ownsConnection(), McrfVpnService.cleanupPending())
        val connectionPoll = object : Runnable {
            override fun run() { if (alive()) { refreshConnection(); screen.postDelayed(this, 1000) } }
        }
        val backCallback = if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { closeSection() } else null
        var released = false
        fun release() {
            if (released) return
            released = true; token?.close(); screen.removeCallbacks(connectionPoll)
            if (Build.VERSION.SDK_INT >= 33 && backCallback != null) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
            previous.forEach { (view, visibility, accessibility) -> view.visibility = visibility; view.importantForAccessibility = accessibility }
        }
        closeSection = { release(); (screen.parent as? ViewGroup)?.removeView(screen) }
        screen.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { release() }
        })
        screen.onSelectionChanged = { finalReport = null }
        screen.onDisconnect = {
            // Only an explicit action disconnects the user's VPN; this never starts a test.
            if (!working) try {
                if (TransportComparisonNativeProvider.ownsConnection())
                    startService(Intent(activity, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                else startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: Exception) { toast("Отключите VPN в системных настройках.") }
        }
        screen.onStop = { cancelled = true; token?.close(); screen.message("Остановка…") }
        screen.onRestore = { showHidden(activity, false) { closeSection(); visibilityChanged() } }
        screen.onHide = hide@{
            val report = finalReport ?: return@hide
            val expected = compared.toList()
            val ids = TransportComparisonPlan.hideExceptWinner(report, expected)
            if (ids.isEmpty() || working) return@hide
            ThemedDialogBuilder(this).setTitle("Скрыть проигравших: ${ids.size}?")
                .setMessage("Их можно вернуть через «Показать скрытые серверы».")
                .setNegativeButton("Отмена", null).setPositiveButton("Скрыть") { _, _ ->
                    if (!alive() || working || finalReport !== report) return@setPositiveButton
                    working = true; screen.saving(true)
                    Thread({
                        val result = runCatching {
                            val fresh = Connections(applicationContext).apply { load() }.entries()
                            check(expected.all { old -> fresh.any {
                                it.id == old.id && it.original == old.original &&
                                    it.outbound?.toString() == old.outbound?.toString() && ConnectionSections.belongs(it, false)
                            } }) { "Серверы изменились. Повторите сравнение." }
                            ServerVisibility.update(applicationContext, hide = ids)
                        }
                        runOnUiThread {
                            if (!alive()) return@runOnUiThread
                            working = false; screen.saving(false)
                            result.fold({ closeSection(); visibilityChanged() },
                                { screen.message("Не удалось скрыть серверы. Повторите сравнение.") })
                        }
                    }, "mcrf-hide-compared-servers").start()
                }.show()
        }
        screen.onRun = run@{
            if (working) return@run
            refreshConnection()
            if (TransportComparisonNativeProvider.connectionBlocked(applicationContext)) {
                screen.message("Отключите VPN перед проверкой."); return@run
            }
            val server = screen.origin ?: return@run
            val chosen = screen.chosen.toList()
            try { TransportComparisonPlan.validate(server, chosen) }
            catch (_: Exception) { screen.message("Выберите 2–8 транспортов одного сервера."); return@run }
            val owned = TransportComparisonCancellation(); token = owned
            working = true; cancelled = false; finalReport = null; compared = chosen; screen.working(true)
            Thread({
                try {
                    val report = TransportComparisonNativeProvider.run(applicationContext, server, chosen, owned) { update ->
                        runOnUiThread { if (alive() && token === owned && !cancelled) screen.publish(update, chosen) }
                    }
                    owned.check()
                    runOnUiThread { if (alive() && token === owned && !cancelled) {
                        finalReport = report; screen.publish(report, chosen)
                    } }
                } catch (_: Exception) {
                    runOnUiThread { if (alive() && token === owned) {
                        screen.message(if (cancelled) "Проверка остановлена." else "Не удалось запустить замер. Проверьте сеть и движок sing-box.")
                    } }
                } finally {
                    owned.close()
                    runOnUiThread { if (alive() && token === owned) {
                        working = false; screen.working(false); refreshConnection()
                        if (cancelled) { finalReport = null; screen.message("Проверка остановлена.") }
                    } }
                }
            }, "mcrf-transport-comparison").start()
        }
        previous.forEach { (view, _, _) ->
            view.visibility = View.INVISIBLE; view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        surface.addView(screen, ViewGroup.LayoutParams(-1, -1)); screen.requestFocus()
        screen.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop, dp(16) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
            }
            insets
        }
        screen.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback)
        refreshConnection(); screen.postDelayed(connectionPoll, 1000)
        Thread({
            val loaded = runCatching {
                val entries = Connections(applicationContext).apply { load() }.entries()
                    .filter { UsageFeatures.connection(it, UsageFeaturesUi.selected(activity)) }
                TransportComparisonPlan.groups(ServerVisibility.visible(entries, ServerVisibility.hidden(applicationContext)), false)
            }
            runOnUiThread {
                if (alive()) loaded.fold(screen::bindGroups, { screen.message("Не удалось прочитать серверы. Откройте раздел ещё раз.") })
            }
        }, "mcrf-transport-choices").start()
    }

    fun showHidden(activity: MainActivity, whitelists: Boolean, changed: () -> Unit = { activity.render() }) = with(activity) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val status = text("Загрузка…", 13, Loft.MUTED); content.addView(status)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "transport-hidden-rows" }
        // Natural height for 0..6 rows; larger lists scroll within the first six rows.
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val naturalRows = (0 until minOf(rows.childCount, 6)).sumOf { index ->
                    val row = rows.getChildAt(index)
                    val width = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                    row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    val margins = row.layoutParams as? ViewGroup.MarginLayoutParams
                    row.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
                }
                val screenLimit = (resources.displayMetrics.heightPixels * .55f).toInt()
                val parentLimit = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) screenLimit else MeasureSpec.getSize(heightSpec)
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(minOf(naturalRows, screenLimit, parentLimit), MeasureSpec.AT_MOST))
            }
        }.apply { addView(rows); visibility = View.GONE }
        content.addView(scroll)
        val selected = linkedSetOf<String>()
        val dialog = ThemedDialogBuilder(this).setTitle("Скрытые серверы · ${if (whitelists) "БС" else "VPN"}")
            .setView(content).setNegativeButton("Отмена", null).setPositiveButton("Вернуть выбранные", null).create()
        dialog.show()
        val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        var saving = false
        save.setOnClickListener {
            if (saving || selected.isEmpty()) return@setOnClickListener
            val restore = selected.toSet(); saving = true; save.isEnabled = false
            Thread({
                val result = runCatching { ServerVisibility.update(applicationContext, restore = restore) }
                runOnUiThread {
                    if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
                    saving = false; save.isEnabled = selected.isNotEmpty()
                    result.fold({ dialog.dismiss(); changed() }, { status.text = "Не удалось вернуть серверы." })
                }
            }, "mcrf-restore-servers").start()
        }
        Thread({
            val result = runCatching {
                val hidden = ServerVisibility.hidden(applicationContext)
                ConnectionSections.entries(Connections(applicationContext).apply { load() }.entries(), whitelists).filter { it.id in hidden }
            }
            runOnUiThread {
                if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
                result.fold({ entries ->
                    status.text = if (entries.isEmpty()) "Скрытых серверов нет." else "Отметьте серверы, которые нужно вернуть."
                    entries.forEach { entry -> rows.addView(CheckBox(this).apply {
                        text = ClientEvents.redact("${entry.name}\n${entry.host}:${entry.port}"); textSize = 14f; minHeight = dp(48)
                        setTextColor(Loft.INK); buttonTintList = ColorStateList.valueOf(Loft.ACCENT)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(entry.id) else selected.remove(entry.id)
                            save.isEnabled = !saving && selected.isNotEmpty()
                        }
                    }, space(0, 6)) }
                    scroll.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
                    scroll.isVerticalScrollBarEnabled = TransportComparisonPlan.hiddenRowsScroll(entries.size)
                    scroll.requestLayout()
                }, { status.text = "Не удалось прочитать скрытые серверы." })
            }
        }, "mcrf-hidden-servers").start()
    }
}
