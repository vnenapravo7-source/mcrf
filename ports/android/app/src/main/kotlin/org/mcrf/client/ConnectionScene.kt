package org.mcrf.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Fixed packaged drawing only. No remote documents, JS/Java bridge, credentials or service actions. */
@SuppressLint("SetJavaScriptEnabled")
class ConnectionScene(context:Context):WebView(context) {
    private var loaded=false
    private var disposed=false
    private var pending:JSONObject?=null
    private var sent=""
    var rendererError:String?=null
        private set
    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable=false;isFocusableInTouchMode=false
        isHorizontalScrollBarEnabled=false;isVerticalScrollBarEnabled=false
        settings.apply {
            javaScriptEnabled=true;blockNetworkLoads=true;allowFileAccess=false;allowContentAccess=false
            domStorageEnabled=false;databaseEnabled=false;cacheMode=WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs=false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs=false
            javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture=true
        }
        webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view:WebView,url:String)=true
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=
                WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
            override fun onPageFinished(view:WebView,url:String){
                if(disposed)return
                loaded=true;flush()
            }
        }
        loadDataWithBaseURL("https://mcrf.invalid/connection-scene/",context.assets.open("connection-scene-r10.html").bufferedReader().use {it.readText()},"text/html","UTF-8",null)
    }
    fun present(style:String,exit:String,signal:ConnectionMotion.Signal,speed:Double,motion:Boolean,visible:Boolean){
        if(disposed)return
        fun hex(color:Int)=String.format(java.util.Locale.ROOT,"#%06X",color and 0xffffff)
        pending=JSONObject().put("style",ConnectionAppearance.canvasStyle(style)).put("exit",ConnectionAppearance.canvasExit(exit))
            .put("signal",signal.name).put("speed",if(speed.isFinite())speed.coerceAtLeast(0.0)else 0.0)
            .put("motion",motion).put("visible",visible)
            .put("palette",JSONObject().put("bg",hex(Loft.BACKGROUND)).put("panel",hex(Loft.SURFACE))
                .put("text",hex(Loft.INK)).put("muted",hex(Loft.MUTED)).put("line",hex(Loft.LINE))
                .put("metal",hex(Loft.MUTED)).put("steel",hex(Loft.FIELD)))
        flush()
    }
    private fun flush(){
        val payload=pending?.toString()?:return
        if(!loaded||disposed||payload==sent)return
        sent=payload
        evaluateJavascript("window.McrfScene ? JSON.stringify(window.McrfScene.update($payload)) : null"){result->
            if(result=="null"){rendererError="Renderer unavailable";return@evaluateJavascript}
            try {
                val report=JSONObject(org.json.JSONTokener(result).nextValue() as String)
                rendererError=report.optString("error").takeIf {it.isNotEmpty()}
            }catch(_:Exception){rendererError="Invalid renderer response"}
        }
    }
    /** Only the fresh disposable DEBUG fixture may force a drawing clock. */
    internal fun fixture(script:String,done:(String?)->Unit){
        check(BuildConfig.DEBUG&&context.packageName=="org.mcrf.client.animationfixture10")
        check(script.startsWith("JSON.stringify(window.McrfScene.")) 
        evaluateJavascript(script,done)
    }
    internal val isRendererLoaded get()=loaded&&!disposed
    fun dispose(){
        if(disposed)return
        disposed=true;pending=null;stopLoading();removeAllViews();destroy()
    }
}
