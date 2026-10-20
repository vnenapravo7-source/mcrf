package org.mcrf.client

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse

/** User-operated VK window. First page is always the main vk.ru home. No JS bridge. */
class VkRelayLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var state: String
    private var oauthStarted=false
    private lateinit var callback: VkRelayCallback
    private lateinit var status: TextView
    private fun intercept(url: String): Boolean {
        val result=callback.accept(url,state,oauthStarted)
        if(result==VkRelayCallback.Result.IGNORE) return false
        web.stopLoading()
        when(result) {
            VkRelayCallback.Result.REJECT -> status.text="VK не подтвердил привязку. Повторите вход и нажмите «Привязать»."
            VkRelayCallback.Result.SAVED -> { CookieManager.getInstance().flush();setResult(RESULT_OK);finish() }
            VkRelayCallback.Result.SAVE_FAILED -> status.text="Не удалось сохранить привязку VK. Повторите попытку."
            else -> Unit
        }
        return true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        UserThemeColorsUi.configure(this)
        val theme=getSharedPreferences("MainActivity",MODE_PRIVATE).getString("theme","system")
        Loft.apply(theme=="dark"||theme=="system"&&resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTheme(if(Loft.dark)R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        callback=VkRelayCallback { auth -> VkRelayAccount(AndroidDeployStore(this)).link(auth) }
        state=savedInstanceState?.getString("state") ?: VkRelayProtocol.newState()
        oauthStarted=savedInstanceState?.getBoolean("oauth") ?: false
        fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));background=Loft.rootBackground(this@VkRelayLoginActivity) }
        status=TextView(this).apply { text="Войдите на главной странице VK. Затем нажмите «Привязать». Пароль вводится только на сайте VK.";setTextColor(Loft.INK);textSize=14f }
        layout.addView(status)
        layout.addView(themedButton("Привязать после входа").apply {minHeight=dp(48);setOnClickListener { state=VkRelayProtocol.newState();oauthStarted=true;status.text="Подтвердите доступ на странице VK. После привязки вернёмся в MCRF.";web.loadUrl(VkRelayProtocol.authorizeUrl(state)) } })
        web=WebView(this)
        web.settings.javaScriptEnabled=true;web.settings.domStorageEnabled=true
        web.settings.allowFileAccess=false;web.settings.allowContentAccess=false
        web.settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
        web.webViewClient=object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url=request.url.toString()
                if(!request.isForMainFrame) return !VkRelayProtocol.trusted(url)
                if(intercept(url)) return true
                return !VkRelayProtocol.trusted(url)
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView,url: String): Boolean = intercept(url) || !VkRelayProtocol.trusted(url)
            override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) { intercept(url) }
            override fun doUpdateVisitedHistory(view: WebView,url: String,isReload: Boolean) { intercept(url) }
            override fun onPageFinished(view: WebView,url: String) { intercept(url) }
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView,errorCode: Int,description: String?,failingUrl: String?) {
                if(!intercept(view.url.orEmpty())&&!intercept(failingUrl.orEmpty()))status.text="Страница не загрузилась. Проверьте сеть и нажмите «Открыть VK заново»."
            }
            override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError) {
                if(request.isForMainFrame && !intercept(view.url.orEmpty())&&!intercept(request.url.toString()))status.text="Страница не загрузилась. Проверьте сеть и нажмите «Открыть VK заново»."
            }
            override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,errorResponse: WebResourceResponse) {
                if(request.isForMainFrame && !intercept(view.url.orEmpty())&&!intercept(request.url.toString()))status.text="VK временно не открыл страницу. Можно открыть главную заново или вернуться в MCRF."
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel();status.text="Не удалось проверить сертификат VK. Небезопасная страница заблокирована." }
        }
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(themedButton("Открыть VK заново").apply {minHeight=dp(48);setOnClickListener {
            web.stopLoading();state=VkRelayProtocol.newState();oauthStarted=false
            status.text="Войдите в VK, затем нажмите «Привязать». Сохранённая привязка MCRF не удаляется."
            web.loadUrl(VkRelayProtocol.LOGIN_URL)
        }})
        layout.addView(themedButton("Вернуться в MCRF").apply {minHeight=dp(48);setOnClickListener {setResult(RESULT_CANCELED);finish()}})
        setContentView(layout)
        if(savedInstanceState==null) web.loadUrl(VkRelayProtocol.LOGIN_URL) else web.restoreState(savedInstanceState)
    }
    private fun themedButton(label:String)=StandardButtons.style(this,Button(this),label)
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("state",state);outState.putBoolean("oauth",oauthStarted);web.saveState(outState);super.onSaveInstanceState(outState) }
    override fun onDestroy() { web.destroy();super.onDestroy() }
}
