package org.mcrf.client

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*

enum class ServiceDiagnosticsMode(val title: String, val detail: String) {
    WHY("Один сервер", "Выберите сервер и сервисы."),
    COMPARE("Мульти-проверка", "Сервисы проверяются через серверы по очереди."),
    DEGRADATION("Ухудшение соединения", "Задержка и отказы текущего подключения.")
}

/** The two modes share the bounded runner and selected-server private proxy preparation. */
class ServiceDiagnosticsUi(private val activity: MainActivity, private val mode: ServiceDiagnosticsMode) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val work = ServiceDiagnosticsWork()
    private var job: AutoCloseable? = null
    private var closed = false
    private var working = false
    private var generation = 0L
    private var resultsRevision = 0L
    private var anchorGeneration = 0L
    private var pendingAnchor: Pair<String, Int>? = null
    private var anchorObserver: ViewTreeObserver? = null
    private var anchorListener: ViewTreeObserver.OnPreDrawListener? = null
    private val targets = linkedSetOf("github-site")
    private val serverSlots = linkedSetOf<Int>()
    private val servers = activity.catalogue.entries().toList()
    private var bs = false
    private var query = ""
    private var selectingServers = false
    private var selectionCollapsed = false
    private val expandedCategories = mutableSetOf<String>()
    private val expandedResults = mutableSetOf<String>()
    private var measurements = emptyList<ServerServiceMeasurement>()
    private var terminalReason: String? = null
    private var hasRun = false
    private var runSlots = emptySet<Int>()
    private var runTargets = emptySet<String>()
    private lateinit var search: EditText
    private lateinit var selections: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var report: TextView
    private lateinit var reportRows: LinearLayout
    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var action: Button
    private lateinit var cancel: Button
    private lateinit var selectAll: Button
    private var onResult: (String) -> Unit = {}

    fun show(host: LinearLayout? = null, onResult: (String) -> Unit = {}, onExit: () -> Unit = { close() }): Unit = with(activity) {
        if (closed || isFinishing || isDestroyed) return@with
        if (mode == ServiceDiagnosticsMode.DEGRADATION) { ConnectionDegradationUi.show(activity); return@with }
        if (host == null) { ConnectionDiagnosticsUi.show(activity); return@with }
        this@ServiceDiagnosticsUi.onResult = onResult
        servers.indexOfFirst { it.id == prefs.getString("selected", "") }.takeIf { it >= 0 }?.let {
            serverSlots.add(it + 1); bs = isBs(servers[it])
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "service-diagnostics-${mode.name.lowercase()}" }
        search = editor("").apply {
            hint = "Найти сервис"; setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(120)); tag = "service-diagnostics-search"
            CompactUi.fieldSurface(this@with, this)
        }
        status = DiagnosticsUiStyle.status(activity)
        progressBar = DiagnosticsUiStyle.progress(activity)
        selections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        report = text("", 13, Loft.MUTED).apply { tag = "service-diagnostics-report" }
        reportRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            DiagnosticsUiStyle.group(activity, this, "Выбор").apply { addView(search, space(16, 12)); addView(selections) }
            DiagnosticsUiStyle.group(activity, this, "Проверка").apply {
                addView(status); addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
            }
            DiagnosticsUiStyle.group(activity, this, "Итоги").apply { addView(report, space(0, 8)); addView(reportRows) }
        }
        val actionRow = LinearLayout(this).apply {
            isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            action = button("Далее · серверы", true).apply {
                tag = "service-diagnostics-check"; setOnClickListener { if (!selectingServers) chooseServers() else start() }
            }
            cancel = button("Назад", false).apply { setOnClickListener {
                when {
                    working -> job?.close()
                    selectingServers && !selectionCollapsed -> { selectingServers = false; search.setText(""); renderSelection() }
                    else -> onExit()
                }
            } }
            selectAll = button("Проверить все", false).apply {
                tag = "service-diagnostics-all"
                setOnClickListener {
                    if (selectingServers) {
                        servers.forEachIndexed { index, server -> if (isBs(server) == bs &&
                            (query.isBlank() || server.name.contains(query, true) || server.host.contains(query, true))) serverSlots.add(index + 1) }
                        renderSelection()
                    } else {
                        targets.clear(); targets.addAll(ServiceTestCatalog.targets.filter { it.check != DiagnosticCheck.UNSUPPORTED }.map { it.id }); chooseServers()
                    }
                }
            }
            listOf(selectAll, action, cancel).forEach {
                it.minWidth = 0; it.minimumWidth = 0; it.minHeight = dp(48); it.minimumHeight = dp(48)
                it.gravity = Gravity.CENTER; it.setPadding(dp(8), dp(8), dp(8), dp(8)); it.textSize = 12f; it.maxLines = 2
            }
            if(contentWidthDp()<370){
                orientation=LinearLayout.VERTICAL
                addView(action,space(0,8))
                addView(LinearLayout(this@with).apply {
                    addView(selectAll,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
                    addView(cancel,LinearLayout.LayoutParams(0,-2,1f))
                },space(0,0))
            }else{
                addView(selectAll, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(action, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        // Actions belong to the diagnostic content. Keeping them in a separate
        // fixed footer produced an unrelated opaque strip above app navigation.
        (selections.parent as LinearLayout).let { it.addView(actionRow, it.indexOfChild(selections), space(0, 20)) }
        scroller = ScrollView(this).apply {
            addView(content)
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
            clipToPadding = true
            setPadding(0, 0, dp(10), dp(4))
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); renderSelection() }
        })
        host.addView(root, LinearLayout.LayoutParams(-1, -1))
        renderSelection(); renderResults()
    }

    private fun isBs(server: ServerImport.Server) = server.whitelists || server.carrier != null

    private fun renderSelection(): Unit = with(activity) {
        if (closed || !::selections.isInitialized) return@with
        val scrollY = scroller.scrollY
        selections.removeAllViews()
        search.visibility = if (selectionCollapsed) View.GONE else View.VISIBLE
        search.hint = if (selectingServers) "Найти сервер или IP" else "Найти сервис"
        if (selectionCollapsed) {
            selections.addView(button("Изменить выбор · ${targets.size} целей · ${serverSlots.size} серверов", false).apply {
                isEnabled = !working; textSize = 12f
                setOnClickListener { selectionCollapsed = false; selectingServers = false; renderSelection() }
            }, space(0, 0))
        } else if (!selectingServers) {
            selections.addView(text("Сервисы · ${targets.size}", 14, Loft.INK), space(0, 8))
            ServiceTestCatalog.matching(query).forEach { category ->
                val expanded = query.isNotBlank() || category.id in expandedCategories
                selections.addView(DiagnosticsUiStyle.foldHeader(activity, category.title, expanded) {
                    if (!expandedCategories.add(category.id)) expandedCategories.remove(category.id); renderSelection()
                }, space(0, 8))
                if (expanded) category.services.forEach { service -> service.parts.filter { it.check != DiagnosticCheck.UNSUPPORTED }.forEach { target ->
                    selections.addView(DiagnosticsUiStyle.choice(activity, "${service.title} · ${target.title}", target.id in targets,
                        !working, service, "diagnostics-target-${target.id}") {
                        if (!targets.remove(target.id)) targets.add(target.id); renderSelection()
                    }, space(0, 8))
                } }
            }
        } else {
            selections.addView(text("Серверы · ${serverSlots.size}", 14, Loft.INK), space(0, 8))
            lateinit var family: ModeSwitch
            family = ModeSwitch(activity, !bs, { vpn ->
                bs = !vpn
                if (mode == ServiceDiagnosticsMode.WHY) serverSlots.removeAll { slot -> servers.getOrNull(slot - 1)?.let { isBs(it) != bs } != false }
                family.setMode(vpn, true); renderSelection()
            },
                listOf("VPN", "БС"), listOf("", "")).apply { tag = "diagnostics-server-family"; setAvailable(!working) }
            selections.addView(family, space(0, 12))
            val visible = servers.mapIndexed { index, server -> index + 1 to server }.filter { (_, server) ->
                isBs(server) == bs && (query.isBlank() || server.name.contains(query, true) || server.host.contains(query, true))
            }
            if (visible.isEmpty()) selections.addView(text("Нет подходящих серверов", 13, Loft.MUTED))
            visible.forEach { (slot, server) ->
                selections.addView(DiagnosticsUiStyle.choice(activity, DiagnosticsServerLabel.display(server), slot in serverSlots,
                    !working, tagValue = "diagnostics-server-$slot") {
                    if (!serverSlots.remove(slot)) { if (mode == ServiceDiagnosticsMode.WHY) serverSlots.clear(); serverSlots.add(slot) }
                    renderSelection()
                }, space(0, 8))
            }
        }
        if (!hasRun) status.text = "Целей: ${targets.size} · серверов: ${serverSlots.size}"
        progressBar.visibility = if (working) View.VISIBLE else View.GONE
        action.text = if (!selectingServers) "Далее · серверы" else "Проверить"
        action.isEnabled = !working && targets.isNotEmpty() && (!selectingServers || serverSlots.isNotEmpty())
        selectAll.text = if (selectingServers) "Выбрать все" else "Проверить все"
        selectAll.isEnabled = !working
        selectAll.visibility = if (selectionCollapsed || (selectingServers && mode == ServiceDiagnosticsMode.WHY)) View.GONE else View.VISIBLE
        cancel.text = if (working) "Отмена" else if (selectingServers && !selectionCollapsed) "К сервисам" else "Назад"
        main.post { if (!closed) scroller.scrollTo(0, scrollY) }
    }

    private fun chooseServers() {
        if (working || targets.isEmpty()) return
        selectingServers = true; selectionCollapsed = false; search.setText(""); renderSelection()
        scroller.post { if (!closed) scroller.scrollTo(0, 0) }
    }

    private fun start() {
        if (closed || working || targets.isEmpty() || serverSlots.isEmpty()) return
        val app = activity.applicationContext
        val selectedTargets = targets.toSet(); val selectedSlots = serverSlots.toSet(); val stamp = ++generation
        runSlots = selectedSlots; runTargets = selectedTargets
        working = true; selectionCollapsed=true; hasRun = true; measurements = emptyList(); terminalReason = null
        expandedResults.clear(); status.text = "Готовим подключение…"; progressBar.isIndeterminate = true
        renderSelection(); renderResults(preserveScroll = false); scroller.post { if (!closed) scroller.scrollTo(0, 0) }
        try {
            // Each server owns a temporary ingress and every selected target has
            // its own bounded probe. A fixed two-minute screen deadline truncated
            // legitimate multi-server runs before later servers were reached.
            val timeoutMs = (selectedSlots.size.toLong() * (10_000L + selectedTargets.size * 8_000L))
                .coerceIn(120_000L, 900_000L)
            job = work.start(timeoutMs, { token ->
                val initial = DiagnosticsEnvironmentReader.scope(app)
                val value = DiagnosticsServerComparison(DiagnosticsNativeComparisonProvider(app, servers))
                    .run(selectedSlots, selectedTargets, token) { update -> main.post {
                        if (!closed && working && generation == stamp) displayReport(update)
                    } }
                token.check()
                if (DiagnosticsEnvironmentReader.scope(app) != initial) throw DiagnosticsPathChanged()
                value
            }) { outcome -> main.post {
                if (!closed && generation == stamp) {
                    working = false; outcome.value?.let { measurements = it.measurements }
                    terminalReason = if (outcome.value != null) null else DiagnosticsPresentation.stopReason(outcome.issue)
                    status.text = terminalReason ?: "Проверка завершена"
                    renderSelection(); renderResults(); onResult(report.text.toString())
                }
            } }
        } catch (_: Exception) {
            working = false; terminalReason = "Предыдущая проверка ещё завершается"
            status.text = terminalReason; renderSelection(); renderResults()
        }
    }

    private fun displayReport(value: ServerComparisonReport) {
        measurements = value.measurements
        val total = runTargets.size * runSlots.size
        status.text = "Проверено ${measurements.size} из $total"
        progressBar.isIndeterminate = false; progressBar.progress = DiagnosticsPresentation.progress(measurements.size, total)
        renderResults()
    }

    private fun clearAnchorRestore() {
        anchorListener?.let { listener ->
            anchorObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        anchorListener = null; anchorObserver = null; pendingAnchor = null
    }

    private fun renderResults(preserveScroll: Boolean = true): Unit = with(activity) {
        if (closed) return@with
        // A burst of updates shares the anchor from the last laid-out tree;
        // replacement rows must never become the source of a second capture.
        val reuseAnchor = anchorListener != null && anchorGeneration == generation
        val viewport = IntArray(2).also(scroller::getLocationOnScreen)
        val anchors = mutableListOf<Pair<String, Int>>()
        fun collect(view: View) {
            val tag = view.tag as? String
            if (tag?.startsWith("diagnostics-result") == true) {
                val location = IntArray(2).also(view::getLocationOnScreen)
                if (location[1] + view.height > viewport[1] && location[1] < viewport[1] + scroller.height)
                    anchors.add(tag to location[1])
            }
            if (view is android.view.ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        if (preserveScroll && !reuseAnchor && reportRows.isLaidOut && !reportRows.isLayoutRequested) collect(reportRows)
        val anchor = if (!preserveScroll) null else if (reuseAnchor) pendingAnchor else anchors.minByOrNull { it.second }
        clearAnchorRestore()
        val revision = ++resultsRevision
        val stamp = generation
        val entries = DiagnosticsPresentation.comparisonEntries(measurements, runSlots, runTargets, if (hasRun && !working) terminalReason else null)
        val grouped = ServiceTestResults.Group.entries.associateWith { group -> entries.filter { it.group == group } }
        report.text = grouped.entries.joinToString(" · ") { "${it.key.title}: ${it.value.size}" }
        reportRows.removeAllViews()
        runSlots.forEachIndexed { index, slot ->
            val serverEntries = entries.filter { it.serverSlot == slot }
            val server = servers.getOrNull(slot - 1)?.let(DiagnosticsServerLabel::display) ?: "Сервер $slot"
            val serverCard = CompactUi.group(activity, reportRows, server).apply {
                background = CompactUi.historyBackground(activity, index)
                tag = "diagnostics-server-$slot"
            }
            serverCard.addView(text(ServiceTestResults.Group.entries.joinToString(" · ") { group ->
                "${group.title}: ${serverEntries.count { it.group == group }}"
            }, 12, Loft.MUTED), space(0, 8))
            ServiceTestResults.Group.entries.forEach { group ->
                val values = serverEntries.filter { it.group == group }
                val key = "$slot:${group.name}"
                val expanded = key in expandedResults
                serverCard.addView(DiagnosticsUiStyle.foldHeader(activity, "${group.title} · ${values.size}", expanded) {
                    if (!expandedResults.add(key)) expandedResults.remove(key); renderResults()
                }.apply {
                    tag = "diagnostics-results-$slot-${group.name.lowercase()}"
                    setTextColor(DiagnosticsUiStyle.stateColor(when (group) {
                        ServiceTestResults.Group.SUCCESS -> DiagnosticState.AVAILABLE
                        ServiceTestResults.Group.FAILURE -> DiagnosticState.ERROR
                        else -> DiagnosticState.UNAVAILABLE
                    }))
                }, space(0, 8))
                if (expanded) values.forEach entryLoop@{ entry ->
                    val service = ServiceTestCatalog.serviceFor(entry.targetId) ?: return@entryLoop
                    serverCard.addView(DiagnosticsUiStyle.result(activity, service, ServiceTestCatalog.label(entry.targetId).orEmpty(),
                        entry.reason, entry.state, entry.reason).apply { tag = "diagnostics-result-$slot-${entry.targetId}" }, space(0, 8))
                }
            }
        }
        if (hasRun && working && entries.isEmpty()) reportRows.addView(text("Готовим локальный прокси…", 12, Loft.MUTED))
        pendingAnchor = anchor
        anchorGeneration = stamp
        val observer = scroller.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (closed || generation != stamp || resultsRevision != revision || anchorListener !== this) {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    if (anchorListener === this) clearAnchorRestore()
                    return true
                }
                if (scroller.isLayoutRequested || reportRows.isLayoutRequested) return true
                clearAnchorRestore()
                anchor?.let { saved -> reportRows.findViewWithTag<View>(saved.first)?.let { view ->
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    scroller.scrollBy(0, location[1] - saved.second)
                } }
                return true
            }
        }
        anchorObserver = observer; anchorListener = listener
        observer.addOnPreDrawListener(listener)
    }

    override fun close() {
        if (closed) return
        closed = true; generation++; resultsRevision++; clearAnchorRestore()
        job?.close(); work.close(); main.removeCallbacksAndMessages(null)
    }

    companion object {
        fun settings(activity: MainActivity, page: LinearLayout, openMode: (ServiceDiagnosticsMode) -> Unit) {
            page.addView(SettingsUiNodes(activity).actionRow("Диагностика · Beta", "Серверы и доступность сервисов", "arrow", "Открыть диагностику") {
                ConnectionDiagnosticsUi.show(activity)
            }, activity.space(0, 12))
        }
    }
}
