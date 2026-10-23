package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.URI
import java.security.MessageDigest
import java.io.File
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray

object AppUpdates {
    private var started=false
    private var pending:AppRelease?=null
    private var shownDialog:AlertDialog?=null
    fun blocksOtherPrompt()=pending!=null||shownDialog?.isShowing==true||AppUpdateInstallUi.isShowing()
    fun onStart(activity:MainActivity){
        if(started)return;started=true
        val context=activity.applicationContext
        Thread({try {
            val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
            val connection=URI("https://api.github.com/repos/${EnginePackage.REPOSITORY}/releases?per_page=30").toURL().openConnection() as HttpsURLConnection
            val data=try {connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=15000;connection.readTimeout=15000;connection.setRequestProperty("User-Agent","MCRF-Android");require(connection.responseCode==200);connection.inputStream.use {JSONArray(String(ImportIO.read(it),Charsets.UTF_8))}}finally{connection.disconnect()}
            val found=AppRelease.find(data,abi)?:return@Thread
            activity.runOnUiThread {if(AppUpdatePresentation.newer(found.asset.version,BuildConfig.VERSION_NAME)&&activity.prefs.getString("ignoredUpdate",null)!=found.asset.version){pending=found;offerPending(activity)}}
        }catch(_:Exception){/* A failed background check never interrupts connection controls. */}},"mcrf-update-check").start()
    }
    fun offerPending(activity:MainActivity){
        val found=pending?:return
        if(!AppUpdatePresentation.newer(found.asset.version,BuildConfig.VERSION_NAME)){pending=null;return}
        if(activity.isFinishing||activity.isDestroyed||!activity.hasWindowFocus()||EngineStartupUpdates.isShowing())return
        pending=null
        showDialog(activity,found)
    }
    internal fun showDialog(activity:MainActivity,found:AppRelease):AlertDialog = with(activity) {
            val content=android.widget.LinearLayout(this).apply {
                orientation=android.widget.LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(16));tag="app-update-dialog"
            }
            content.addView(text("Обновление MCRF",18,Loft.INK),space(0,8))
            content.addView(text("${AppUpdatePresentation.buildLabel(BuildConfig.VERSION_NAME)}\n→ ${AppUpdatePresentation.buildLabel(found.asset.version)}\n${TrafficState.bytes(found.asset.bytes)}"+
                if(found.prerelease)" · тестовая"else"",12,Loft.MUTED),space(0,16))
            content.addView(android.widget.ScrollView(this).apply {
                addView(text(AppUpdatePresentation.summary(found.notes),14,Loft.INK))
            },android.widget.LinearLayout.LayoutParams(-1,0,1f))
            val actions=android.widget.LinearLayout(this).apply {orientation=android.widget.LinearLayout.HORIZONTAL}
            val dialog=ThemedDialogBuilder(this).create().apply {setView(content,0,0,0,0)}
            fun action(label:String,primary:Boolean=false,run:()->Unit) {
                actions.addView(button(label,primary).apply {
                    textSize=12f;setPadding(dp(3),0,dp(3),0);setOnClickListener {run()}
                },android.widget.LinearLayout.LayoutParams(0,dp(48),1f).apply {marginStart=dp(3);marginEnd=dp(3)})
            }
            action("Позже"){dialog.dismiss()}
            action("Пропустить"){prefs.edit().putString("ignoredUpdate",found.asset.version).apply();dialog.dismiss()}
            action("Обновить",true){dialog.dismiss();AppUpdateInstallUi.show(this,found)}
            content.addView(actions,space(16,0))
            shownDialog=dialog
            dialog.setOnDismissListener {if(shownDialog===dialog)shownDialog=null}
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {setColor(Loft.SURFACE or android.graphics.Color.BLACK);cornerRadius=dp(24).toFloat()})
                setLayout((resources.displayMetrics.widthPixels*.9f).toInt(),(resources.displayMetrics.heightPixels*.7f).toInt())
            }
            dialog
    }
    fun check(activity:MainActivity) {
        var release:AppRelease?=null
        activity.run(MainActivity.Operation {
            val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
            val connection=URI("https://api.github.com/repos/${EnginePackage.REPOSITORY}/releases?per_page=30").toURL().openConnection() as HttpsURLConnection
            val data=try {
                connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=15000;connection.readTimeout=15000
                connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("User-Agent","MCRF-Android")
                require(connection.responseCode==200) { "GitHub временно недоступен. Попробуй позже." }
                connection.inputStream.use { JSONArray(String(ImportIO.read(it),Charsets.UTF_8)) }
            }finally{connection.disconnect()}
            val found=AppRelease.find(data,abi)
            if(found==null) "Android APK для $abi ещё не опубликован в репозитории."
            else {
                if(!AppUpdatePresentation.newer(found.asset.version,BuildConfig.VERSION_NAME)) "Установлена актуальная версия Android."
                else { release=found;"Найден Android APK в репозитории" }
            }
        },false,Runnable {
            release?.let {found->pending=found;offerPending(activity)}
        })
    }
}
