package org.mcrf.client

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import java.io.ByteArrayInputStream
import java.util.UUID

/** Offline inline-page renderer behind the disabled production capability.
 * Normal dispatch is source-wired, not runtime accepted.
 * Caller must bind current/load/offer to the same real Binder request and close
 * on owner loss. Never use this for external, loopback or exit-proxied URLs. */
internal class OpenFluxOwnPageView(
    context: Context,
    private val current: () -> Boolean,
    private val loadVerifiedDocument: ((String?) -> Unit) -> Unit,
    private val offerSubmission: (String, () -> Boolean, (Boolean) -> Unit) -> Unit,
    private val failed: (String) -> Unit,
    private val cancelled: () -> Unit,
) : LinearLayout(context), AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val origin = "https://${UUID.randomUUID().toString().replace("-", "")}.openflux.invalid"
    private val url = "$origin/"
    private val session = OpenFluxOwnPageSession(current)
    @Volatile private var closed = false
    private var paused = true
    private var pendingHtml: String? = null
    private var requested = false
    private var documentLoaded = false
    private var channelSent = false
    private var ports: Array<WebMessagePort>? = null
    private var browser: WebView? = null
    private val chrome = OpenFluxVerificationLayout(context)
    private val status = TextView(context).apply {
        text = "Встроенная настройка OpenFlux. Сеть и переходы для этой страницы отключены. Уход из окна отменит незавершённую проверку."
        setTextColor(Loft.INK); isSaveEnabled = false
    }
    private val open = StandardButtons.style(context, Button(context), "Открыть страницу")
    private val send = StandardButtons.style(context, Button(context), "Передать ответ").apply { isEnabled = false }

    init {
        orientation = VERTICAL; isSaveEnabled = false
        background = Loft.rootBackground(context)
        val inset = (16 * resources.displayMetrics.density).toInt()
        chrome.setPadding(inset, inset, inset, inset)
        addView(chrome, LayoutParams(-1, -1))
        chrome.details.addView(status, LayoutParams(-1, -2))
        chrome.actions.addView(open, LayoutParams(-1, -2))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val cancel = StandardButtons.style(context, Button(context), "Отмена")
        for (button in listOf(cancel, send)) {
            button.maxLines = 3; button.ellipsize = null
            row.addView(button, LayoutParams(0, -1, 1f))
        }
        chrome.actions.addView(row, LayoutParams(-1, -2))
        open.setOnClickListener {
            if (requested || !live()) return@setOnClickListener
            requested = true; open.isEnabled = false; status.text = "Получение текущей страницы…"
            try {
                // Loader must return verified full text asynchronously; no URL/HTML Intent extras.
                loadVerifiedDocument { html -> handler.post {
                    if (!live() || documentLoaded) return@post
                    documentLoaded = true
                    if (html == null) fail("Страница недоступна. Нужен новый запрос проверки.")
                    else if (paused) pendingHtml = html else showDocument(html)
                } }
            } catch (_: Exception) { fail("Не удалось получить страницу текущей проверки.") }
        }
        send.setOnClickListener {
            if (!live()) return@setOnClickListener
            val attempt = session.beginSubmissionAfterUserAction() ?: return@setOnClickListener
            send.isEnabled = false
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val done: (Boolean) -> Unit = { accepted ->
                if (completed.compareAndSet(false, true)) handler.post {
                    if (live()) when (session.finishSubmission(attempt, accepted)) {
                        true -> {
                            browser?.stopLoading()
                            status.text = "Ответ передан в очередь. Ожидаем подтверждение текущего движка."
                        }
                        false -> { send.isEnabled = true; status.text = "Ответ не передан. Запрос мог истечь или очередь занята." }
                        null -> Unit
                    }
                }
            }
            try { offerSubmission(attempt.payload, { !closed && session.submissionCurrent(attempt) }, done) }
            catch (_: Exception) { done(false) }
        }
        cancel.setOnClickListener { close(); cancelled() }
    }
    override fun toString() = "OpenFluxOwnPageView(redacted)"
    fun pausePage() { paused = true; browser?.onPause() }
    fun resumePage() {
        if (closed) return
        paused = false; browser?.onResume()
        pendingHtml?.let { html -> pendingHtml = null; showDocument(html) }
    }
    private fun live(): Boolean {
        if (closed) return false
        val valid = try { current() } catch (_: Exception) { false }
        if (!valid) close()
        return valid
    }
    private fun fail(message: String) {
        if (closed) return
        close(); status.text = message
        failed(message) // Retire this exact window/request, not merely its WebView.
    }
    private fun empty() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    private fun showDocument(html: String) {
        if (!live()) return
        val activity = context as? android.app.Activity
        if (!OpenFluxVerificationWebProfile.ready || android.app.Application.getProcessName() != "${context.packageName}:openfluxVerification" ||
            activity == null || activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
            fail("Защищённое окно OpenFlux недоступно."); return
        }
        // This is a second size boundary, not a substitute for the receiver's hash/UTF8 receipt.
        if (html.isEmpty() || html.length > OpenFluxOwnDocument.MAX_BYTES) { fail("Страница превышает допустимый размер."); return }
        val raw = html.toByteArray(Charsets.UTF_8)
        val bounded = raw.size <= OpenFluxOwnDocument.MAX_BYTES; raw.fill(0)
        if (!bounded) { fail("Страница превышает допустимый размер."); return }
        try {
            val web = WebView(context); browser = web; web.isSaveEnabled = false
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = false
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                blockNetworkLoads = true; setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false; mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                @Deprecated("Legacy callback") override fun shouldOverrideUrlLoading(view: WebView, target: String) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = empty()
                @Deprecated("Legacy callback") override fun shouldInterceptRequest(view: WebView, target: String) = empty()
                override fun onPageStarted(view: WebView, target: String, icon: android.graphics.Bitmap?) {
                    if (!live() || target != url || channelSent) fail("Переход со встроенной страницы запрещён.")
                }
                override fun onPageFinished(view: WebView, target: String) {
                    if (!live() || target != url || channelSent) return
                    channelSent = true
                    try {
                        val pair = view.createWebMessageChannel(); ports = pair
                        pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                                if (!live()) return
                                if (!message.ports.isNullOrEmpty()) { message.ports?.forEach { runCatching { it.close() } }; fail("Формат ответа страницы отклонён."); return }
                                val payload = try { message.data } catch (_: Exception) { null }
                                if (payload == null) { fail("Формат ответа страницы отклонён."); return }
                                if (session.stage(payload)) {
                                    send.isEnabled = true
                                    status.text = "Страница подготовила ответ. Для передачи нажми «Передать ответ»."
                                } else fail("Ответ страницы отклонён. Нужен новый запрос проверки.")
                            }
                        }, handler)
                        // Exact synthetic HTTPS origin, never wildcard/null. Only main frame gets the port.
                        view.postWebMessage(WebMessage("mcrf-own-channel", arrayOf(pair[1])), Uri.parse(origin))
                        status.text = "Заполни встроенную страницу. Ответ не отправляется без подтверждения."
                    } catch (_: Exception) { fail("Закрытый канал страницы недоступен.") }
                }
                override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: android.net.http.SslError) { ssl.cancel(); fail("Сетевые переходы здесь запрещены.") }
                override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) { auth.cancel() }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail("Окно страницы остановлено. Нужен новый запрос."); return true }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage) = true
                override fun onPermissionRequest(permission: PermissionRequest) { permission.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            }
            web.setDownloadListener { _, _, _, _, _ -> status.text = "Скачивание файлов запрещено." }
            chrome.setPage(web)
            web.loadDataWithBaseURL(url, BOOTSTRAP + html, "text/html", "UTF-8", null)
        } catch (_: Exception) { fail("Не удалось открыть встроенную страницу.") }
    }
    override fun close() {
        if (closed) return
        closed = true; pendingHtml = null; session.close(); handler.removeCallbacksAndMessages(null)
        open.isEnabled = false; send.isEnabled = false
        ports?.forEach { runCatching { it.close() } }; ports = null
        browser?.let { web ->
            browser = null
            OpenFluxWebViewRetirement.retire(
                { (web.parent as? ViewGroup)?.removeView(web) }, { web.stopLoading() },
                { web.webChromeClient = null }, { web.webViewClient = WebViewClient() }, { web.destroy() })
        }
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
    companion object {
        // Installed before provider script. No Android JavascriptInterface, cookies,
        // file/content access, network, frames, workers, forms or external navigation.
        private val BOOTSTRAP = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><script>(function(){'use strict';let port=null,queued=null,used=false;Object.defineProperty(window,'openfluxSubmit',{configurable:false,writable:false,value:function(value){if(used)return;let text;try{text=JSON.stringify(value);}catch(e){return;}if(typeof text!=='string'||text.length>8192)return;used=true;if(port)port.postMessage(text);else queued=text;}});window.addEventListener('message',function(e){if((e.source!==null&&e.source!==window)||e.data!=='mcrf-own-channel'||!e.ports||e.ports.length!==1||port)return;port=e.ports[0];if(queued!==null){port.postMessage(queued);queued=null;}window.dispatchEvent(new Event('openflux-ready'));});})();</script>"""
    }
}
