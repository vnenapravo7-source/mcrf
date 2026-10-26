package org.mcrf.client

import android.app.AlertDialog
import android.os.Build
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray

object AppUpdates {
    private var started=false
    private var pending:AppRelease?=null
    private var shownDialog:AlertDialog?=null
    fun blocksOtherPrompt()=pending!=null||shownDialog?.isShowing==true||AppUpdateInstallUi.isShowing()
    private fun releases():JSONArray {
        val all=JSONArray()
        for(page in 1..10) {
            val connection=URI("https://api.github.com/repos/${EnginePackage.REPOSITORY}/releases?per_page=100&page=$page").toURL().openConnection() as HttpsURLConnection
            val rows=try {
                connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=15000;connection.readTimeout=15000
                connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("User-Agent","MCRF-Android")
                require(connection.responseCode==200){"GitHub временно недоступен. Попробуй позже."}
                connection.inputStream.use {JSONArray(String(ImportIO.read(it),Charsets.UTF_8))}
            }finally{connection.disconnect()}
            repeat(rows.length()){index->
                val row=rows.getJSONObject(index);all.put(row)
            }
            // An older tag in this page cannot prove later pages are older.
            if(AppReleasePagination.complete(rows.length()))return all
        }
        throw IllegalArgumentException("Не удалось получить всю историю обновлений. Повторите проверку позже.")
    }
    fun onStart(activity:MainActivity){
        if(started)return;started=true
        Thread({try {
            val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
            val data=releases()
            val found=AppRelease.find(data,abi)?.let {AppRelease.withHistory(it,data,BuildConfig.VERSION_NAME)}?:return@Thread
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
                addView(text(found.notes.ifBlank {"Исправления и улучшения приложения."},14,Loft.INK))
            },android.widget.LinearLayout.LayoutParams(-1,0,1f))
            // App-owned row: Android's ButtonBarLayout stacks three actions on narrow screens.
            val actions=android.widget.LinearLayout(this).apply {
                orientation=android.widget.LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;tag="app-update-actions"
            }
            fun action(label:String,tagValue:String,primary:Boolean=false)=android.widget.Button(this).apply {
                tag=tagValue;StandardButtons.style(this@with,this,label,primary)
                setPadding(dp(7),dp(10),dp(7),dp(10));setSingleLine(true)
                setAutoSizeTextTypeUniformWithConfiguration(12,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                actions.addView(this,android.widget.LinearLayout.LayoutParams(0,dp(48),paint.measureText(label)+dp(14)).apply {
                    if(actions.childCount>0)marginStart=dp(6)
                })
            }
            val later=action("Позже","app-update-later")
            val skip=action("Пропустить","app-update-skip")
            val update=action("Обновить","app-update-install",true)
            content.addView(actions,android.widget.LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16)})
            val dialog=ThemedDialogBuilder(this).setView(content)
                .create()
            later.setOnClickListener {dialog.dismiss()}
            skip.setOnClickListener {prefs.edit().putString("ignoredUpdate",found.asset.version).apply();dialog.dismiss()}
            update.setOnClickListener {dialog.dismiss();AppUpdateInstallUi.show(this,found)}
            shownDialog=dialog
            dialog.setOnDismissListener {if(shownDialog===dialog)shownDialog=null}
            dialog.show()
            dialog.window?.apply {
                setLayout((resources.displayMetrics.widthPixels*.9f).toInt(),(resources.displayMetrics.heightPixels*.7f).toInt())
            }
            dialog
    }
    fun check(activity:MainActivity) {
        var release:AppRelease?=null
        activity.run(MainActivity.Operation {
            val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
            val data=releases()
            val found=AppRelease.find(data,abi)?.let {AppRelease.withHistory(it,data,BuildConfig.VERSION_NAME)}
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
