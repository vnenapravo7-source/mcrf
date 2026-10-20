package org.mcrf.client

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.widget.*

/** User solves VK's own page. No automatic permission, login or captcha clicks. */
class CarrierCaptchaActivity : Activity() {
    private var id="";private var web:WebView?=null;private var answered=false;private var ownsBrowser=false
    private var pendingReply:CarrierManualReply?=null
    private class RetainedReply(val requestId:String,val reply:CarrierManualReply)
    private var replyRetained=false
    private var statusView:TextView?=null
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val replyRetry=Runnable {sendPendingReply()}
    private val expiry=object:Runnable{override fun run(){if(CarrierChallenge.get(id)==null) finish() else handler.postDelayed(this,500)}}
    private fun sendPendingReply(){
        handler.removeCallbacks(replyRetry)
        when(pendingReply?.attempt()){
            CarrierManualReply.State.WAITING->{statusView?.text="Ответ готов. Ожидаем свободную отправку — повторно проходить проверку не нужно.";handler.postDelayed(replyRetry,100)}
            CarrierManualReply.State.SCHEDULED->{answered=true;finish()}
            CarrierManualReply.State.EXPIRED->finish()
            else->Unit
        }
    }
    override fun onCreate(state: Bundle?) {
        UserThemeColorsUi.configure(this)
        val theme=getSharedPreferences("MainActivity",MODE_PRIVATE).getString("theme","system")
        Loft.apply(theme=="dark"||theme=="system"&&resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTheme(if(Loft.dark)R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state);id=intent.getStringExtra("request").orEmpty()
        val request=CarrierChallenge.get(id) ?: run { finish();return }
        if(request.automatic){finish();return}
        ownsBrowser=CarrierChallenge.claimBrowser(id)
        if(!ownsBrowser){finish();return} // Duplicate taps cannot cancel the original owner's challenge.
        val requestId=id // Retained callbacks must not capture the old Activity.
        val retained=lastNonConfigurationInstance as? RetainedReply
        pendingReply=if(retained?.requestId==requestId&&retained.reply.hasPending())retained.reply
            else CarrierManualReply({CarrierChallenge.get(requestId)!=null},{CarrierChallenge.respondAsync(requestId,it)})
        request.opened=true
        fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));background=Loft.rootBackground(this@CarrierCaptchaActivity) }
        root.addView(TextView(this).apply { text="VK просит подтвердить, что ты не робот. После проверки подключение продолжится.";textSize=16f;setTextColor(Loft.INK);setPadding(0,0,0,dp(16)) })
        val browser=WebView(this);web=browser
        val status=TextView(this).apply {text=if(request.timeoutMillis<150000)"Время этой проверки ограничено движком. Если не успеешь, повтори подключение и открой проверку сразу."else"Пройди проверку на странице VK.";setTextColor(Loft.MUTED);setPadding(0,0,0,dp(8))}
        statusView=status
        root.addView(status)
        browser.settings.apply { javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false) }
        browser.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean = !CarrierChallenge.allowed(request.url.toString())
            override fun onReceivedSslError(view:WebView,handler:android.webkit.SslErrorHandler,error:android.net.http.SslError){handler.cancel();status.text="Не удалось проверить сертификат VK. Небезопасная страница заблокирована."}
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                if(request.isForMainFrame)status.text="Страница не загрузилась. Проверь сеть и нажми «Повторить»."
            }
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){
                if(request.isForMainFrame)status.text="VK временно не открыл страницу. Можно повторить загрузку."
            }
            override fun onPageFinished(view:WebView,url:String){if(CarrierChallenge.allowed(url)) view.evaluateJavascript(HOOK,null)}
        }
        browser.webChromeClient=object:WebChromeClient(){
            override fun onJsPrompt(view:WebView,url:String,message:String,defaultValue:String,result:JsPromptResult):Boolean {
                if(!message.startsWith(PREFIX))return super.onJsPrompt(view,url,message,defaultValue,result)
                result.confirm("")
                if(CarrierChallenge.allowed(url)&&CarrierChallenge.allowed(view.url.orEmpty())){
                    val proof=message.removePrefix(PREFIX);if(proof.length in 1..16384 && proof.none { it.isISOControl() || it=='|' }&&!proof.startsWith("error:",true)) {
                        pendingReply?.accept(proof)
                        sendPendingReply()
                    }
                }
                return true
            }
            override fun onConsoleMessage(message:ConsoleMessage)=true // Never log provider output/proofs.
        }
        root.addView(browser,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(themedButton("Повторить загрузку").apply {setOnClickListener {
            if(CarrierChallenge.get(this@CarrierCaptchaActivity.id)!=null){
                if(pendingReply?.hasPending()==true)sendPendingReply()
                else {status.text="Загрузка страницы VK…";browser.loadUrl(request.url)}
            }else finish()
        }},LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(themedButton("Отмена").apply {setOnClickListener { finish() } },LinearLayout.LayoutParams(-1,dp(56)))
        setContentView(root)
        if(pendingReply?.hasPending()==true)sendPendingReply() else browser.loadUrl(request.url)
    }
    private fun themedButton(label:String)=StandardButtons.style(this,Button(this),label)
    override fun onResume(){super.onResume();handler.post(expiry)}
    override fun onPause(){handler.removeCallbacks(expiry);super.onPause()}
    override fun onRetainNonConfigurationInstance():Any? {
        val reply=pendingReply?.takeIf {it.hasPending()}?:return null
        if(CarrierChallenge.get(id)==null)return null
        handler.removeCallbacks(replyRetry)
        replyRetained=true
        // Memory only, same request and original deadline; never Bundle/prefs.
        return RetainedReply(id,reply)
    }
    override fun onDestroy(){handler.removeCallbacks(replyRetry);if(!replyRetained)pendingReply?.clear();pendingReply=null;statusView=null
        web?.apply { stopLoading();webChromeClient=null;webViewClient=WebViewClient();destroy() };web=null
        if(ownsBrowser){
            if(!isChangingConfigurations&&!answered&&!CarrierChallenge.respondAsync(id,"error:cancelled")) {
                CarrierChallenge.get(id)?.opened=false
                CarrierChallengeNotifications.show(this,id)
            }
            CarrierChallenge.releaseBrowser(id);ownsBrowser=false
        }
        super.onDestroy()}
    companion object {
        private const val PREFIX="MCRF_CAPTCHA_TOKEN|"
        private const val HOOK="""(function(){if(window.__mcrfCaptcha)return;window.__mcrfCaptcha=true;
function report(d){var t=d&&d.response&&d.response.success_token;if(typeof t==='string'&&t.length<=16384)window.prompt('MCRF_CAPTCHA_TOKEN|'+t,'');}
var old=window.fetch;window.fetch=function(){var args=arguments,u=args[0],url=typeof u==='string'?u:(u&&u.url)||'';return old.apply(this,args).then(function(r){if(url.indexOf('captchaNotRobot.check')>=0)r.clone().json().then(report).catch(function(){});return r;});};
var open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){this.__mcrfUrl=String(u);return open.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(){if((this.__mcrfUrl||'').indexOf('captchaNotRobot.check')>=0)this.addEventListener('load',function(){try{report(JSON.parse(this.responseText));}catch(e){}});return send.apply(this,arguments);};})();"""
    }
}
