package org.mcrf.client

import android.app.Instrumentation
import android.webkit.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Android WebView + production JS, entirely owned HTML; no provider/network/VPN. */
object CarrierAutoPageDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val report=StringBuilder()
        for(slider in listOf(false,true)) {
            val result=AtomicReference<String>();val done=CountDownLatch(1);val unexpectedNetwork=AtomicInteger()
            val pages=AtomicInteger();val prompts=AtomicInteger();val ownedLoads=AtomicInteger();val jsErrors=AtomicInteger()
            val clicks=AtomicReference<String>();val inspected=CountDownLatch(1)
            val fixtureUrl="https://vk.com/mcrf-owned-fixture"
            val html="""<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
<script>window.checks=0;window.fetch=function(){return Promise.resolve({clone:function(){return {json:function(){return Promise.resolve({response:{success_token:'owned-webview-proof'}});}};}});};</script>
${if(slider)"<div class='SliderCaptcha'>Owned puzzle fixture</div>"else""}
<label for="not-robot-captcha-checkbox" style="display:block;width:200px;height:40px" onclick="window.checks++;window.fetch('/method/captchaNotRobot.check')">Owned checkbox</label>
<input type="checkbox" id="not-robot-captcha-checkbox">"""
            var browser:WebView?=null
            try {
                test.runOnMainSync {
                    browser=WebView(test.targetContext).apply {
                        settings.javaScriptEnabled=true
                        settings.allowFileAccess=false;settings.allowContentAccess=false
                        webViewClient=object:WebViewClient(){
                            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                                if(request.isForMainFrame&&request.url.toString()==fixtureUrl){
                                    ownedLoads.incrementAndGet()
                                    return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                                }
                                if(request.url.path=="/favicon.ico")return WebResourceResponse("image/png","UTF-8",ByteArrayInputStream(byteArrayOf()))
                                unexpectedNetwork.incrementAndGet()
                                return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(byteArrayOf()))
                            }
                            override fun onPageFinished(view:WebView,url:String) {
                                pages.incrementAndGet()
                                view.evaluateJavascript(CarrierCaptchaAutoPage.SCRIPT,null)
                                view.evaluateJavascript(CarrierCaptchaAutoPage.SCRIPT,null)
                            }
                        }
                        webChromeClient=object:WebChromeClient(){
                            override fun onJsPrompt(view:WebView,url:String,message:String,defaultValue:String,answer:JsPromptResult):Boolean {
                                prompts.incrementAndGet();answer.confirm("")
                                if(CarrierChallenge.allowed(url)&&CarrierChallenge.allowed(view.url.orEmpty()))CarrierCaptchaAutoPage.result(message)?.let {result.set(it);done.countDown()}
                                return true
                            }
                            override fun onConsoleMessage(message:ConsoleMessage):Boolean {
                                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)jsErrors.incrementAndGet()
                                return true
                            }
                        }
                        measure(android.view.View.MeasureSpec.makeMeasureSpec(360,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(400,android.view.View.MeasureSpec.EXACTLY));layout(0,0,360,400)
                        loadUrl(fixtureUrl) // All requests served above or denied locally; never a provider request.
                    }
                }
                check(done.await(10,TimeUnit.SECONDS)){"Owned WebView result missing: pages=${pages.get()},prompts=${prompts.get()},jsErrors=${jsErrors.get()},network=${unexpectedNetwork.get()},ownedLoads=${ownedLoads.get()}"}
                check(result.get()==if(slider)"error:slider_detected" else "owned-webview-proof"){"Unexpected owned WebView result"}
                test.runOnMainSync {browser!!.evaluateJavascript("String(window.checks)"){clicks.set(it);inspected.countDown()}}
                check(inspected.await(2,TimeUnit.SECONDS)){"Owned checkbox count unavailable"}
                check(clicks.get()==if(slider)"\"0\""else"\"1\""){"Repeated or unexpected checkbox action"}
                check(unexpectedNetwork.get()==0){"Owned fixture attempted a network request"}
                check(ownedLoads.get()==1){"Owned fixture main document was not intercepted exactly once"}
                report.append("PASS actual WebView ").append(if(slider)"interactive-puzzle fallback without clicks"else"origin-checked proof, idempotent single checkbox action").append("; no network\n")
            }finally {test.runOnMainSync {browser?.apply {stopLoading();webChromeClient=null;webViewClient=WebViewClient();destroy()};browser=null}}
        }
        return report.toString()
    }
}
