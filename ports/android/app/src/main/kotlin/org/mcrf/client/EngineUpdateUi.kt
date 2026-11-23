package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

object EngineUpdatePresentation {
    enum class Action(val label:String){CHECK("Проверить обновление"),DOWNLOAD("Скачать обновление"),INSTALL("Установить обновление"),CANCEL("Отменить загрузку"),WAIT("Ожидаем Android")}
    fun action(phase:EngineUpdatePolicy.Phase)=when(phase){
        EngineUpdatePolicy.Phase.AVAILABLE->Action.DOWNLOAD
        EngineUpdatePolicy.Phase.QUEUED,EngineUpdatePolicy.Phase.CONSENT->Action.INSTALL
        EngineUpdatePolicy.Phase.DOWNLOADING->Action.CANCEL
        EngineUpdatePolicy.Phase.CHECKING,EngineUpdatePolicy.Phase.INSTALLING->Action.WAIT
        else->Action.CHECK
    }
}

/** Compact OpenFlux section for the host Updates page; does not handle application updates. */
object EngineUpdateUi {
    private val processLaunch=EngineUpdateProcessLaunch()
    /** Called by the existing application-update launch hook, never by resume. */
    fun onProcessLaunch(activity:MainActivity) {
        val app=activity.applicationContext
        val handler=Handler(Looper.getMainLooper())
        val check=object:Runnable {override fun run() {
            if(!runCatching {processLaunch.start {
                if(EngineUpdates.isBusy()||EngineUpdates.installationPending(app))false
                else EngineUpdates.check(app)
            }}.getOrDefault(false))handler.postDelayed(this,2000)
        }}
        handler.post(check)
    }
    fun view(activity:MainActivity):LinearLayout=with(activity){
        val handler=Handler(Looper.getMainLooper());var preparing=false
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="openflux-updates"}
        body.addView(LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL;isBaselineAligned=false
            addView(text("OpenFlux",18,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
            addView(iconButton("info","О движке OpenFlux").apply {
                setOnClickListener {alert("OpenFlux",EngineUpdateScreenPolicy.additionalHelp("openflux"))}
            },LinearLayout.LayoutParams(dp(44),dp(48)))
        },space(0,8))
        val status=text("",13,Loft.INK);body.addView(status,space(8,8))
        status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        val progress=android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {isIndeterminate=true;visibility=View.GONE}
        body.addView(progress,space(0,8))
        val action=button("Проверить обновление",true).apply {tag="openflux-update-check";gravity=android.view.Gravity.CENTER}
        body.addView(action, space(0, 12))
        body.addView(text("Проверяем при каждом запуске приложения. Установку подтверждает Android.",12,Loft.MUTED),space(0,8))
        fun install(){
            if(preparing)return
            if(!packageManager.canRequestPackageInstalls()){
                try{startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))}catch(_:Exception){alert("Установка","Не удалось открыть разрешение установки Android.")};return
            }
            preparing=true
            val worker=Executors.newSingleThreadExecutor()
            worker.execute{
                try{
                    val prepared=EngineUpdates.consentFile(applicationContext,active()||ProbeCoordinator.measurements!=null)
                    handler.post{
                        preparing=false
                        if(prepared==null)return@post // This attempt owns no mutation; do not abort another view's consent.
                        try{
                            if(!body.isAttachedToWindow||isFinishing||isDestroyed||!hasWindowFocus()||active()||ProbeCoordinator.measurements!=null){
                                EngineUpdates.abortPreparedConsent(this@with,prepared.token);return@post
                            }
                            val request=EngineUpdates.installing(this@with,prepared.token)
                            @Suppress("DEPRECATION") val intent=Intent(Intent.ACTION_INSTALL_PACKAGE)
                                .setDataAndType(Uri.parse("content://$packageName.engine-package/${prepared.file.name}"),"application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
                            startActivityForResult(intent,request)
                        }catch(_:Exception){
                            runCatching{EngineUpdates.installerLaunchFailed(this@with,prepared.token)}
                            if(body.isAttachedToWindow&&!isFinishing&&!isDestroyed)alert("Установка","Не удалось открыть установщик. Проверь очередь обновления перед повтором.")
                            else ClientEvents.record(applicationContext,"Предупреждение","Подготовка установки OpenFlux прервана; проверь очередь обновления.")
                        }
                    }
                }catch(_:Exception){handler.post{preparing=false;if(body.isAttachedToWindow&&!isFinishing&&!isDestroyed)alert("OpenFlux","Не удалось подготовить проверенный APK. Проверь очередь обновления перед повтором.")}}
                finally{worker.shutdown()}
            }
        }
        action.setOnClickListener{when(EngineUpdatePresentation.action(EngineUpdates.snapshot(this@with).phase)){
            EngineUpdatePresentation.Action.CHECK->EngineUpdates.check(this@with)
            EngineUpdatePresentation.Action.DOWNLOAD->EngineUpdates.download(this@with)
            EngineUpdatePresentation.Action.INSTALL->install()
            EngineUpdatePresentation.Action.CANCEL->EngineUpdates.stopDownload()
            EngineUpdatePresentation.Action.WAIT->Unit
        }}
        val refresh=object:Runnable{override fun run(){
            if(!body.isAttachedToWindow||isFinishing||isDestroyed)return
            val s=EngineUpdates.snapshot(this@with);val a=EngineUpdatePresentation.action(s.phase)
            status.setTextColor(if(s.phase==EngineUpdatePolicy.Phase.ERROR)Loft.ERROR else Loft.INK)
            val downloading = s.phase == EngineUpdatePolicy.Phase.DOWNLOADING
            progress.visibility=if(preparing||s.phase in setOf(EngineUpdatePolicy.Phase.CHECKING,EngineUpdatePolicy.Phase.INSTALLING)||downloading)View.VISIBLE else View.GONE
            progress.isIndeterminate = !downloading || s.total <= 0L
            if (!progress.isIndeterminate) progress.progress = AppUpdatePresentation.progressPercent(s.received, s.total)
            status.text="Версия: ${EngineUpdates.installedSource(this@with)}\n${s.message}"+
                if(s.phase==EngineUpdatePolicy.Phase.DOWNLOADING)"\n${TrafficState.bytes(s.received)} / ${TrafficState.bytes(s.total)}"else""
            val enabled = !preparing && a != EngineUpdatePresentation.Action.WAIT && !EngineUpdateOpenFluxScripts.isBusy()
            action.text = a.label
            action.isEnabled = enabled
            handler.postDelayed(this,1000)
        }}
        body.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){EngineUpdates.recover(this@with);handler.post(refresh)}
            override fun onViewDetachedFromWindow(v:View){
                // Keep an in-flight preparation's completion: it must reset preparing
                // and abort its exact prepared consent after this view detaches.
                handler.removeCallbacks(refresh)
            }
        })
        body
    }
    /** Legacy host caller; new navigation should add view(activity) to Updates directly. */
    fun show(activity:MainActivity){ThemedDialogBuilder(activity).setTitle("OpenFlux").setView(ScrollView(activity).apply{setPadding(activity.dp(20),activity.dp(4),activity.dp(20),activity.dp(12));addView(view(activity))}).setPositiveButton("Готово",null).show()}
}
