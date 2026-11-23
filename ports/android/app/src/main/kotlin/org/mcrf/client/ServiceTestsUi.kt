package org.mcrf.client

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

/** Selection survives search and expansion; search never silently deselects. */
class ServiceTestsSelection {
    private val ids = linkedSetOf<String>()
    val selected: Set<String> get() = ids.toSet()
    fun selectAll() {ids.addAll(ServiceTestCatalog.targets.map {it.id})}
    fun toggle(targets: List<ServiceTestTarget>) {
        val keys = targets.map { it.id }
        require(keys.all { ServiceTestCatalog.find(it) != null })
        if (keys.all { it in ids }) ids.removeAll(keys.toSet()) else ids.addAll(keys)
    }
}

/** Content scrolls as one page; Check/Cancel stay reachable. Dismiss, background
 * and Activity destruction cancel owned probes. */
class ServiceTestsUi(private val activity: MainActivity) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val selection = ServiceTestsSelection()
    private val expandedCategories = mutableSetOf<String>()
    private val expandedServices = mutableSetOf<String>()
    private val collapsedSearchCategories = mutableSetOf<String>()
    private val collapsedSearchServices = mutableSetOf<String>()
    private var query = ""
    private var dialog: Dialog? = null
    private var closed = false
    private var running = false
    private var run: ServiceTestRunner.Run? = null
    @Volatile private var probe: RealServiceProbe? = null
    private var session: DiagnosticsProbeSession? = null
    private val preparation=ServiceDiagnosticsWork()
    private var preparing:AutoCloseable?=null
    private var generation=0L
    private val runner = ServiceTestRunner(ServiceProbe { request, token -> requireNotNull(probe).execute(request, token) },budgetMs=120_000)
    private var results = emptyMap<Pair<String, DiagnosticPath>, DiagnosticResult>()
    private val resultViews = linkedMapOf<Pair<String,DiagnosticPath>, TextView>()
    private val summaryViews = linkedMapOf<String, Pair<List<ServiceTestTarget>, TextView>>()
    private lateinit var overall:TextView
    private lateinit var progressBar:ProgressBar
    private lateinit var list: LinearLayout
    private lateinit var scroller: ScrollView
    private var renderRevision = 0
    private lateinit var count: TextView
    private lateinit var check: Button
    private lateinit var cancel: Button
    private lateinit var all: Button
    private lateinit var resultToggle:Button
    private lateinit var selectionControls:LinearLayout
    private lateinit var selectionSection:LinearLayout
    private lateinit var resultsSection:LinearLayout
    private var grouped=false
    private var onResult:(String)->Unit={}

    fun show(host:LinearLayout?=null,onResult:(String)->Unit={},onExit:()->Unit={close()}): Unit = with(activity) {
        this@ServiceTestsUi.onResult=onResult
        if (closed || dialog != null || isFinishing || isDestroyed) return@with
        val surface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if(host==null) {setPadding(dp(16),dp(16),dp(16),dp(16));background=Loft.popupBackground(activity)}
            tag = "service-tests-screen"
        }
        if(host==null)surface.addView(text("Тесты сервисов · Beta", 20, Loft.INK).apply {gravity=Gravity.START})
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        content.addView(text("Сравните доступность напрямую и через MCRF.", 12, Loft.MUTED), space(0, 12))
        val selectionCard=DiagnosticsUiStyle.group(activity,content,"Сервисы")
        selectionSection=selectionCard
        selectionControls=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        selectionCard.addView(selectionControls)
        val search = editor("").apply {
            hint="Поиск сервиса"
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
            setSingleLine(true); tag = "service-tests-search"
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
            CompactUi.fieldSurface(this@with,this)
        }
        selectionControls.addView(search, space(0,12))
        selectionCard.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
            all=button("Проверить все",false).apply {tag="service-tests-all";setOnClickListener {selection.selectAll();start()}}
            addView(all,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
            resultToggle=button("Все результаты",false).apply {tag="service-tests-results";setOnClickListener {
                grouped=!grouped;text=if(grouped)"Выбор сервисов"else"Все результаты";renderList()
            }}
            addView(resultToggle,LinearLayout.LayoutParams(0,-2,1f))
            listOf(all,resultToggle).forEach {it.minWidth=0;it.minimumWidth=0;it.minimumHeight=dp(48);it.gravity=Gravity.CENTER;it.textSize=12f;it.setPadding(dp(8),dp(8),dp(8),dp(8))}
        },space(0,0))
        count = DiagnosticsUiStyle.status(activity).apply { tag = "service-tests-count" }
        progressBar=DiagnosticsUiStyle.progress(activity)
        overall=DiagnosticsUiStyle.status(activity).apply {
            tag="service-tests-overall";visibility=View.GONE
        }
        DiagnosticsUiStyle.group(activity,content,"Проверка").apply {
            addView(count)
            addView(progressBar,LinearLayout.LayoutParams(-1,dp(4)).apply {topMargin=dp(8)})
            addView(overall,space(8,0))
        }
        val actions=LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL;isBaselineAligned=false
            check = button("Проверить", true).apply { tag = "service-tests-check"; setOnClickListener { start() } }
            cancel = button("Закрыть", false).apply { tag = "service-tests-cancel"; setOnClickListener {
                if (running) {preparing?.close();run?.cancel();session?.close()} else onExit()
            } }
            listOf(check,cancel).forEach {it.gravity=Gravity.CENTER;it.minimumHeight=dp(48);it.minWidth=0;it.minimumWidth=0;it.textSize=13f;it.setPadding(dp(4),dp(4),dp(4),dp(4))}
            addView(check, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        selectionCard.addView(list,space(12,0))
        resultsSection=DiagnosticsUiStyle.group(activity,content,"Результаты").apply {visibility=View.GONE}
        scroller = ScrollView(this).apply { isFillViewport = false;clipToPadding=false;setPadding(0,0,0,dp(4));addView(content) }
        surface.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        surface.addView(actions,space(16,0))
        if(host!=null) {
            host.addView(surface,LinearLayout.LayoutParams(-1,-1))
        } else {
        val windowDialog = Dialog(this)
        windowDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog = windowDialog
        windowDialog.setContentView(surface)
        windowDialog.setOnDismissListener { close() }
        windowDialog.show()
        windowDialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(.5f)
            setLayout(minOf(resources.displayMetrics.widthPixels-dp(32),dp(560)), (resources.displayMetrics.heightPixels * DiagnosticsUiStyle.MAX_HEIGHT_FRACTION).toInt())
        }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty(); collapsedSearchCategories.clear(); collapsedSearchServices.clear()
                scroller.scrollTo(0, 0); renderList()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderList()
    }

    private fun start() {
        if (running || closed || selection.selected.isEmpty()) return
        try {
            val owned = DiagnosticsProbeSession(activity.applicationContext)
            session=owned;run=null
            val stamp=++generation
            running = true; grouped=false; results = emptyMap(); renderList()
            preparing=preparation.start(30_000,{token->
                val attachment=token.attach(owned)
                try {owned.prepare().also {token.check()}}finally {attachment.close()}
            }) {outcome->main.post {
                if(closed||generation!=stamp){owned.close();return@post}
                if(outcome.value==null||owned.isClosed()) {
                    owned.close();running=false;renderList()
                    overall.visibility=View.VISIBLE
                    overall.text=if(outcome.issue==DiagnosticIssue.CANCELLED||outcome.value!=null)"Отменено"else"Не удалось подготовить проверку. Повторите позже."
                    return@post
                }
                probe=outcome.value
                try {run = runner.start(selection.selected) { report ->
                if(report.complete)owned.close()
                main.post {
                    if (!closed&&generation==stamp) {
                        results = report.results.associateBy { it.targetId to it.path }
                        running = !report.complete
                        // Progress changes text in place; rebuilding focused rows resets scroll/focus.
                        if (report.complete) {
                            grouped=true;renderList()
                            onResult(ServiceTestResults.grouped(report.results).entries.joinToString("\n\n") {(group,rows)->
                                "${group.title} · ${rows.size}\n"+rows.joinToString("\n") {row->
                                    "${ServiceTestCatalog.label(row.targetId)} · ${if(row.path==DiagnosticPath.DIRECT)"Напрямую"else"MCRF"}: ${describe(row)}"
                                }
                            })
                        } else renderList()
                    }
                }
                }} catch(_:Exception) {owned.close();running=false;renderList()}
            }}
        } catch (_: Exception) {
            session?.close();running = false; renderList()
            activity.alert("Тесты сервисов", "Не удалось начать проверку. Повторите позже.")
        }
    }

    private fun renderList() {
        SettingsSectionUi.read({renderListContent()}) {
            if(!closed&&::list.isInitialized) {
                list.removeAllViews()
                list.addView(activity.text("Не удалось показать категорию. Выбор сервисов сохранён.",13,Loft.ERROR))
                list.addView(activity.button("Повторить",false).apply {setOnClickListener {renderList()}})
            }
        }
    }
    private fun renderListContent(): Unit = with(activity) {
        if (closed || !::list.isInitialized) return@with
        val revision = ++renderRevision
        val scrollY = scroller.scrollY
        count.text = "Целей: ${selection.selected.size}"
        selectionControls.visibility=if(running||grouped)View.GONE else View.VISIBLE
        val showingResults=running||grouped
        val destination=if(showingResults)resultsSection else selectionSection
        if(list.parent!==destination) {
            (list.parent as? android.view.ViewGroup)?.removeView(list)
            destination.addView(list,space(if(showingResults)0 else 12,0))
        }
        resultsSection.visibility=if(showingResults)View.VISIBLE else View.GONE
        check.isEnabled = !running && selection.selected.isNotEmpty()
        all.isEnabled=!running
        resultToggle.isEnabled=!running&&results.isNotEmpty()
        resultToggle.text=if(grouped)"Выбор сервисов"else"Все результаты"
        cancel.text = if (running) "Отменить" else "Закрыть"
        list.removeAllViews()
        resultViews.clear(); summaryViews.clear()
        if(grouped||running) {
            renderGrouped();updateResults()
            main.post {if(!closed&&renderRevision==revision)scroller.scrollTo(0,scrollY)}
            return@with
        }
        val categories = ServiceTestCatalog.matching(query)
        if (categories.isEmpty()) list.addView(text("Ничего не найдено", 14, Loft.MUTED))
        categories.forEach { category ->
            val expanded = if (query.isNotBlank()) category.id !in collapsedSearchCategories else category.id in expandedCategories
            list.addView(DiagnosticsUiStyle.foldHeader(activity,category.title,expanded) {
                val toggled = if (query.isNotBlank()) collapsedSearchCategories else expandedCategories
                if (!toggled.add(category.id)) toggled.remove(category.id); renderList()
            }.apply {
                tag = "service-category-${category.id}"; contentDescription = "${category.title}: ${if (expanded) "свернуть" else "раскрыть"}"
            }, space(0, 4))
            if (expanded) category.services.forEach serviceLoop@{ service ->
                val full = ServiceTestCatalog.categories.flatMap { it.services }.firstOrNull { it.id == service.id } ?: service
                val selectedCount = full.parts.count { it.id in selection.selected }
                val partsExpanded = if (query.isNotBlank()) service.id !in collapsedSearchServices else service.id in expandedServices
                val serviceCard=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL;tag="service-card-${service.id}"
                }
                serviceCard.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL;isBaselineAligned=false
                    addView(DiagnosticsUiStyle.choice(activity,
                        service.title + if(selectedCount>0)" · $selectedCount/${full.parts.size}"else"",
                        full.parts.all {it.id in selection.selected},!running,full) {
                        selection.toggle(full.parts);expandedServices.add(service.id);renderList()
                    },LinearLayout.LayoutParams(0,-2,1f))
                    addView(iconButton(if(partsExpanded)"up"else"down","Части сервиса ${service.title}").apply {
                        contentDescription = "Части сервиса ${service.title}"
                        setOnClickListener {
                            val toggled = if (query.isNotBlank()) collapsedSearchServices else expandedServices
                            if (!toggled.add(service.id)) toggled.remove(service.id); renderList()
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                })
                val summary = text("", 12, Loft.MUTED).apply { setPadding(dp(4), dp(4), dp(4), dp(8));tag="service-summary-${service.id}" }
                summaryViews[service.id] = full.parts to summary
                serviceCard.addView(summary)
                if (partsExpanded) service.parts.forEach { target ->
                    serviceCard.addView(DiagnosticsUiStyle.choice(activity,target.title,target.id in selection.selected,!running,full,"service-target-${target.id}") {
                        selection.toggle(listOf(target));renderList()
                    },space(8,0))
                    val resultBox=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6));visibility=View.GONE}
                    DiagnosticPath.values().forEach {path->
                        resultBox.addView(text("",12,Loft.MUTED).apply {
                            setPadding(0,dp(4),0,dp(4));tag="service-result-${target.id}-${path.name.lowercase()}"
                            resultViews[target.id to path]=this
                        })
                    }
                    serviceCard.addView(resultBox,space(0,8))
                }
                list.addView(serviceCard,space(0,16))
            }
        }
        updateResults()
        main.post { if (!closed && renderRevision == revision) scroller.scrollTo(0, scrollY) }
    }

    private fun renderGrouped()=with(activity) {
        val visibleResults=if(running)results.values.filter {it.state!=DiagnosticState.WAITING}.takeLast(6) else results.values.toList()
        val groups=ServiceTestResults.grouped(visibleResults)
        groups.forEach {(group,values)->
            if(values.isEmpty())return@forEach
            val title=when(group){ServiceTestResults.Group.SUCCESS->"Успех";ServiceTestResults.Group.FAILURE->"Ошибка";else->"Не определено"}
            list.addView(text("$title · ${values.size}",14,when(group){ServiceTestResults.Group.SUCCESS->DiagnosticsUiStyle.stateColor(DiagnosticState.AVAILABLE);ServiceTestResults.Group.FAILURE->Loft.ERROR;else->DiagnosticsUiStyle.stateColor(DiagnosticState.UNAVAILABLE)}),space(12,8))
            values.forEach resultLoop@{result->
                val service=ServiceTestCatalog.categories.flatMap {it.services }.firstOrNull {s->s.parts.any {it.id==result.targetId}} ?: return@resultLoop
                list.addView(DiagnosticsUiStyle.result(activity,service,
                    ServiceTestCatalog.label(result.targetId).orEmpty(),
                    (if(result.path==DiagnosticPath.DIRECT)"Напрямую"else"Через MCRF")+describe(result,false).let {if(it.isBlank())""else" · $it"},DiagnosticsPresentation.displayState(result),
                    describe(result)+result.evidence.joinToString("") {"\n${it.stage}: ${DiagnosticsPresentation.state(it.state)}"}),space(0,8))
            }
        }
        if(visibleResults.isEmpty())list.addView(text(if(running)"Готовим подключение…"else"Сначала запустите проверку.",13,Loft.MUTED))
    }

    private fun updateResults() {
        resultViews.forEach { (key, view) ->
            val result=results[key]
            view.visibility=if(result==null)View.GONE else View.VISIBLE
            (view.parent as? View)?.visibility=if(DiagnosticPath.values().any {results.containsKey(key.first to it)})View.VISIBLE else View.GONE
            val value="${if(key.second==DiagnosticPath.DIRECT)"Напрямую"else"Через MCRF"}: ${describe(result)}"
            if (view.text.toString() != value) view.text = value
            view.setTextColor(resultColor(result?.let(DiagnosticsPresentation::displayState)))
        }
        summaryViews.values.forEach { (parts, view) ->
            val measured=parts.flatMap {target->DiagnosticPath.values().mapNotNull {results[target.id to it]}}
            val value=if(measured.isEmpty())"" else DiagnosticPath.values().joinToString("\n"){path->
                val pathResults=parts.mapNotNull {results[it.id to path]}
                "${if(path==DiagnosticPath.DIRECT)"Напрямую"else"Через MCRF"}: ${summarize(pathResults)}"
            }
            if (view.text.toString() != value) view.text = value
            view.visibility = if (value.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        val all=results.values.toList()
        overall.visibility=if(all.isEmpty())View.GONE else View.VISIBLE
        overall.text=(if(running)"Проверяем"else"Проверка завершена")+" · "+summarize(all)
        count.text="Целей: ${selection.selected.size}"
        val total=selection.selected.size*DiagnosticPath.values().size
        val completed=all.count {it.state!=DiagnosticState.WAITING&&it.state!=DiagnosticState.RUNNING}
        if(running)count.text=if(all.isEmpty())"Подготовка проверки…"else"Проверяем · $completed из $total"
        progressBar.visibility=if(running)View.VISIBLE else View.GONE
        progressBar.isIndeterminate=running&&all.isEmpty()
        progressBar.progress=DiagnosticsPresentation.progress(completed,total)
    }

    private fun resultColor(state:DiagnosticState?)=DiagnosticsUiStyle.stateColor(state)
    private fun summarize(values:List<DiagnosticResult>):String {
        if(values.isEmpty())return "нет результатов"
        val available=values.count {ServiceTestResults.group(it)==ServiceTestResults.Group.SUCCESS}
        val errors=values.count {ServiceTestResults.group(it)==ServiceTestResults.Group.FAILURE}
        val pending=values.count {it.state in setOf(DiagnosticState.WAITING,DiagnosticState.RUNNING)}
        return buildList {
            if(available>0)add("успех $available")
            if(errors>0)add("провал $errors")
            if(pending>0)add("в очереди $pending")
            val other=values.size-available-errors-pending
            if(other>0)add("не определено $other")
        }.joinToString(" · ")
    }
    private fun describe(result: DiagnosticResult?,includeState:Boolean=true): String {
        if (result == null) return if(includeState)"ожидание"else""
        val label = when (DiagnosticsPresentation.displayState(result)) {
            DiagnosticState.WAITING -> "ожидание"
            DiagnosticState.RUNNING -> "проверяется"
            DiagnosticState.AVAILABLE -> "доступен"
            DiagnosticState.ERROR -> "ошибка"
            DiagnosticState.UNAVAILABLE -> "не проверено"
            DiagnosticState.CANCELLED -> "отменено"
        }
        val detail=" · " + DiagnosticsPresentation.resultReason(result)
        return if(includeState)label+detail else detail.removePrefix(" · ")
    }

    override fun close() {
        if (closed) return
        closed = true; generation++;preparing?.close();preparation.close();run?.cancel();runner.close();session?.close();main.removeCallbacksAndMessages(null)
        val owned = dialog; dialog = null; owned?.dismiss(); probe = null
    }
}
