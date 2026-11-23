package org.mcrf.client

import android.animation.ValueAnimator
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.Toast

/** Keep the existing server widget provider so placed widgets get the combined layout. */
class McrfServerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) McrfHomeWidget.update(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        McrfHomeWidget.update(context, manager, id)
    }
}

internal object McrfHomeWidget {
    const val ACTION_PREFIX = "org.mcrf.client.widget."
    private const val SERVER_ID = ACTION_PREFIX + "serverId"
    private const val CONNECT = ACTION_PREFIX + "connect"
    private const val DISCONNECT = ACTION_PREFIX + "disconnect"
    private const val SELECT = ACTION_PREFIX + "select"
    private const val DETECT_ON = ACTION_PREFIX + "detect-on"
    private const val DETECT_OFF = ACTION_PREFIX + "detect-off"
    private const val AUTO_ON = ACTION_PREFIX + "auto-on"
    private const val AUTO_OFF = ACTION_PREFIX + "auto-off"
    private val actions = setOf(CONNECT, DISCONNECT, SELECT, DETECT_ON, DETECT_OFF, AUTO_ON, AUTO_OFF)
    private val serverId = Regex("[0-9a-f]{64}")
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var refreshRows: Runnable? = null
    private var lastState: ConnectionState? = null
    private var lastRunningServer = ""
    private class PendingRequest(
        val command: String, val serverId: String?, val initialState: ConnectionState, val initialServer: String
    ) {
        var submitted = false
        fun acknowledged(state: ConnectionState, running: String) = state != initialState || running != initialServer
    }
    @Volatile private var pendingRequest: PendingRequest? = null
    private const val SERVICE_ACK_TIMEOUT_MS = 5_000L
    private const val REQUEST_TIMEOUT_MS = 8_000L
    @Volatile private var application: Context? = null
    @Volatile var selectionRevision = 0
        private set

    private enum class ConnectionState(val label: Int, val command: String?, val on: Boolean = false) {
        DISCONNECTED(R.string.widget_offline, CONNECT),
        CONNECTING(R.string.widget_connecting, null),
        CONNECTED(R.string.widget_connected, DISCONNECT, true),
        WHITELISTS(R.string.widget_whitelists, DISCONNECT, true),
        PROXY(R.string.widget_proxy, DISCONNECT, true),
        PAUSED(R.string.widget_paused, DISCONNECT),
        STOPPING(R.string.widget_stopping, null),
        FAILED(R.string.widget_failed, CONNECT)
    }

    private fun connectionState(): ConnectionState {
        val status = McrfVpnService.status
        return when {
            McrfVpnService.cleanupPending() -> ConnectionState.STOPPING
            ConnectionPresentation.pending(status) || McrfVpnService.directTunWorking() ||
                status.startsWith("Подключение") -> ConnectionState.CONNECTING
            ConnectionPresentation.connected(status) -> when {
                status.startsWith("Прокси ") -> ConnectionState.PROXY
                McrfVpnService.pendingWhitelists == true -> ConnectionState.WHITELISTS
                else -> ConnectionState.CONNECTED
            }
            McrfVpnService.ownsSystemTunnel() || McrfVpnService.directTunActive() ||
                McrfVpnService.wifiPaused() || McrfVpnService.hasActiveSession() -> ConnectionState.PAUSED
            ConnectionPresentation.failed(status) || status.startsWith("VPN-разрешение отозвано") ->
                ConnectionState.FAILED
            else -> ConnectionState.DISCONNECTED
        }
    }

    internal fun runningServer() = McrfVpnService.selectedId
        .takeIf { ConnectionPresentation.connected(McrfVpnService.status) }.orEmpty()

    internal fun refresh(context: Context) {
        val state = connectionState()
        val running = runningServer()
        val pending = pendingRequest
        if (pending?.submitted == true && pending.acknowledged(state, running)) {
            pendingRequest = null
            refreshFeedback(context)
        } else if (state != lastState || running != lastRunningServer) {
            refreshFeedback(context)
        }
        lastState = state
        lastRunningServer = running
    }

    private val paletteKeys = UserThemeColors.keys + listOf(
        HomeUiTheme.MODE_KEY, HomeUiTheme.GRADIENT_KEY, ConnectionAppearance.SERVER_ORBIT, "theme"
    )

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val context = application ?: return@OnSharedPreferenceChangeListener
        if (key == null || key in paletteKeys || key in setOf(
                BsDetectionMonitor.KEY, BsAutoSelectionPolicy.KEY, BsAutoSelectionPolicy.LAST_BS,
                BsAutoSelectionPolicy.LAST_VPN, "selected", "tun", UsageFeatures.KEY)) {
            refreshWidget(context)
        }
        if (key == null || key in paletteKeys || key == "selected" || key == "automatic") {
            scheduleServerRefresh(context)
        }
    }

    internal fun observePreferences(context: Context) {
        application = context.applicationContext
        prefs(context).registerOnSharedPreferenceChangeListener(preferenceListener)
        scheduleServerRefresh(context)
    }

    internal fun scheduleServerRefresh(context: Context) {
        val app = application ?: return
        if (context.applicationContext !== app) return
        handler.post {
            refreshRows?.let(handler::removeCallbacks)
            refreshRows = Runnable {
                val manager = AppWidgetManager.getInstance(app)
                notifyServerRows(manager, widgetIds(app, manager))
                refreshRows = null
            }.also { handler.postDelayed(it, 200) }
        }
    }

    private fun widgetIds(context: Context, manager: AppWidgetManager) =
        manager.getAppWidgetIds(ComponentName(context, McrfServerWidget::class.java))

    private fun refreshWidget(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in widgetIds(context, manager)) update(context, manager, id)
    }

    @Suppress("DEPRECATION")
    private fun notifyServerRows(manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.mcrf_widget_server_list)
    }

    private fun refreshFeedback(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = widgetIds(context, manager)
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.mcrf_server_widget)
        views.applyPowerPresentation(context, palette(context), connectionState())
        manager.partiallyUpdateAppWidget(ids, views)
        notifyServerRows(manager, ids)
    }

    @Suppress("DEPRECATION")
    internal fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val palette = palette(context)
        val state = connectionState()
        val prefs = prefs(context)
        val detecting = prefs.getBoolean(BsDetectionMonitor.KEY, false)
        val selected = prefs.getString("selected", "").orEmpty()
        val whitelists = McrfVpnService.pendingWhitelists
            ?: (selected.isNotEmpty() && prefs.getString(BsAutoSelectionPolicy.LAST_BS, null) == selected)
        val auto = AutoControlsUi.mode(context, prefs, whitelists) != BsAutoSelectionPolicy.OFF
        val views = RemoteViews(context.packageName, R.layout.mcrf_server_widget).apply {
            setInt(R.id.mcrf_widget_surface, "setColorFilter", palette.surface)
            applyPowerPresentation(context, palette, state)
            setTextColor(R.id.mcrf_widget_empty, palette.muted)
            setRemoteAdapter(R.id.mcrf_widget_server_list, Intent(context, McrfWidgetServerRows::class.java))
            setEmptyView(R.id.mcrf_widget_server_list, R.id.mcrf_widget_empty)
            // The launcher fills in only the selected row ID on the explicit private command.
            setPendingIntentTemplate(R.id.mcrf_widget_server_list, action(context, SELECT, mutable = true))
            setToggle(context, R.id.mcrf_widget_detection_row, R.string.widget_detection, detecting,
                if (detecting) DETECT_OFF else DETECT_ON, palette)
            setToggle(context, R.id.mcrf_widget_auto_row, R.string.widget_auto, auto,
                if (auto) AUTO_OFF else AUTO_ON, palette)
        }
        manager.updateAppWidget(id, views)
        notifyServerRows(manager, intArrayOf(id))
    }

    private fun RemoteViews.applyPowerPresentation(context: Context, palette: HomeUiTheme.Palette, state: ConnectionState) {
        val pending = pendingRequest
        val command = state.command.takeIf { pending == null }
        val label = when {
            state == ConnectionState.CONNECTING || state == ConnectionState.STOPPING -> state.label
            pending?.command == DISCONNECT -> R.string.widget_stopping
            pending?.command == SELECT && pending.initialState.command == DISCONNECT -> R.string.widget_switching
            pending != null -> R.string.widget_preparing
            else -> state.label
        }
        val actionLabel = when (command) {
            CONNECT -> R.string.widget_connect
            DISCONNECT -> R.string.widget_disconnect
            else -> R.string.widget_power_wait
        }
        setTextViewText(R.id.mcrf_widget_status, context.getString(label))
        setTextColor(R.id.mcrf_widget_status, if (command == null) palette.accent else palette.ink)
        val animatePending = command == null && ValueAnimator.areAnimatorsEnabled()
        setViewVisibility(R.id.mcrf_widget_progress, if (animatePending) View.VISIBLE else View.INVISIBLE)
        setViewVisibility(R.id.mcrf_widget_matrix, if (animatePending) View.INVISIBLE else View.VISIBLE)
        setImageViewResource(R.id.mcrf_widget_matrix,
            if (command != null && state.on) R.drawable.widget_matrix_on else R.drawable.widget_matrix_off)
        setContentDescription(R.id.mcrf_widget_power_target,
            context.getString(R.string.widget_power_description, context.getString(label), context.getString(actionLabel)))
        setOnClickPendingIntent(R.id.mcrf_widget_power_target, command?.let { powerAction(context, it) })
    }

    internal fun pendingServer(context: Context): String? = pendingRequest?.serverId
        ?: prefs(context).getString("selected", null)
            ?.takeIf { connectionState() == ConnectionState.CONNECTING && it.isNotEmpty() }

    private fun RemoteViews.setToggle(context: Context, id: Int, label: Int, enabled: Boolean,
                                      command: String, palette: HomeUiTheme.Palette) {
        val state = context.getString(if (enabled) R.string.widget_on else R.string.widget_off)
        setTextViewText(id, context.getString(R.string.widget_switch_label, context.getString(label), state))
        setTextColor(id, if (enabled) palette.accent else palette.muted)
        setOnClickPendingIntent(id, action(context, command))
    }

    private fun preferredVpn(context: Context): Boolean {
        val prefs = prefs(context)
        return UsageFeatures.preferredVpn(UsageFeatures.decode(prefs.getString(UsageFeatures.KEY, null)), prefs.getBoolean("tun", true))
    }

    private fun powerAction(context: Context, command: String): PendingIntent =
        if (command == CONNECT && preferredVpn(context) && VpnService.prepare(context) != null) {
            val consent = Intent(context, McrfWidgetVpnConsentActivity::class.java).setAction(CONNECT)
            PendingIntent.getActivity(context, 0, consent, PendingIntent.FLAG_IMMUTABLE)
        } else action(context, command)

    private fun action(context: Context, command: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, McrfWidgetActions::class.java).setAction(command)
        return PendingIntent.getBroadcast(context, 0, intent, when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
        })
    }

    internal fun rowClick(id: String) = Intent().putExtra(SERVER_ID, id)

    internal fun handle(context: Context, intent: Intent, complete: () -> Unit) {
        val command = intent.action
        if (command == null || command !in actions) {
            complete()
            return
        }
        val id = if (command == SELECT) intent.getStringExtra(SERVER_ID)?.takeIf(serverId::matches) else null
        if (command == SELECT && id == null) {
            complete()
            return
        }
        val app = context.applicationContext
        val request = if (command == CONNECT || command == DISCONNECT || command == SELECT) {
            val state = connectionState()
            if (pendingRequest != null || state.command == null ||
                command == SELECT && state.command == DISCONNECT && McrfVpnService.selectedId == id) {
                complete()
                return
            }
            PendingRequest(command, id, state, runningServer()).also {
                pendingRequest = it
                refreshFeedback(app)
                expireRequest(app,it,REQUEST_TIMEOUT_MS)
            }
        } else null
        McrfQuickTile.executeInOrder {
            var submitted = false
            try {
                if(request==null||pendingRequest===request)submitted = execute(app, command, id)
            } catch (error: Exception) {
                handler.post {
                    val detail = when (error) {
                        is IllegalArgumentException, is IllegalStateException -> ClientEvents.redact(error.message.orEmpty()).take(180)
                        else -> ""
                    }.ifBlank { app.getString(R.string.widget_action_unavailable) }
                    Toast.makeText(app, detail, Toast.LENGTH_SHORT).show()
                }
            } finally {
                handler.post {
                    try {
                        if (request == null) {
                            refreshWidget(app)
                        } else {
                            finishRequest(app, request, submitted)
                        }
                        McrfQuickTile.refresh(app)
                    } finally {
                        complete()
                    }
                }
            }
        }
    }

    private fun finishRequest(context: Context, request: PendingRequest, submitted: Boolean) {
        if (pendingRequest !== request) return
        if (!submitted || request.acknowledged(connectionState(), runningServer())) {
            pendingRequest = null
            refreshFeedback(context)
            return
        }
        request.submitted = true
        expireRequest(context,request,SERVICE_ACK_TIMEOUT_MS)
    }

    private fun expireRequest(context:Context,request:PendingRequest,delay:Long) {
        handler.postDelayed({
            if (pendingRequest === request) {
                pendingRequest = null
                refreshFeedback(context)
            }
        },delay)
    }

    /** True when the VPN service has been asked to process START or STOP. */
    private fun execute(context: Context, command: String, id: String?): Boolean {
        val prefs = prefs(context)
        val state = connectionState()
        if (command == DISCONNECT) {
            if (state.command != DISCONNECT) return false
            return context.startService(Intent(context, McrfVpnService::class.java).setAction(McrfVpnService.STOP)) != null
        }
        if (command == CONNECT && state.command != CONNECT) return false
        check(state.command != null) { context.getString(R.string.widget_power_wait) }
        val catalogue = Connections(context).apply { load() }
        val entries = catalogue.entries()
        val selected = prefs.getString("selected", "").orEmpty()
        val current = entries.firstOrNull { it.id == runningServer() } ?: entries.firstOrNull { it.id == selected }
        val whitelists = current?.whitelists ?: (McrfVpnService.pendingWhitelists == true)
        when (command) {
            CONNECT -> {
                val preferred = if (AutoControlsUi.mode(context, prefs, whitelists) == BsAutoSelectionPolicy.OFF) {
                    val manual = BsAutoSelectionPolicy.last(prefs, whitelists)
                    entries.firstOrNull { it.id == manual && it.whitelists == whitelists }
                } else current ?: SectionConnectSelection.choose(entries, BsAutoSelectionPolicy.last(prefs, whitelists), whitelists, catalogue.probes())
                    ?: SectionConnectSelection.choose(entries, BsAutoSelectionPolicy.last(prefs, !whitelists), !whitelists, catalogue.probes())
                val entry = preferred ?: error(context.getString(R.string.widget_server_empty))
                McrfQuickTile.startServer(context, entry, preferredVpn(context))
                selectionRevision++
                return true
            }
            SELECT -> {
                val entry = entries.firstOrNull { it.id == id } ?: error(context.getString(R.string.widget_server_gone))
                val hasSession = state.command == DISCONNECT
                if (hasSession && McrfVpnService.selectedId == entry.id) return false
                val vpn = McrfVpnService.ownsSystemTunnel() || preferredVpn(context)
                val mayStart = !vpn || VpnService.prepare(context) == null
                if (mayStart) {
                    McrfQuickTile.startServer(context, entry, vpn)
                } else {
                    check(prefs.edit().remove("warpSelectionJournal").putString("selected", entry.id).putBoolean("automatic", false).commit())
                    BsAutoSelectionPolicy.remember(prefs, entry)
                    handler.post { Toast.makeText(context, R.string.widget_server_consent, Toast.LENGTH_SHORT).show() }
                }
                selectionRevision++
                return mayStart
            }
            DETECT_ON, DETECT_OFF -> McrfQuickTile.setBsDetection(context, command == DETECT_ON, catalogue)
            AUTO_ON, AUTO_OFF -> AutoControlsUi.toggle(context, prefs, whitelists, command == AUTO_ON)
        }
        return false
    }

    internal fun prefs(context: Context) = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)

    internal fun palette(context: Context): HomeUiTheme.Palette {
        val prefs = prefs(context)
        val mode = HomeUiTheme.mode(prefs)
        val offset = if (mode == HomeUiTheme.Mode.LIGHT) 2 else 0
        val keys = UserThemeColors.keys
        val custom = runCatching {
            UserThemeColors.choice(prefs.getString(keys[offset], null), prefs.getString(keys[offset + 1], null))
        }.getOrNull()
        return HomeUiTheme.paletteForMode(mode, custom)
    }
}

class McrfWidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        McrfHomeWidget.handle(context, intent, pending::finish)
    }
}

/** Transparent trampoline used only to obtain Android's initial VPN permission. */
class McrfWidgetVpnConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val consent = VpnService.prepare(this)
        if (consent == null) connect() else @Suppress("DEPRECATION") startActivityForResult(consent, VPN_CONSENT)
    }

    @Deprecated("Android VPN consent returns through the legacy framework request callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT) return
        if (resultCode == RESULT_OK) connect() else finish()
    }

    private fun connect() = McrfHomeWidget.handle(this, intent, ::finish)

    private companion object { const val VPN_CONSENT = 1 }
}

class McrfWidgetServerRows : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ServerRows()

    private inner class ServerRows : RemoteViewsFactory {
        private val catalogue = Connections(applicationContext)
        private var rows = emptyList<ServerImport.Server>()
        private var selected = ""
        private var pending: String? = null
        private var palette = McrfHomeWidget.palette(applicationContext)

        override fun onCreate() = Unit
        override fun onDestroy() { rows = emptyList() }

        override fun onDataSetChanged() {
            rows = try {
                catalogue.refreshFromStorage()
                catalogue.entries().distinctBy { it.id }
            } catch (_: Exception) {
                emptyList()
            }
            val prefs = McrfHomeWidget.prefs(applicationContext)
            selected = McrfHomeWidget.runningServer().ifEmpty {
                prefs.getString("selected", "").orEmpty().takeUnless { prefs.getBoolean("automatic", false) }.orEmpty()
            }
            pending = McrfHomeWidget.pendingServer(applicationContext)
            palette = McrfHomeWidget.palette(applicationContext)
        }

        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        override fun getLoadingView(): RemoteViews? = null
        override fun hasStableIds() = true
        override fun getItemId(position: Int) = rows.getOrNull(position)?.id?.take(16)?.toULong(16)?.toLong() ?: 0L

        override fun getViewAt(position: Int): RemoteViews? {
            val row = rows.getOrNull(position) ?: return null
            val waiting = row.id == pending
            val chosen = row.id == selected && !waiting
            val section = getString(if (row.whitelists) R.string.widget_server_bs else R.string.widget_server_vpn)
            val detail = if (waiting) getString(R.string.widget_server_pending)
                else getString(R.string.widget_server_detail, section, row.label())
            val name = row.name.take(120)
            return RemoteViews(packageName, R.layout.mcrf_widget_server_row).apply {
                setTextViewText(R.id.mcrf_widget_server_name, name)
                setTextViewText(R.id.mcrf_widget_server_detail, detail)
                setTextColor(R.id.mcrf_widget_server_name, palette.ink)
                setTextColor(R.id.mcrf_widget_server_detail, if (waiting) palette.accent else palette.muted)
                setInt(R.id.mcrf_widget_server_highlight, "setColorFilter", if (waiting) palette.field else palette.selected)
                setViewVisibility(R.id.mcrf_widget_server_highlight, if (chosen || waiting) View.VISIBLE else View.GONE)
                setContentDescription(R.id.mcrf_widget_server_row,
                    getString(if (chosen) R.string.widget_server_current else R.string.widget_server_description, name, detail))
                setOnClickFillInIntent(R.id.mcrf_widget_server_row, McrfHomeWidget.rowClick(row.id))
            }
        }
    }
}
