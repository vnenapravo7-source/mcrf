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
    private var desktopLogin=false
    private lateinit var callback: VkRelayCallback
    private lateinit var status: TextView
    private fun intercept(url: String): Boolean {
        if(isFinishing||isDestroyed){callback.close();return true}
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
        desktopLogin=savedInstanceState?.getBoolean("desktopLogin") ?: false
        fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));background=Loft.rootBackground(this@VkRelayLoginActivity);tag="vk-login-layout" }
        status=TextView(this).apply { text="Войдите в VK, затем нажмите «Привязать».";setTextColor(Loft.INK);textSize=13f;includeFontPadding=false;tag="vk-login-status" }
        layout.addView(status,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
        layout.addView(themedButton("Привязать").apply {minHeight=dp(48);tag="vk-login-link";setOnClickListener { state=VkRelayProtocol.newState();oauthStarted=true;status.text="Подтвердите доступ на странице VK.";web.loadUrl(VkRelayProtocol.authorizeUrl(state)) } },LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        web=WebView(this)
        val defaultAgent=web.settings.userAgentString
        web.settings.userAgentString=if(desktopLogin)VkRelayWebLogin.desktopAgent(defaultAgent)else VkRelayWebLogin.browserAgent(defaultAgent)
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
                if(!intercept(view.url.orEmpty())&&!intercept(failingUrl.orEmpty()))status.text="Не удалось открыть VK. Проверьте сеть или нажмите «Обновить»."
            }
            override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError) {
                if(request.isForMainFrame && !intercept(view.url.orEmpty())&&!intercept(request.url.toString()))status.text="Не удалось открыть VK. Проверьте сеть или нажмите «Обновить»."
            }
            override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,errorResponse: WebResourceResponse) {
                if(request.isForMainFrame && !intercept(view.url.orEmpty())&&!intercept(request.url.toString()))status.text="VK временно недоступен. Нажмите «Обновить» или используйте «Назад»."
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel();status.text="Не удалось проверить сертификат VK. Небезопасная страница заблокирована." }
        }
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        val actions=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;isBaselineAligned=false;tag="vk-login-actions"}
        actions.addView(themedButton("Другой вход").apply {minHeight=dp(48);tag="vk-login-alternate";setOnClickListener {
            web.stopLoading();desktopLogin=!desktopLogin;state=VkRelayProtocol.newState();oauthStarted=false
            web.settings.userAgentString=if(desktopLogin)VkRelayWebLogin.desktopAgent(defaultAgent)else VkRelayWebLogin.browserAgent(defaultAgent)
            status.text="${if(desktopLogin)"Полная"else"Мобильная"} версия VK · после входа нажмите «Привязать»."
            web.loadUrl(VkRelayProtocol.LOGIN_URL)
        }},LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(4)})
        actions.addView(themedButton("Обновить").apply {minHeight=dp(48);tag="vk-login-reload";setOnClickListener {
            web.stopLoading();state=VkRelayProtocol.newState();oauthStarted=false
            status.text="Войдите в VK, затем нажмите «Привязать»."
            web.loadUrl(VkRelayProtocol.LOGIN_URL)
        }},LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=dp(4)})
        layout.addView(actions,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        setContentView(layout)
        if(savedInstanceState==null) web.loadUrl(VkRelayProtocol.LOGIN_URL) else web.restoreState(savedInstanceState)
    }
    private fun themedButton(label:String)=StandardButtons.style(this,Button(this),label)
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("state",state);outState.putBoolean("oauth",oauthStarted);outState.putBoolean("desktopLogin",desktopLogin);web.saveState(outState);super.onSaveInstanceState(outState) }
    override fun onDestroy() { if(::callback.isInitialized)callback.close();if(::web.isInitialized)web.destroy();super.onDestroy() }
}
