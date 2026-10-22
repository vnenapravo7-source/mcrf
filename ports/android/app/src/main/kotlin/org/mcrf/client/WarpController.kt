package org.mcrf.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Activity-owned controller; retain while navigating, detach on destroy. Never runs direct trace HTTP. */
class WarpController(
    context: Context,
    private val catalogue: Connections,
    probe: TraceProbe,
    /** Main-thread short transaction: saveWarp + selection persistence. Must return committed profile ID. */
    commit: (WarpOperations.Verified, String?) -> String,
    /** Main-thread short transaction: remove profile + reconcile persistent selection. */
    remove: (String) -> Unit,
    onChanged: () -> Unit = {}
) {
    fun interface TraceProbe { fun fetch(candidateConfig: String, cancelled: () -> Boolean): String }
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val state = WarpControllerState()
    private var commitCallback: ((WarpOperations.Verified, String?) -> String)? = commit
    private var removeCallback: ((String) -> Unit)? = remove
    private var changedCallback: (() -> Unit)? = onChanged
    private var probeCallback: TraceProbe? = probe
    private var page: WarpPage? = null
    private var job: Future<*>? = null
    private var message = "Выход ещё не выбран."
    private var lastResultId: String? = null
    private data class Retry(val previousId: String?, val account: String, val options: WarpOperations.Options)
    private var retry: Retry? = null
    private var offerRussia = false

    /** Main thread; controller owns no view after detach. Rebuild following onChanged as needed. */
    fun build(activity: MainActivity, selectedId: String? = null): View {
        checkMain()
        return WarpPage(activity,catalogue.entries(),::register,::rescan,::remove,::cancel,::retrySearch,::tryRussia,selectedId).also {
            page = it; it.setBusy(state.running(),message);it.setRecovery(retry!=null,offerRussia)
        }.view
    }
    val view: View? get() = page?.view
    fun register() {
        checkMain()
        if(!WarpSettingsStore.load(app).termsAccepted) { update(false,"Сначала прими условия Cloudflare WARP."); return }
        start(null)
    }
    fun rescan(id: String) { checkMain(); start(id) }
    /** Reuses the already registered account, including when initial foreign search failed. */
    fun retrySearch() { checkMain();retry?.let { start(it.previousId) } }
    val canTryRussia: Boolean get() = offerRussia && !state.running()
    /** Explicit action only: no foreign search changes its country to RU automatically. */
    fun tryRussia() { checkMain();if(canTryRussia) retry?.let { start(it.previousId,WarpRecoveryPolicy.russia(it.options)) } }
    private fun start(previousId: String?, requestedOptions: WarpOperations.Options? = null) {
        val cached=retry?.takeIf { it.previousId==previousId }
        val options=requestedOptions ?: WarpSettingsStore.load(app).options()
        offerRussia=false
        val ticket = state.begin(previousId)
        job?.cancel(true); lastResultId = null
        update(true,if(previousId == null) "Регистрация и подбор WARP…" else "Подбор нового выхода WARP…")
        job = worker.submit {
            val cancelled = { state.cancelled(ticket) || Thread.currentThread().isInterrupted }
            try {
                try {NativeCarrier.executable(app,"warpscout")} catch(_:Exception) {throw WarpOperations.ScanFailure("Установите или обновите WARP Scanner в разделе «Движки».")}
                try {EngineUpdatePrimary.installation(app,ClientPrimaryCorePolicy.Core.parse("singbox").kind)} catch(_:Exception) {throw WarpOperations.ScanFailure("Для проверки WARP требуется установленный sing-box. Выбранный основной движок не изменится.")}
                catalogue.refreshFromStorage()
                val previous = previousId?.let { id -> catalogue.entries().firstOrNull { it.id==id && WarpProfile.isWarp(it) }
                    ?: throw IllegalArgumentException("Профиль WARP уже удалён.") }
                val previousAccount = previousId?.let { catalogue.warpAccount(it) ?: WarpProfile.fromEntry(previous!!) }
                val previousShare = previous?.let(catalogue::share)
                var attempt=0
                val backend = WarpScanner(app,cancelled) { phase,done,total ->
                    val label=if(attempt>0) "Попытка $attempt/3 · ${progressLabel(phase,done,total)}" else progressLabel(phase,done,total)
                    handler.post { if(state.current(ticket) && state.running()) update(true,label) }
                }
                val account = previousAccount ?: cached?.account ?: WarpRegistrationStore.load(app)
                    ?: WarpOperations.register(backend,options,cancelled)
                handler.post { if(state.current(ticket)) { retry=Retry(previousId,account,options) } }
                // Keep the in-memory retry even if encrypted persistence fails after registration.
                if(previousAccount==null) WarpRegistrationStore.save(app,account)
                val verified = WarpOperations.search(account,backend, { config -> (probeCallback ?: throw CancellationException()).fetch(config,cancelled) },options,cancelled) { current,total ->
                    attempt=current
                    handler.post { if(state.current(ticket) && state.running()) update(true,"Поиск выхода WARP · $current/$total${if(options.country=="RU") " · РФ" else ""}") }
                }
                if(cancelled()) throw CancellationException()
                // All UI selection and catalogue mutations are serialized on the main lane.
                handler.post {
                    if(!state.current(ticket)) return@post
                    try {
                        state.commit(ticket) {
                            catalogue.refreshFromStorage()
                            if(previousId != null) {
                                val current = catalogue.entries().firstOrNull { it.id==previousId && WarpProfile.isWarp(it) }
                                    ?: throw IllegalArgumentException("Профиль WARP уже удалён.")
                                require(catalogue.share(current) == previousShare) { "Профиль изменён во время подбора. Повтори проверку." }
                                val now = catalogue.warpAccount(previousId) ?: WarpProfile.fromEntry(current)
                                require(now == previousAccount) { "Аккаунт WARP изменился. Повтори проверку." }
                            }
                            val callback = commitCallback ?: throw CancellationException()
                            lastResultId = callback(verified,previousId)
                            require(catalogue.entries().any { it.id==lastResultId && WarpProfile.isWarp(it) }) { "Транзакция WARP не сохранила профиль." }
                        }
                        update(false,"WARP проверен · ${verified.trace.country} · ${verified.trace.node}")
                        // A failed cleanup keeps the encrypted account reusable; never undo a committed server.
                        try { WarpRegistrationStore.clearIf(app,verified.account) } catch(_: Exception) { }
                        retry=null;offerRussia=false;page?.setRecovery(false,false)
                        changedCallback?.invoke()
                    } catch(_: CancellationException) { }
                    catch(_: Exception) { state.finish(ticket); update(false,"Не удалось сохранить WARP. Проверь каталог и повтори подбор.") }
                }
            } catch(_: CancellationException) {
                handler.post { if(state.finish(ticket)) update(false,"Подбор WARP отменён. Старый профиль сохранён.") }
            } catch(_: InterruptedException) {
                Thread.currentThread().interrupt()
                handler.post { if(state.finish(ticket)) update(false,"Подбор WARP отменён. Старый профиль сохранён.") }
            } catch(error: Exception) {
                handler.post { if(state.finish(ticket)) {
                    offerRussia=WarpRecoveryPolicy.offerRussia(error is WarpOperations.SearchExhausted,(error as? WarpOperations.SearchExhausted)?.foreign==true,retry!=null)
                    val detail=(error as? WarpOperations.SearchExhausted)?.detail ?: (error as? WarpOperations.ScanFailure)?.reason.orEmpty()
                    update(false,(if(offerRussia) "Выход вне РФ не найден за 3 попытки. Повторите поиск или выберите РФ." else "Выход WARP не прошёл проверку. Старый профиль сохранён.")+if(detail.isNotBlank()) "\n$detail" else "")
                } }
            }
        }
    }
    fun remove(id: String) {
        checkMain()
        try {
            state.remove(id) {
                job?.cancel(true)
                catalogue.refreshFromStorage()
                require(catalogue.entries().any { it.id==id && WarpProfile.isWarp(it) }) { "Профиль WARP уже удалён." }
                removeCallback?.invoke(id) ?: throw CancellationException()
            }
            if(retry?.previousId==id) { retry=null;offerRussia=false }
            update(false,"Локальный профиль WARP удалён."); changedCallback?.invoke()
        } catch(_: Exception) { update(false,"Не удалось удалить профиль WARP. Обнови список подключений.") }
    }
    fun cancel() {
        checkMain(); state.cancel(); job?.cancel(true);offerRussia=false; update(false,"Подбор WARP отменён. Старый профиль сохранён.")
    }
    /** Call on Activity destruction. Cancels only this scanner/probe; never calls global ProbeCoordinator.cancel. */
    fun detach() {
        checkMain(); state.close(); job?.cancel(true); worker.shutdownNow()
        page = null; commitCallback = null; removeCallback = null; changedCallback = null; probeCallback = null
        retry=null;offerRussia=false
    }
    private fun update(busy: Boolean, text: String) { message=text;page?.setBusy(busy,text);page?.setRecovery(retry!=null,offerRussia) }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "WARP UI вызывается в главном потоке." } }
    private fun progressLabel(phase: String, done: Int, total: Int): String = when {
        phase.contains("regist") -> "Регистрация WARP…"
        total>0 -> "Подбор WARP · $done / $total"
        else -> "Проверка выходов WARP…"
    }
}
