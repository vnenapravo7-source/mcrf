package org.mcrf.client

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONTokener

/** Private HTTP renderer source, NOT enabled by production dispatch. Callbacks
 * must use one exact binding/worker and transfer response ownership once.
 * No loopback/network fallback, no JS -> final Send, no JavascriptInterface. */
internal class OpenFluxHttpPageView(
    context: Context,
    private val current: () -> Boolean,
    private val load: ((Loaded?) -> Unit) -> Unit,
    private val exchange: (OpenFluxHttpPageChannel.Request, (Exchange?) -> Unit) -> Unit,
    private val submit: (String, (Boolean) -> Unit) -> Unit,
    private val failed: (String) -> Unit,
    private val cancelled: () -> Unit
) : LinearLayout(context), AutoCloseable {
    class Loaded(val address: String, val response: OpenFluxHttpIpcResponse.Response)
    class Exchange(val nativeId: String, val response: OpenFluxHttpIpcResponse.Response)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val window = OpenFluxWindowAdmission()
    private val session = OpenFluxOwnPageSession { live() }
    @Volatile private var policy: OpenFluxHttpPagePolicy? = null
    private var channel: OpenFluxHttpPageChannel? = null
    private var browser: WebView? = null
    private var ports: Array<WebMessagePort>? = null
    private var frames: OpenFluxHttpPageResponse? = null
    private var inflight: OpenFluxHttpPageChannel.Request? = null
    @Volatile private var resumed = false
    private var requested = false
    private var rendered = false
    private var connected = false
    private var portAttempts = 0
    @Volatile private var sending = false
    private val streams = ConcurrentHashMap.newKeySet<OwnedStream>()
    private val pendingResponses = ConcurrentHashMap.newKeySet<OpenFluxHttpIpcResponse.Response>()
    private val pendingWaiters = ConcurrentHashMap.newKeySet<Waiter>()
    private val resourceSlots = Semaphore(8)
    private class Waiter {
        val latch = CountDownLatch(1)
        private var retired = false
        private var published = false
        private var result: WebResourceResponse? = null
        @Synchronized fun pending() = !retired && !published
        fun publish(value: WebResourceResponse): Boolean {
            val accepted = synchronized(this) {
                if (retired || published) false else { result = value; published = true; true }
            }
            // Never close a stream under the handoff monitor. A rejected late
            // response belongs to this caller, not to a vanished loader thread.
            try { if (!accepted) value.data?.close() } finally { latch.countDown() }
            return accepted
        }
        @Synchronized fun take(): WebResourceResponse? {
            if (retired || !published) return null
            retired = true
            return result.also { result = null } // Exactly one consumer now owns the stream.
        }
        fun close() {
            val owned = synchronized(this) { retired = true; result.also { result = null } }
            try { owned?.data?.close() } finally { latch.countDown() }
        }
    }
    private val waiters = mutableMapOf<String, Waiter>() // Main-thread ownership only.
    private val chrome = OpenFluxVerificationLayout(context)
    private val status = TextView(context).apply {
        text = "Страница текущего скрипта OpenFlux. Запросы идут только к его обработчику через ядро. " +
            "Прямой доступ страницы к сети отключён; внешние ресурсы, cookie-сессии и переходы не поддерживаются. " +
            "Уход из окна отменяет проверку."
        setTextColor(Loft.INK); isSaveEnabled = false
    }
    private val open = StandardButtons.style(context, Button(context), "Открыть страницу")
    private val send = StandardButtons.style(context, Button(context), "Передать ответ").apply { isEnabled = false }
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        orientation = VERTICAL; isSaveEnabled = false; background = Loft.rootBackground(context)
        val inset = (16 * resources.displayMetrics.density).toInt(); chrome.setPadding(inset, inset, inset, inset)
        addView(chrome, LayoutParams(-1, -1)); chrome.details.addView(status, LayoutParams(-1, -2))
        chrome.actions.addView(open, LayoutParams(-1, -2))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val cancel = StandardButtons.style(context, Button(context), "Отмена")
        for (button in listOf(cancel, send)) {
            button.maxLines = 3; button.ellipsize = null; row.addView(button, LayoutParams(0, -1, 1f))
        }
        chrome.actions.addView(row, LayoutParams(-1, -2))
        open.setOnClickListener {
            if (!live() || requested) return@setOnClickListener
            requested = true; open.isEnabled = false; status.text = "Получение страницы текущего обработчика…"
            val once = AtomicBoolean(false)
            try { load { loaded ->
                if (!once.compareAndSet(false, true)) { loaded?.response?.close(); return@load }
                loaded?.response?.let(pendingResponses::add)
                if (closed.get()) { loaded?.response?.let { pendingResponses.remove(it); it.close() }; return@load }
                if (!handler.post {
                    loaded?.response?.let(pendingResponses::remove)
                    if (!live()) loaded?.response?.close()
                    else if (loaded == null) fail("Страница текущей проверки недоступна.") else show(loaded)
                }) loaded?.response?.let { pendingResponses.remove(it); it.close() }
            } } catch (_: Exception) { fail("Не удалось получить страницу текущей проверки.") }
        }
        send.setOnClickListener {
            if (!canSend()) return@setOnClickListener
            val attempt = session.beginSubmissionAfterUserAction() ?: return@setOnClickListener
            sending = true; send.isEnabled = false; status.text = "Ожидание подтверждения текущего ядра…"
            ports?.forEach { runCatching { it.close() } }; ports = null; connected = false
            runCatching { browser?.stopLoading() }
            val once = AtomicBoolean(false)
            try { submit(attempt.payload) { accepted ->
                if (once.compareAndSet(false, true)) handler.post {
                    if (live() && session.submissionCurrent(attempt)) {
                        // Callback MUST mean exact native apply ACK, not queue/Binder admission.
                        if (session.finishSubmission(attempt, accepted) == true) {
                            status.text = "Текущее ядро подтвердило принятие ответа."
                            close(); cancelled()
                        } else fail("Ответ не подтверждён. Нужен новый запрос проверки.")
                    }
                }
            } } catch (_: Exception) { fail("Ответ не подтверждён. Повторная отправка запрещена.") }
        }
        cancel.setOnClickListener { close(); cancelled() }
    }
    private fun live() = !closed.get() && resumed && try { current() } catch (_: Exception) { false }
    private fun canSend() = live() && rendered && connected && !sending && channel?.empty() == true &&
        inflight == null && frames == null && streams.isEmpty()
    // Staging enables only the native button; no page request can call submit().
    private var staged = false
    private fun updateSend() { send.isEnabled = staged && canSend() }
    fun resumePage() {
        if (!closed.get()) {
            resumed = true; window.resume()
            try { browser?.onResume(); updateSend() } catch (_: Exception) { fail("Окно страницы не восстановлено.") }
        }
    }
    fun pausePage() {
        resumed = false; window.pause(); runCatching { browser?.onPause() }
        if (requested) { close(); cancelled() } // Never revive an old handler/page epoch.
    }
    private fun fail(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { fail(message) }; return }
        if (closed.get()) return
        close(); status.text = message; failed(message)
    }
    private fun empty() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
    private fun show(loaded: Loaded) {
        try {
            check(live() && browser == null)
            val activity = context as? Activity
            check(OpenFluxVerificationWebProfile.ready && Application.getProcessName() == "${context.packageName}:openfluxVerification" &&
                activity != null && activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val p = OpenFluxHttpPagePolicy(loaded.address, UUID.randomUUID().toString().replace("-", ""))
            val html = p.mainHtml(loaded.response.status, loaded.response.headers, loaded.response.body) ?: error("unsupported document")
            val asset = context.assets.open("openflux-http-page.js").use { input ->
                val raw = input.readBytes(); try { check(raw.size <= 65536); raw.toString(Charsets.UTF_8) } finally { raw.fill(0) }
            }
            check(!asset.contains("</script", ignoreCase = true))
            policy = p; channel = OpenFluxHttpPageChannel(p, window, window.capture()!!) { live() }
            val web = WebView(context); browser = web; web.isSaveEnabled = false
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false
                blockNetworkLoads = true; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE; setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false; mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                @Deprecated("Legacy callback") override fun shouldOverrideUrlLoading(view: WebView, target: String) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (request.isForMainFrame) empty() else resource(request.url.toString(), request.method)
                @Deprecated("Legacy callback") override fun shouldInterceptRequest(view: WebView, target: String) = empty()
                override fun onPageStarted(view: WebView, target: String, icon: android.graphics.Bitmap?) {
                    if (!live() || target != p.initialUrl || connected) fail("Переход со страницы проверки запрещён.")
                }
                override fun onPageCommitVisible(view: WebView, target: String) {
                    if (live() && target == p.initialUrl) { rendered = true; updateSend() }
                }
                override fun onPageFinished(view: WebView, target: String) {
                    if (live() && target == p.initialUrl) { rendered = true; updateSend() }
                }
                override fun onReceivedSslError(view: WebView, callback: android.webkit.SslErrorHandler, error: android.net.http.SslError) { callback.cancel(); fail("Сетевой переход запрещён.") }
                override fun onReceivedHttpAuthRequest(view: WebView, callback: HttpAuthHandler, host: String, realm: String) { callback.cancel() }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail("Страница текущей проверки не загружена.")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail("Окно страницы остановлено."); return true }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage) = true
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            }
            web.setDownloadListener { _, _, _, _, _ -> fail("Скачивание файлов запрещено.") }
            chrome.setPage(web)
            val prefix = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"${OpenFluxHttpPagePolicy.CSP}\"><script>$asset</script>"
            web.loadDataWithBaseURL(p.initialUrl, prefix + html, "text/html", "UTF-8", null)
            handler.post(probePort) // Do not wait for pageFinished/module fetch promises to settle.
        } catch (_: Exception) { fail("Страница несовместима с изолированной проверкой.") }
        finally { loaded.response.close() }
    }
    private val probePort = object : Runnable {
        override fun run() {
            if (!live() || connected) return
            val web = browser ?: return
            if (++portAttempts > 40) { fail("Канал страницы не подготовлен."); return }
            try { web.evaluateJavascript("window.__mcrfHttpBridgeReady===true") { result ->
                if (!live() || connected || browser !== web) return@evaluateJavascript
                if (result != "true") { handler.postDelayed(this, 250); return@evaluateJavascript }
                try {
                    val pair = web.createWebMessageChannel(); ports = pair
                    pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(port: WebMessagePort, message: WebMessage) {
                            if (!live() || sending || ports?.get(0) !== port) return
                            if (!message.ports.isNullOrEmpty()) { message.ports?.forEach { runCatching { it.close() } }; fail("Формат сообщения страницы отклонён."); return }
                            val text = message.data ?: run { fail("Формат сообщения страницы отклонён."); return }
                            if (OpenFluxHttpIpc.utf8Size(text, 114688) == null) { fail("Сообщение страницы превышает лимит."); return }
                            val array = try {
                                val reader = JSONTokener(text); val value = reader.nextValue() as? JSONArray ?: error("invalid shape")
                                check(reader.nextClean() == '\u0000'); value
                            } catch (_: Exception) { fail("Формат сообщения страницы отклонён."); return }
                            if (array.length() == 2 && array.opt(0) == "stage") {
                                val payload = array.opt(1) as? String
                                if (payload == null || !session.stage(payload)) fail("Ответ страницы отклонён.")
                                else { staged = true; status.text = "Ответ подготовлен. Нажми «Передать ответ»."; updateSend() }
                            } else if (channel?.accept(text) == true) pump() else fail("Запрос страницы отклонён.")
                        }
                    }, handler)
                    connected = true
                    web.postWebMessage(WebMessage("mcrf-http-channel", arrayOf(pair[1])), Uri.parse(requireNotNull(policy).origin))
                    updateSend()
                } catch (_: Exception) { fail("Не удалось подготовить канал страницы.") }
            } } catch (_: Exception) { fail("Канал страницы недоступен.") }
        }
    }
    private fun pump() {
        if (!live() || sending || inflight != null || frames != null) return
        val queue = channel ?: return; val request = queue.next() ?: run { updateSend(); return }
        inflight = request; updateSend()
        val once = AtomicBoolean(false)
        try { exchange(request) { result ->
            val response = result?.response
            if (!once.compareAndSet(false, true)) { response?.close(); return@exchange }
            response?.let(pendingResponses::add)
            if (closed.get()) { response?.let { pendingResponses.remove(it); it.close() }; return@exchange }
            if (!handler.post {
                response?.let(pendingResponses::remove)
                if (!live() || inflight !== request || !queue.current(request)) { response?.close(); return@post }
                if (response == null || result == null) { fail("Ресурс текущей страницы недоступен."); return@post }
                if (response.request != result.nativeId) { response.close(); fail("Ответ ресурса не принадлежит запросу."); return@post }
                val waiter = waiters.remove(request.id)
                if (waiter != null) {
                    try {
                        if (!waiter.pending()) { response.close(); fail("Ожидание ресурса истекло."); return@post }
                        if (!waiter.publish(resourceResponse(response))) { fail("Ожидание ресурса истекло."); return@post }
                        check(queue.complete(request)); inflight = null; pump()
                    } catch (_: Exception) { response.close(); runCatching { waiter.close() }; fail("Ресурс страницы несовместим.") }
                } else {
                    frames = OpenFluxHttpPageResponse.adopt(request.id, result.nativeId, response, request.method == "HEAD") {
                        live() && queue.current(request)
                    }
                    if (frames == null) fail("Ответ ресурса отклонён.") else handler.post(deliverFrame)
                }
            }) response?.let { pendingResponses.remove(it); it.close() }
        } } catch (_: Exception) { fail("Запрос ресурса не завершён. Повтор запрещён.") }
    }
    private val deliverFrame = object : Runnable {
        override fun run() {
            if (!live()) return
            val stream = frames ?: return; val request = inflight ?: return
            try {
                val frame = stream.next() ?: error("retired response")
                requireNotNull(ports)[0].postMessage(WebMessage(frame))
                if (JSONArray(frame).opt(0) == "end") {
                    stream.close(); frames = null; check(channel?.complete(request) == true); inflight = null; pump()
                } else handler.post(this)
            } catch (_: Exception) { fail("Передача ресурса странице остановлена.") }
        }
    }
    private fun resource(url: String, method: String): WebResourceResponse {
        val p = policy ?: return empty()
        if (!live() || sending || p.interceptTarget(url, method) == null || Looper.myLooper() == Looper.getMainLooper()) return empty()
        if (!resourceSlots.tryAcquire()) { fail("Слишком много одновременных ресурсов страницы."); return empty() }
        val waiter = Waiter()
        pendingWaiters.add(waiter)
        if (closed.get()) { pendingWaiters.remove(waiter); resourceSlots.release(); return empty() }
        if (!handler.post {
            if (!live() || !waiter.pending()) { waiter.close(); return@post }
            val id = UUID.randomUUID().toString().replace("-", "")
            val frame = JSONArray().put("request").put(id).put(method).put(url).put(JSONArray()).put("").toString()
            if (channel?.accept(frame) == true) { waiters[id] = waiter; pump() }
            else { waiter.close(); fail("Слишком много ресурсов страницы.") }
        }) waiter.close()
        return try {
            if (!waiter.latch.await(10, TimeUnit.SECONDS)) {
                waiter.close(); fail("Ожидание ресурса страницы истекло."); empty()
            } else waiter.take() ?: empty()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); waiter.close(); fail("Загрузка ресурса остановлена."); empty()
        } finally { pendingWaiters.remove(waiter); resourceSlots.release() }
    }
    private fun resourceResponse(response: OpenFluxHttpIpcResponse.Response): WebResourceResponse {
        check(response.status in 200..299 && response.status != 204 && streams.size < 8)
        check(response.headers.filter { it.first.equals("Content-Encoding", true) }.all { it.second.trim().equals("identity", true) })
        val types = response.headers.filter { it.first.equals("Content-Type", true) }; check(types.size <= 1)
        val type = types.singleOrNull()?.second?.split(';')?.map { it.trim() } ?: listOf("application/octet-stream")
        val mime = type[0].lowercase(java.util.Locale.ROOT)
        check(mime.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) && type.size <= 2)
        if (type.size == 2) check(type[1].lowercase(java.util.Locale.ROOT) in setOf("charset=utf-8", "charset=\"utf-8\""))
        val stream = OwnedStream(response.body); streams.add(stream)
        return try { WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null,
            response.status, "OpenFlux", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to OpenFluxHttpPagePolicy.CSP), stream) }
        catch (error: Exception) { stream.close(); throw error }
    }
    private inner class OwnedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var retired = false
        @Synchronized override fun close() { if (!retired) { retired = true; buf.fill(0); pos = count; streams.remove(this); handler.post { if (live()) updateSend() } } }
        @Synchronized override fun read(): Int { val result = super.read(); if (available() == 0) close(); return result }
        @Synchronized override fun read(bytes: ByteArray, offset: Int, size: Int): Int { val result = super.read(bytes, offset, size); if (available() == 0) close(); return result }
        @Synchronized override fun skip(size: Long): Long { val result = super.skip(size); if (available() == 0) close(); return result }
        override fun markSupported() = false
        override fun mark(readAheadLimit: Int) = Unit
        override fun reset() { throw IOException("OpenFlux resource is one-shot") }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!closed.compareAndSet(false, true)) return
        resumed = false; window.close(); handler.removeCallbacksAndMessages(null); session.close(); channel?.close(); channel = null
        pendingResponses.toList().forEach { it.close() }; pendingResponses.clear()
        pendingWaiters.toList().forEach { runCatching { it.close() } }; pendingWaiters.clear()
        frames?.close(); frames = null; inflight = null; waiters.values.forEach { runCatching { it.close() } }; waiters.clear()
        streams.toList().forEach { runCatching { it.close() } }; streams.clear()
        open.isEnabled = false; send.isEnabled = false
        ports?.forEach { runCatching { it.close() } }; ports = null
        browser?.let { web -> browser = null; OpenFluxWebViewRetirement.retire(
            { (web.parent as? ViewGroup)?.removeView(web) }, { web.stopLoading() },
            { web.webChromeClient = null }, { web.webViewClient = WebViewClient() }, { web.destroy() }) }
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
    override fun toString() = "OpenFluxHttpPageView(redacted)"
}
