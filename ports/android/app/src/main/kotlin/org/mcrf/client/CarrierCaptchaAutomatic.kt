package org.mcrf.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*

/** Native owns the attempt order; Android completes only the requested WebView stage.
 * One browser at a time, original native deadline, no account/fingerprint changes.
 * Interactive puzzles return control to the native fallback instead of a hidden slider solver.
 */
object CarrierCaptchaAutomatic {
    private val main=Handler(Looper.getMainLooper())
    private val diagnosticWorker=CarrierCaptchaLogDelivery()
    private var running:String?=null
    fun start(context:Context,id:String,kind:String="") {main.post {Attempt(context.applicationContext,id,kind).tick()}}

    private class Attempt(val context:Context,val id:String,val kind:String) {
        private val startedAt=android.os.SystemClock.elapsedRealtime()
        private var httpStatus:Int?=null;private var tlsError:Int?=null;private var networkError:Int?=null
        private fun diagnostic(message:String){
            diagnosticWorker.offer {ClientEvents.record(context,"Капча",message)}
        }
        private var browser:WebView?=null
        private var done=false
        private var reply:String?=null
        private var ownsBrowser=false
        private val tick=Runnable {tick()}
        private fun finish(value:String?) {
            if(done)return
            // A full IPC delivery pool must not turn into a lost result. Retry within
            // this request's original deadline; never call a native pipe on the UI thread.
            if(value!=null&&!CarrierChallenge.respondAsync(id,value)) {
                reply=value;main.postDelayed(tick,100);return
            }
            done=true;main.removeCallbacks(tick)
            browser?.apply {stopLoading();webChromeClient=null;webViewClient=WebViewClient();destroy()};browser=null
            if(ownsBrowser)CarrierChallenge.releaseBrowser(id)
            if(running==id)running=null
            diagnostic(CarrierCaptchaDiagnostics.finished(kind,value,android.os.SystemClock.elapsedRealtime()-startedAt,httpStatus,tlsError,networkError))
        }
        fun tick() {
            if(done)return
            val request=CarrierChallenge.get(id)
            if(request==null||!request.automatic){finish(null);return}
            reply?.let {finish(it);return}
            if(request.remainingMillis()<=700){finish("error:auto_timeout");return}
            if(browser==null) {
                if(running!=null||!CarrierChallenge.claimBrowser(id)){main.postDelayed(tick,100);return}
                running=id;ownsBrowser=true
                diagnostic(CarrierCaptchaDiagnostics.started(kind,request.remainingMillis()))
                try {
                    browser=WebView(context).apply {
                        settings.apply {javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false)}
                        webViewClient=object:WebViewClient(){
                            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                                val blocked=!CarrierChallenge.allowed(request.url.toString())
                                if(blocked&&request.isForMainFrame)finish("error:auto_origin")
                                return blocked
                            }
                            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){tlsError=error.primaryError;handler.cancel();finish("error:auto_tls")}
                            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame){networkError=error.errorCode;finish("error:auto_network")}}
                            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame){httpStatus=response.statusCode;finish("error:auto_http")}}
                            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {finish("error:auto_webview");return true}
                        }
                        webChromeClient=object:WebChromeClient(){
                            override fun onJsPrompt(view:WebView,url:String,message:String,defaultValue:String,result:JsPromptResult):Boolean {
                                // Android supplies the requesting document URL here. Injected
                                // JavaScript console messages can have an empty sourceId.
                                result.confirm("")
                                if(CarrierChallenge.allowed(url)&&CarrierChallenge.allowed(view.url.orEmpty()))CarrierCaptchaAutoPage.result(message)?.let(::finish)
                                return true // Private bridge, never a visible JS dialog.
                            }
                            override fun onConsoleMessage(message:ConsoleMessage)=true
                        }
                        val density=resources.displayMetrics.density
                        val width=(360*density).toInt();val height=(400*density).toInt()
                        measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                        layout(0,0,width,height)
                    }
                    browser?.loadUrl(request.url)
                }catch(_:Exception){finish("error:auto_webview");return}
            }
            browser?.let {view->if(CarrierChallenge.allowed(view.url.orEmpty()))view.evaluateJavascript(CarrierCaptchaAutoPage.SCRIPT,null)}
            if(!done)main.postDelayed(tick,200)
        }
    }
}
