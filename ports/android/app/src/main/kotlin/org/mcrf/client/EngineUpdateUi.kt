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
import android.widget.Switch
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
    fun view(activity:MainActivity):LinearLayout=with(activity){
        val handler=Handler(Looper.getMainLooper());var preparing=false
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));tag="openflux-updates"}
        body.addView(text("OpenFlux",16,Loft.INK))
        val auto=Switch(this).apply{text="Проверять автоматически";isChecked=EngineUpdates.automatic(this@with)}
        body.addView(auto,space(8,8))
        body.addView(text("Проверка каждые 6 часов для установленного OpenFlux. Установка отдельного подписанного модуля требует подтверждения Android.",12,Loft.MUTED),space(4,8))
        auto.setOnCheckedChangeListener{_,enabled->try{EngineUpdates.setAutomatic(this@with,enabled)}catch(_:Exception){alert("OpenFlux","Не удалось сохранить расписание проверки.")}}
        val status=text("",13,Loft.INK);body.addView(status,space(8,8))
        val action=button("Проверить обновление",false);body.addView(action)
        fun install(){
            if(preparing)return
            if(!packageManager.canRequestPackageInstalls()){
                try{startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))}catch(_:Exception){alert("Установка","Не удалось открыть разрешение установки Android.")};return
            }
            preparing=true
            val worker=Executors.newSingleThreadExecutor()
            worker.execute{
                try{
                    val file=EngineUpdates.consentFile(applicationContext,active()||ProbeCoordinator.measurements!=null)
                    handler.post{
                        preparing=false
                        if(file==null||!body.isAttachedToWindow||isFinishing||isDestroyed||!hasWindowFocus()||active()||ProbeCoordinator.measurements!=null){EngineUpdates.abortPreparedConsent(this@with);return@post}
                        try{
                            val request=EngineUpdates.installing(this@with)
                            @Suppress("DEPRECATION") val intent=Intent(Intent.ACTION_INSTALL_PACKAGE)
                                .setDataAndType(Uri.parse("content://$packageName.engine-package/${file.name}"),"application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
                            startActivityForResult(intent,request)
                        }catch(_:Exception){EngineUpdates.installerLaunchFailed(this@with);alert("Установка","Не удалось открыть установщик. APK сохранён в очереди.")}
                    }
                }catch(e:Exception){handler.post{preparing=false;if(body.isAttachedToWindow)alert("OpenFlux",e.message?:"Не удалось проверить пакет.")}}
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
            status.text="Версия: ${EngineUpdates.installedSource(this@with)}\n${s.message}"+
                if(s.phase==EngineUpdatePolicy.Phase.DOWNLOADING)"\n${TrafficState.bytes(s.received)} / ${TrafficState.bytes(s.total)}"else""
            action.text=a.label;action.isEnabled=!preparing&&a!=EngineUpdatePresentation.Action.WAIT
            handler.postDelayed(this,1000)
        }}
        body.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){EngineUpdates.recover(this@with);handler.post(refresh)}
            override fun onViewDetachedFromWindow(v:View){handler.removeCallbacksAndMessages(null);EngineUpdates.abortPreparedConsent(this@with)}
        })
        body
    }
    /** Legacy host caller; new navigation should add view(activity) to Updates directly. */
    fun show(activity:MainActivity){ThemedDialogBuilder(activity).setTitle("OpenFlux").setView(ScrollView(activity).apply{addView(view(activity))}).setPositiveButton("Готово",null).show()}
}
