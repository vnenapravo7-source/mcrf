package org.mcrf.client

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.widget.*
import java.io.ByteArrayInputStream

/** User-driven external provider check. No JS bridge, automation or VK origin expansion. */
class OpenFluxVerificationActivity : Activity() {
    private lateinit var remote: OpenFluxVerificationClient
    private val registry get() = remote
    private var bound = false
    private var resumed = false
    private var id = ""
    private var owns = false
    private var browser: WebView? = null
    private var loaded = false
    private var failed = false
    @Volatile private var approved: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expiry = object : Runnable { override fun run() {
        remote.poll()
        if (registry.get(id) == null) finish() else handler.postDelayed(this, 500)
    } }
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, endpoint: android.os.IBinder) {
            if (isFinishing || isDestroyed) return
            try {
                remote = OpenFluxVerificationClient(endpoint, id)
                owns = true
                createContent(requireNotNull(remote.get(id)))
                if (resumed) handler.post(expiry)
            } catch (_: Exception) { finish() }
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) { finish() }
        override fun onBindingDied(name: android.content.ComponentName) { finish() }
        override fun onNullBinding(name: android.content.ComponentName) { finish() }
    }

    override fun onCreate(state: Bundle?) {
        UserThemeColorsUi.configure(this)
        val theme = getSharedPreferences("MainActivity", MODE_PRIVATE).getString("theme", "system")
        Loft.apply(theme == "dark" || theme == "system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (Loft.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        id = intent.getStringExtra("request").orEmpty()
        if (!OpenFluxVerificationWebProfile.ready || !id.matches(OpenFluxVerificationClient.ID)) { finish(); return }
        setContentView(TextView(this).apply { text = "Подготовка текущей проверки OpenFlux…"; setTextColor(Loft.INK) })
        val bind = android.content.Intent(this, OpenFluxVerificationService::class.java)
            .setData(android.net.Uri.parse("mcrf-openflux-bind://request/$id/${java.util.UUID.randomUUID()}"))
            .putExtra("request", id)
        bound = try { bindService(bind, connection, BIND_AUTO_CREATE) } catch (_: Exception) { false }
        if (!bound) finish()
    }
    private fun createContent(request: OpenFluxVerificationClient.Request) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        fun label(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Loft.INK); setPadding(0, 0, 0, dp(12)); isSaveEnabled = false
        }
        fun button(value: String) = StandardButtons.style(this, Button(this), value).apply { maxLines = 3; ellipsize = null }
        val root = OpenFluxVerificationLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = Loft.rootBackground(this@OpenFluxVerificationActivity); isSaveEnabled = false
        }
        root.details.addView(label("Проверка OpenFlux", 20f))
        root.details.addView(label("Сайт: ${request.page.origin}\n\nОткрывай только знакомый сайт. После ручной проверки можно передать cookies этому запросу OpenFlux.\n\nСтраница использует отдельное хранилище WebView OpenFlux; MCRF не записывает cookies в настройки или логи."))
        val status = label(if (request.submitted.get()) "Ответ отправлен в очередь. Ожидаем текущий движок." else "Страница ещё не открыта.")
        root.actions.addView(status)
        val web = WebView(this).also { browser = it; it.isSaveEnabled = false; it.visibility = android.view.View.GONE }
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        fun allowed(target: String) = registry.get(id) === request && OpenFluxVerificationPolicy.mayNavigate(request.page, target, approved)
        fun block() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, resource: WebResourceRequest): Boolean {
                val blocked = !allowed(resource.url.toString())
                if (blocked) status.text = "Переход на другой сайт заблокирован. Ответ передаётся только исходному сайту."
                return blocked
            }
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = !allowed(url)
            override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? {
                if (registry.get(id) !== request) return block()
                val target = resource.url.toString()
                if (resource.isForMainFrame) return if (allowed(target)) null else block()
                // No mixed/local/file/content resources. Dependencies are site code;
                // they do not gain an Android JS interface or third-party cookies.
                return if (OpenFluxVerificationPolicy.externalPage(target, "", false, "") != null) null else block()
            }
            override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                loaded = false; failed = false
                if (!allowed(url)) { failed = true; view.stopLoading(); status.text = "Адрес страницы отклонён." }
            }
            override fun onPageFinished(view: WebView, url: String) {
                loaded = !failed && allowed(url)
                if (loaded) status.text = "Пройди проверку вручную, затем нажми «Передать ответ»."
            }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: android.net.http.SslError) {
                failed = true; loaded = false; ssl.cancel(); status.text = "Сертификат сайта не прошёл проверку. Продолжение запрещено."
            }
            override fun onReceivedError(view: WebView, resource: WebResourceRequest, error: WebResourceError) {
                if (resource.isForMainFrame) { failed = true; loaded = false; status.text = "Страница не загрузилась. Можно повторить открытие сайта." }
            }
            override fun onReceivedHttpError(view: WebView, resource: WebResourceRequest, response: WebResourceResponse) {
                if (resource.isForMainFrame) { failed = true; loaded = false; status.text = "Сайт вернул ошибку. Ответ не передан." }
            }
            override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) { auth.cancel() }
            override fun onSafeBrowsingHit(view: WebView, resource: WebResourceRequest, threatType: Int, response: SafeBrowsingResponse) { response.backToSafety(true) }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                failed = true; loaded = false; browser = null
                OpenFluxWebViewRetirement.retire(
                    { (view.parent as? android.view.ViewGroup)?.removeView(view) }, {}, {}, {}, { view.destroy() })
                finish(); return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage) = true
            override fun onPermissionRequest(permission: PermissionRequest) { permission.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
        }
        web.setDownloadListener { _, _, _, _, _ -> status.text = "Скачивание файлов в окне проверки отключено." }
        root.setPage(web)
        val open = button("Открыть сайт").apply { isEnabled = !request.submitted.get(); setOnClickListener {
            if (registry.get(request.id) !== request) { finish(); return@setOnClickListener }
            approved = request.page.origin; loaded = false; failed = false
            status.text = "Загрузка ${request.page.origin}…"; web.visibility = android.view.View.VISIBLE
            web.loadUrl(request.page.url)
        } }
        root.actions.addView(open, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val actions = LinearLayout(this).apply { isBaselineAligned = false }
        val cancel = button("Отмена").apply { setOnClickListener { finish() } }
        val send = button("Передать ответ").apply { isEnabled = !request.submitted.get(); setOnClickListener {
            if (!loaded || failed || !allowed(web.url.orEmpty())) { status.text = "Сначала открой сайт и пройди проверку."; return@setOnClickListener }
            val cookies = CookieManager.getInstance().getCookie(request.page.url).orEmpty()
            if (cookies.isBlank()) { status.text = "Сайт пока не выдал cookies. Заверши проверку вручную."; return@setOnClickListener }
            if (request.submit(cookies)) {
                isEnabled = false; open.isEnabled = false; web.stopLoading()
                status.text = "Ответ отправлен в очередь текущего движка. Это ещё не подтверждение подключения."
            } else status.text = "Ответ не отправлен: запрос истёк или очередь занята. Можно повторить отправку."
        } }
        actions.addView(cancel, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        actions.addView(send, LinearLayout.LayoutParams(0, -1, 1f))
        root.actions.addView(actions); setContentView(root)
    }
    override fun onResume() { super.onResume(); resumed = true; if (owns) handler.post(expiry) }
    override fun onPause() { resumed = false; handler.removeCallbacks(expiry); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(expiry)
        val retiringBrowser = browser; browser = null
        retiringBrowser?.let { web ->
            OpenFluxWebViewRetirement.retire(
                { (web.parent as? android.view.ViewGroup)?.removeView(web) }, { web.stopLoading() },
                { web.webChromeClient = null }, { web.webViewClient = WebViewClient() }, { web.destroy() })
        }
        if (owns) {
            remote.close(isChangingConfigurations)
        } else OpenFluxVerificationUi.failedOpening(this, id)
        if (bound) { runCatching { unbindService(connection) }; bound = false }
        super.onDestroy()
    }
}
