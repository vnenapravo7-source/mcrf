package org.mcrf.client

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

/** Explicit consent, native VPN/notification permissions and user-chosen SAF export. */
class TrafficCaptureActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var mode: TrafficCaptureSegments
    private lateinit var scope: TrafficCaptureSegments
    private lateinit var timer: Spinner
    private lateinit var apps: Button
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var start: Button
    private lateinit var modeHint: TextView
    private lateinit var vpnAction: Button
    private lateinit var privacy: TextView
    private lateinit var help: TextView
    private lateinit var events: TextView
    private lateinit var selectedRule: Button
    private lateinit var allRule: Button
    private var journalEntries = emptyList<TrafficJournalStore.Entry>()
    private val checkedEvents = linkedSetOf<String>()
    private var journalReadable = false
    private var journalEpoch = 0L
    private val appLabels = mutableMapOf<String, String?>()
    private var displayedLabels = emptyMap<String, String?>()
    private var helpExpanded = false
    private var eventsExpanded = false
    private var selected = linkedSetOf<String>()
    private var pending: TrafficCapturePolicy.Options? = null
    private var transitioning = false
    private var reading = false
    private var refreshAt = 0L
    private var waitingForVpn = false
    private var resumed = false
    private val refresh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            status.text = TrafficCaptureService.status
            start.text = if (TrafficCaptureService.busy()) "Остановить запись" else "Начать запись"
            start.isEnabled = !transitioning
            updateContext()
            if (android.os.SystemClock.elapsedRealtime() - refreshAt >= 3000) reload()
            main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        UserThemeColorsUi.configure(this)
        val theme = getSharedPreferences("MainActivity", MODE_PRIVATE).getString("theme", "system")
        Loft.apply(theme == "dark" || theme == "system" &&
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (Loft.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        title = "Запись трафика · Beta"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        selected.addAll(state?.getStringArrayList("selected").orEmpty())
        checkedEvents.addAll(state?.getStringArrayList("checkedEvents").orEmpty())
        pending = state?.getString("pendingMode")?.let {
            TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.valueOf(it),
                state.getStringArrayList("pendingApps")?.toSet().orEmpty(), state.getLong("pendingSeconds"), state.getBoolean("pendingSelected"))
        }
        helpExpanded = state?.getBoolean("helpExpanded") ?: false
        eventsExpanded = state?.getBoolean("eventsExpanded") ?: false
        waitingForVpn = state?.getBoolean("waitingForVpn") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = Loft.rootBackground(this@TrafficCaptureActivity)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        content.addView(copy(title.toString(), 18f, Loft.INK))
        status = copy(TrafficCaptureService.status, 12f).apply { setPadding(0, dp(4), 0, 0); setTextIsSelectable(true) }
        content.addView(status)
        val controls = card(content)
        fun label(value: String) {
            controls.addView(copy(value, 12f, Loft.INK), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = if (controls.childCount == 0) 0 else dp(12)
                bottomMargin = dp(4)
            })
        }
        label("Режим")
        mode = TrafficCaptureSegments(this, TrafficCapturePolicy.Mode.entries.map { it.label }).also(controls::addView)
        mode.setSelection(state?.getInt("mode") ?: 0)
        modeHint = copy("").apply { setPadding(0, dp(6), 0, dp(4)) }
        controls.addView(modeHint)
        vpnAction = button("Настройки VPN").apply { visibility = View.GONE; setOnClickListener {
            if (TrafficCaptureService.foreignVpn(this@TrafficCaptureActivity)) startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            else startActivity(Intent(this@TrafficCaptureActivity, MainActivity::class.java))
        } }
        controls.addView(vpnAction)
        label("Область")
        scope = TrafficCaptureSegments(this, listOf("Вся система", "Выбранные приложения")).also(controls::addView)
        scope.setSelection(state?.getInt("scope") ?: 0)
        apps = button("Приложения: ${selected.size}").apply { setOnClickListener { chooseApps() } }
        controls.addView(apps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        apps.visibility = if (scope.selectedItemPosition == 1) View.VISIBLE else View.GONE
        scope.changed = { position ->
            apps.visibility = if (position == 1) View.VISIBLE else View.GONE
        }
        label("Таймер")
        timer = picker(TrafficCapturePolicy.presets.map { it.label }).also(controls::addView)
        timer.setSelection((state?.getInt("timer") ?: 0).coerceIn(TrafficCapturePolicy.presets.indices))
        help = disclosure().apply { setOnClickListener { helpExpanded = !helpExpanded; updateDisclosure() } }
        controls.addView(help)
        privacy = copy(TrafficCapturePolicy.PRIVACY).apply {
            text = privacyText(); setPadding(0, dp(8), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        }
        controls.addView(privacy)
        mode.changed = { updateContext() }
        start = button(if (TrafficCaptureService.busy()) "Остановить запись" else "Начать запись", primary = true).apply { setOnClickListener {
            if (TrafficCaptureService.busy()) {
                pending = null; transitioning = false
                startService(Intent(this@TrafficCaptureActivity, TrafficCaptureService::class.java).setAction(TrafficCaptureService.STOP))
            } else consent()
        } }
        controls.addView(start, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val history = card(content)
        history.addView(copy("Сохранённая запись", 18f, Loft.INK))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        history.addView(actions)
        selectedRule = button("В правило отмеченное").apply { setOnClickListener { createRule(onlyChecked = true) } }
        allRule = button("Все в правило").apply { setOnClickListener { createRule(onlyChecked = false) } }
        listOf(selectedRule, allRule, button("Очистить").apply { setOnClickListener { clearJournal() } }).forEachIndexed { index, action ->
            action.textSize = 12f; action.maxLines = 2
            action.setPadding(dp(4), 0, dp(4), 0); action.minHeight = dp(48); action.minimumHeight = dp(48)
            action.gravity = Gravity.CENTER
            actions.addView(action, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index < 2) marginEnd = dp(8) })
        }
        updateRuleActions()
        events = disclosure().apply { setOnClickListener { eventsExpanded = !eventsExpanded; updateDisclosure() } }
        history.addView(events)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        history.addView(rows)
        history.addView(button("Экспорт").apply { setOnClickListener {
            AlertDialog.Builder(this@TrafficCaptureActivity).setTitle("Экспортировать сетевую историю?")
                .setMessage("Открытый JSON содержит UID, пакеты, IP и известные домены. Выберите доверенное место; локальное шифрование не защищает экспорт.")
                .setNegativeButton("Отмена", null).setPositiveButton("Выбрать файл") { _, _ ->
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "mcrf-traffic-${System.currentTimeMillis()}.json"), EXPORT)
                }.show()
        } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        updateDisclosure()
        updateContext()
        setContentView(root)
    }
    private fun clearJournal() {
        AlertDialog.Builder(this).setTitle("Очистить локальную запись?")
            .setMessage("Запись остановится. MCRF серверное подключение продолжит работать. Ранее экспортированные файлы удалите отдельно.")
            .setNegativeButton("Отмена", null).setPositiveButton("Очистить") { _, _ ->
                journalEpoch++; checkedEvents.clear(); journalReadable = false; updateRuleActions()
                rows.removeAllViews(); rows.addView(copy("Очищаем запись…"))
                if (TrafficCaptureService.busy()) startService(Intent(this@TrafficCaptureActivity, TrafficCaptureService::class.java).setAction(TrafficCaptureService.DELETE))
                else io.execute { try { TrafficJournalStore.local(this@TrafficCaptureActivity).delete(); main.post { reload() } }
                    catch (_: Exception) { message("Не удалось удалить локальные записи") } }
            }.show()
    }
    private fun privacyText(): CharSequence = SpannableString(TrafficCapturePolicy.PRIVACY).apply {
        var start = 0
        TrafficCapturePolicy.PRIVACY.split("\n\n").forEach { paragraph ->
            val end = paragraph.indexOf('.').takeIf { it >= 0 } ?: paragraph.length
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, start + end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start += paragraph.length + 2
        }
    }
    private fun updateContext() {
        val foreign = TrafficCaptureService.foreignVpn(this)
        val connected = mode.selectedItemPosition == TrafficCapturePolicy.Mode.CONNECTED.ordinal
        modeHint.text = when {
            foreign -> "Другой VPN занят. Отключите его для записи."
            connected -> "Запись через VPN/БС MCRF с текущими маршрутами."
            else -> "Прямой интернет через локальный VPN. Нужен sing-box."
        }
        modeHint.setTextColor(if (foreign) Loft.ERROR else Loft.MUTED)
        vpnAction.visibility = if (foreign || connected && !McrfVpnService.ownsCurrentTun()) View.VISIBLE else View.GONE
        vpnAction.text = if (foreign) "Настройки VPN" else "Открыть подключение MCRF"
    }
    private fun updateDisclosure() {
        help.text = "Что записывается и ограничения ${if (helpExpanded) "▴" else "▾"}"
        privacy.visibility = if (helpExpanded) View.VISIBLE else View.GONE
        events.text = "Последние события ${if (eventsExpanded) "▴" else "▾"}"
        rows.visibility = if (eventsExpanded) View.VISIBLE else View.GONE
    }
    private fun consent() {
        val value = try {
            val seconds = TrafficCapturePolicy.presets[timer.selectedItemPosition].seconds
            TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.entries[mode.selectedItemPosition], selected.toSet(), seconds, scope.selectedItemPosition == 1)
        } catch (_: Exception) { message("Выберите приложения и интервал записи"); return }
        AlertDialog.Builder(this).setTitle("${value.mode.label} · начать?").setMessage(
            "Сохранить время, приложения, адреса и доступные домены локально на 24 часа? " +
            "Содержимое не сохраняется и не расшифровывается. " +
            if (value.mode == TrafficCapturePolicy.Mode.ONLY) "Интернет пойдёт напрямую через локальный VPN." else "Будет использовано подключение MCRF.")
            .setNegativeButton("Отмена", null).setPositiveButton("Разрешаю запись") { _, _ ->
                pending = value
                if (TrafficCaptureService.busy()) {
                    transitioning = true
                    startService(Intent(this, TrafficCaptureService::class.java).setAction(TrafficCaptureService.STOP))
                    waitRetirement(android.os.SystemClock.elapsedRealtime() + 30_000)
                } else permission()
            }.show()
    }
    private fun waitRetirement(until: Long) {
        if (isFinishing || isDestroyed || pending == null) { transitioning = false; return }
        if (!resumed) { transitioning = false; pending = null; return }
        if (!TrafficCaptureService.busy()) { transitioning = false; permission(); return }
        if (android.os.SystemClock.elapsedRealtime() >= until) {
            transitioning = false; pending = null; message("Ожидаем завершения прежней записи. Повторите запуск после освобождения worker."); return
        }
        main.postDelayed({ waitRetirement(until) }, 250)
    }
    private fun permission() {
        val value = pending ?: return
        val gate = TrafficCapturePolicy.admit(value.mode, TrafficCaptureService.foreignVpn(this),
            McrfVpnService.hasActiveSession(), McrfVpnService.ownsCurrentTun(), TrafficCaptureService.busy(), McrfVpnService.cleanupPending())
        if (gate == TrafficCapturePolicy.Admission.FOREIGN_VPN) {
            AlertDialog.Builder(this).setTitle("Остановить VPN и продолжить?")
                .setMessage("Другой VPN занимает системное подключение. Остановите его в настройках Android; после возвращения запись продолжится с вашего согласия.")
                .setOnCancelListener { pending = null }
                .setNegativeButton("Отмена") { _, _ -> pending = null }
                .setPositiveButton("Открыть настройки") { _, _ -> waitingForVpn = true; startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.show()
            return
        }
        if (gate == TrafficCapturePolicy.Admission.SERVER_CONFLICT && !McrfVpnService.cleanupPending()) {
            AlertDialog.Builder(this).setTitle("Остановить подключение и продолжить?")
                .setMessage("Текущее подключение MCRF отключится. Затем начнётся выбранный режим записи.")
                .setOnCancelListener { pending = null }
                .setNegativeButton("Отмена") { _, _ -> pending = null }
                .setPositiveButton("Остановить и продолжить") { _, _ ->
                    transitioning = true
                    startService(Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                    waitServerRetirement(android.os.SystemClock.elapsedRealtime() + 30_000)
                }.show()
            return
        }
        if (gate in setOf(TrafficCapturePolicy.Admission.BUSY, TrafficCapturePolicy.Admission.SERVER_CONFLICT)) {
            pending = null; message("Соединение MCRF занято или работает как прокси. Дождитесь VPN либо остановите его вручную."); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS); return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            pending = null; message("Разрешите уведомления MCRF: во время записи нужна видимая кнопка остановки."); return
        }
        val approval = VpnService.prepare(this)
        if (approval != null) { startActivityForResult(approval, VPN); return }
        pending = null
        startForegroundService(TrafficCaptureService.request(this, value))
    }
    private fun waitServerRetirement(until: Long) {
        if (isFinishing || isDestroyed || pending == null) { transitioning = false; return }
        if (!resumed) { transitioning = false; pending = null; return }
        if (!McrfVpnService.hasActiveSession() && !McrfVpnService.ownsCurrentTun() && !McrfVpnService.cleanupPending()) {
            transitioning = false; permission(); return
        }
        if (android.os.SystemClock.elapsedRealtime() >= until) {
            transitioning = false; pending = null; message("Подключение ещё останавливается. Повторите запись после завершения."); return
        }
        main.postDelayed({ waitServerRetirement(until) }, 250)
    }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        if (request == NOTIFICATIONS) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) permission()
            else { pending = null; message("Запись не начата: уведомления не разрешены") }
        }
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == VPN) {
            if (result == RESULT_OK) permission() else { pending = null; message("Запись не начата: разрешение VPN отклонено") }
        }
        if (request == EXPORT && result == RESULT_OK) data?.data?.let { uri -> io.execute {
            try {
                val export = TrafficJournalStore.local(this).export()
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(export) }
                message("Экспорт сохранён в выбранный файл")
            } catch (_: Exception) { message("Не удалось сохранить экспорт; файл может быть неполным") }
        } }
    }
    private fun chooseApps() {
        apps.isEnabled = false
        io.execute {
            val entries = packageManager.getInstalledApplications(0).filter {
                packageManager.checkPermission(Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED
            }.map { it.packageName to "${packageManager.getApplicationLabel(it)}\n${it.packageName} · UID ${it.uid}" }.sortedBy { it.second.lowercase() }
            main.post {
                if (isDestroyed || isFinishing) return@post
                apps.isEnabled = true
                val working = selected.toMutableSet()
                AlertDialog.Builder(this).setTitle("Приложения (с общим UID вместе)")
                    .setMultiChoiceItems(entries.map { it.second }.toTypedArray(), entries.map { it.first in working }.toBooleanArray()) { _, index, checked ->
                        if (checked) working.add(entries[index].first) else working.remove(entries[index].first)
                    }.setNegativeButton("Отмена", null).setPositiveButton("Выбрать") { _, _ ->
                        selected = LinkedHashSet(working); apps.text = "Приложения: ${selected.size}"; scope.setSelection(1)
                    }.show()
            }
        }
    }
    private fun reload() {
        if (reading || io.isShutdown) return
        reading = true; refreshAt = android.os.SystemClock.elapsedRealtime()
        val epoch = journalEpoch
        io.execute {
            val result = runCatching { TrafficJournalStore.local(this).entries() }
            val keys = result.getOrNull().orEmpty().map(TrafficJournalRules::key).toSet()
            val labels = result.getOrNull().orEmpty().flatMap { it.packages + listOfNotNull(it.app) }.distinct().associateWith { pkg ->
                if (!appLabels.containsKey(pkg)) appLabels[pkg] = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() && it != pkg }
                }.getOrNull()
                appLabels[pkg]
            }
            main.post {
                reading = false
                if (!isDestroyed && !isFinishing) {
                    if (epoch != journalEpoch) { reload(); return@post }
                    if (journalReadable && result.getOrNull() == journalEntries && labels == displayedLabels) return@post
                    rows.removeAllViews()
                    result.fold({ entries ->
                        journalReadable = true; journalEntries = entries; displayedLabels = labels
                        checkedEvents.retainAll(keys)
                        appendRows(0)
                    }, {
                        journalReadable = false; journalEntries = emptyList()
                        rows.addView(copy("Не удалось прочитать зашифрованную запись. Данные не заменены."))
                    })
                    updateRuleActions()
                }
            }
        }
    }
    private fun appendRows(offset: Int) {
        journalEntries.drop(offset).take(50).forEach { rows.addView(captureRow(it)) }
        if (journalEntries.size > offset + 50) {
            val more = button("Показать ещё")
            more.setOnClickListener { rows.removeView(more); appendRows(offset + 50) }
            rows.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (journalEntries.isEmpty()) rows.addView(copy("Записей пока нет"))
    }
    private fun updateRuleActions() {
        selectedRule.isEnabled = journalReadable && checkedEvents.isNotEmpty()
        allRule.isEnabled = journalReadable && journalEntries.isNotEmpty()
    }
    private fun createRule(onlyChecked: Boolean) {
        if (!journalReadable) return
        val keys = if (onlyChecked) checkedEvents.toSet() else null
        val epoch = journalEpoch
        if (keys != null && keys.isEmpty()) return
        selectedRule.isEnabled = false; allRule.isEnabled = false
        io.execute {
            val result = runCatching {
                val entries = TrafficJournalStore.local(this).entries().filter { keys == null || TrafficJournalRules.key(it) in keys }
                val draft = TrafficJournalRules.draft(entries)
                val store = RoutingStore(this).apply { load() }
                Triple(draft, store, store.presentationRevision() to store.generation())
            }
            main.post {
                if (isDestroyed || isFinishing) return@post
                updateRuleActions()
                if (epoch != journalEpoch) return@post
                result.fold({ (draft, store, version) ->
                    showRuleDraft(draft, store, version.first, version.second)
                }, { message("Нет подходящих адресов или не удалось прочитать данные. Записи и маршруты сохранены.") })
            }
        }
    }
    private fun showRuleDraft(draft: org.json.JSONObject, store: RoutingStore, revision: String, generation: String) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val name = EditText(this).apply {
            setText(draft.getString("name")); setSingleLine(true); setTextColor(Loft.INK)
            background = Loft.fieldBackground(this@TrafficCaptureActivity); minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        body.addView(name, LinearLayout.LayoutParams(-1, -2))
        body.addView(copy("Выход", 12f, Loft.INK), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(4) })
        val target = picker(listOf("VPN / прокси", "Напрямую", "Блокировать"))
        body.addView(target)
        body.addView(copy("Точные адреса в текущую группу. DNS-ответы и локальные адреса исключены. Остальные правила сохраняются."),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        body.addView(copy(draft.getString("text"), 13f, Loft.INK).apply { setTextIsSelectable(true) })
        val dialog = AlertDialog.Builder(this).setTitle("Создать правило")
            .setView(ScrollView(this).apply { addView(body) }).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isEmpty()) { name.error = "Введите название"; return@setOnClickListener }
                val chosen = org.json.JSONObject(draft.toString()).put("name", title)
                    .put("target", listOf("proxy", "direct", "block")[target.selectedItemPosition])
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                io.execute {
                    val result = runCatching { store.editAtRevision(revision, generation) { RouteRules.insert(it, chosen) } }
                    main.post {
                        if (!dialog.isShowing || isDestroyed || isFinishing) return@post
                        result.fold({ dialog.dismiss(); message("Правило сохранено в текущую группу маршрутов") }, {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            message("Не удалось сохранить: данные или режим изменились. Откройте создание правила заново. Записи сохранены.")
                        })
                    }
                }
            }
        }
        dialog.show()
    }
    private fun captureRow(entry: TrafficJournalStore.Entry): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = Loft.fieldBackground(this@TrafficCaptureActivity)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        val app = (entry.packages + listOfNotNull(entry.app)).mapNotNull { displayedLabels[it] }.distinct().joinToString()
            .ifBlank { "Приложение неизвестно" }
        val lines = LinearLayout(this@TrafficCaptureActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(entry.timestamp))
            addView(copy("$time · $app", 14f, Loft.INK).apply { setSingleLine(true); ellipsize = TextUtils.TruncateAt.END })
            addView(copy(entry.domain ?: "Домен неизвестен", 12f).apply {
                setPadding(0, dp(4), 0, 0)
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(copy(entry.ip, 12f).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        }
        addView(lines, LinearLayout.LayoutParams(0, -2, 1f))
        val check = CheckBox(this@TrafficCaptureActivity).apply {
            minWidth = dp(48); minHeight = dp(48); gravity = Gravity.CENTER
            buttonTintList = android.content.res.ColorStateList.valueOf(Loft.ACCENT)
            contentDescription = "Отметить $app: ${entry.domain ?: entry.ip}"
            isChecked = TrafficJournalRules.key(entry) in checkedEvents
            setOnCheckedChangeListener { _, checked ->
                val key = TrafficJournalRules.key(entry)
                if (checked) checkedEvents.add(key) else checkedEvents.remove(key)
                updateRuleActions()
            }
        }
        addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
        setOnClickListener { check.isChecked = !check.isChecked }
        setOnLongClickListener { showEntry(entry); true }
    }
    private fun showEntry(entry: TrafficJournalStore.Entry) {
        AlertDialog.Builder(this).setTitle(entry.domain ?: entry.ip)
            .setMessage("${entry.attribution}\n${entry.ip}${entry.port?.let { ":$it" }.orEmpty()} · ${entry.protocol}\n" +
                "Источник: ${entry.domainSource} · ${entry.event}\nРазмер: ${entry.bytes?.let { "$it Б" } ?: "не сообщён движком"}")
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Копировать IP") { _, _ -> copyAddress(entry.ip) }
            .apply { entry.domain?.let { domain -> setPositiveButton("Копировать домен") { _, _ -> copyAddress(domain) } } }.show()
    }
    private fun copyAddress(value: String) {
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Адрес", value))
        message("Адрес скопирован")
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("waitingForVpn", waitingForVpn)
        state.putBoolean("helpExpanded", helpExpanded); state.putBoolean("eventsExpanded", eventsExpanded)
        state.putStringArrayList("selected", ArrayList(selected)); state.putInt("mode", mode.selectedItemPosition)
        state.putInt("scope", scope.selectedItemPosition); state.putInt("timer", timer.selectedItemPosition)
        state.putStringArrayList("checkedEvents", ArrayList(checkedEvents))
        pending?.let { state.putString("pendingMode", it.mode.name); state.putStringArrayList("pendingApps", ArrayList(it.selectedApps))
            state.putLong("pendingSeconds", it.seconds); state.putBoolean("pendingSelected", it.selectedOnly) }
        super.onSaveInstanceState(state)
    }
    override fun onResume() {
        super.onResume(); resumed = true; main.post(refresh)
        if (waitingForVpn) {
            waitingForVpn = false
            if (!TrafficCaptureService.foreignVpn(this)) permission()
            else { pending = null; message("Запись не начата: другой VPN ещё работает") }
        }
    }
    override fun onPause() { resumed = false; main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
    private fun copy(value: String, size: Float = 12f, color: Int = Loft.MUTED) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Loft.glass(this@TrafficCaptureActivity)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun disclosure() = copy("", 12f, Loft.INK).apply {
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        val feedback = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, feedback, true)
        if (feedback.resourceId != 0) setBackgroundResource(feedback.resourceId)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }
    private fun button(label: String, primary: Boolean = false) =
        StandardButtons.style(this, Button(this), label, primary).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; includeFontPadding = false
        }
    private fun picker(labels: List<String>) = Spinner(this, Spinner.MODE_DROPDOWN).apply {
        minimumHeight = dp(48)
        // A custom field background replaces the platform's dropdown indicator.
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            Loft.fieldBackground(this@TrafficCaptureActivity),
            object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: android.graphics.Canvas) {
                    val x = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.left + dp(20).toFloat() else bounds.right - dp(20).toFloat()
                    val y = bounds.exactCenterY()
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = Loft.MUTED; style = android.graphics.Paint.Style.STROKE
                        strokeWidth = dp(2).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    canvas.drawPath(android.graphics.Path().apply {
                        moveTo(x - dp(4), y - dp(2)); lineTo(x, y + dp(2)); lineTo(x + dp(4), y - dp(2))
                    }, paint)
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        ))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setPopupBackgroundDrawable(Loft.popupBackground(this@TrafficCaptureActivity))
        adapter = object : ArrayAdapter<String>(this@TrafficCaptureActivity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                style(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                style(super.getDropDownView(position, convertView, parent))
            private fun style(view: View) = (view as TextView).apply {
                textSize = 14f; setTextColor(Loft.INK); minHeight = dp(48)
                setPaddingRelative(dp(12), dp(10), dp(40), dp(10))
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
        }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun message(text: String) { main.post { if (!isDestroyed && !isFinishing) Toast.makeText(this, text, Toast.LENGTH_LONG).show() } }
    companion object { private const val VPN = 11; private const val NOTIFICATIONS = 12; private const val EXPORT = 13 }
}
